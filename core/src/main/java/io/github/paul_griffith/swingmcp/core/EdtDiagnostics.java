package io.github.paul_griffith.swingmcp.core;

import java.awt.Component;
import java.awt.Window;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Event Dispatch Thread diagnostics: {@code await_idle} (flush / wait-for-condition) and {@code
 * edt_stack} (responsiveness probe + stack dump).
 *
 * <p>These are the follow-up tools after a modal-safe {@link Interactions#click}. {@code
 * await_idle} lets an agent block until a just-opened dialog appears or a component reaches a
 * state; the stack probe turns a <i>blocked</i> EDT — normally a hang — into an actionable
 * diagnosis. Crucially the stack is read via {@link Thread#getStackTrace()} from a transport
 * thread, so it works (and is most useful) precisely when the EDT is wedged and no {@code
 * invokeAndWait} could ever return.
 */
public final class EdtDiagnostics {

  /** Default overall timeout for {@code await_idle} (the shared core default). */
  public static final Duration DEFAULT_AWAIT_TIMEOUT = EdtOps.DEFAULT_TIMEOUT;

  /** Default responsiveness threshold for {@code edt_stack}. */
  public static final Duration DEFAULT_RESPONSIVENESS_THRESHOLD = Duration.ofMillis(200);

  /** How long between condition polls. */
  private static final long POLL_INTERVAL_MS = 25;

  /** Max frames of EDT stack to report. */
  private static final int MAX_STACK_FRAMES = 60;

  private EdtDiagnostics() {}

  /** A component-state predicate {@code await_idle} can wait for. */
  public enum ComponentState {
    VISIBLE,
    SHOWING,
    ENABLED,
    FOCUSED;

    /** Parses a case-insensitive name, or {@code null} if unknown. */
    public static ComponentState parse(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }

  /**
   * Outcome of an {@code await_idle}.
   *
   * @param met {@code true} if the condition held (or the flush completed) before timeout
   * @param elapsedMs wall-clock milliseconds spent
   * @param flushOnly {@code true} when there was no condition — the call just drained the EDT
   */
  public record AwaitResult(boolean met, long elapsedMs, boolean flushOnly) {}

  /**
   * Result of the {@code edt_stack} probe.
   *
   * @param edtFound whether an {@code AWT-EventQueue} thread was located
   * @param threadName the EDT thread's name, or {@code null} when not found
   * @param responsive {@code true} if a no-op drained the EDT within the threshold
   * @param latencyMs measured no-op latency; equals the wait cap when the EDT never answered
   * @param thresholdMs the threshold the verdict was made against
   * @param stack the EDT's current stack frames (outermost first), or empty when not found
   */
  public record EdtStatus(
      boolean edtFound,
      String threadName,
      boolean responsive,
      long latencyMs,
      long thresholdMs,
      List<String> stack) {}

  // ---------------------------------------------------------------------------------------
  // await_idle
  // ---------------------------------------------------------------------------------------

  /**
   * No-condition {@code await_idle}: drains the EDT by running a no-op to completion (so every
   * event queued before this call has been processed), then returns.
   */
  public static AwaitResult flush(Duration timeout) {
    long start = System.nanoTime();
    boolean met = Interactions.tryFlush(timeout);
    return new AwaitResult(met, elapsedMs(start), true);
  }

  /** Waits until a visible window's title contains {@code substring} (case-insensitive). */
  public static AwaitResult awaitWindowTitle(String substring, Duration timeout) {
    String needle = substring.toLowerCase(Locale.ROOT);
    return await(() -> anyWindowTitleContains(needle), timeout);
  }

  /** Waits until the component addressed by {@code componentId} reaches {@code state}. */
  public static AwaitResult awaitComponentState(
      String componentId, ComponentState state, Duration timeout) {
    return await(() -> componentInState(componentId, state), timeout);
  }

  /**
   * Waits until the component addressed by {@code componentId} has a display text containing {@code
   * substring} (case-insensitive) — the natural way to await an async status update (a label or
   * field changing) after driving the UI.
   */
  public static AwaitResult awaitComponentText(
      String componentId, String substring, Duration timeout) {
    String needle = substring.toLowerCase(Locale.ROOT);
    return await(() -> componentTextContains(componentId, needle), timeout);
  }

  /**
   * Polls {@code condition} (evaluated on the EDT) every {@link #POLL_INTERVAL_MS} until met or
   * timeout.
   */
  private static AwaitResult await(BooleanSupplier edtCondition, Duration timeout) {
    long start = System.nanoTime();
    long deadline = start + timeout.toNanos();
    while (true) {
      if (evaluateOnEdt(edtCondition)) {
        return new AwaitResult(true, elapsedMs(start), false);
      }
      if (System.nanoTime() >= deadline) {
        return new AwaitResult(false, elapsedMs(start), false);
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new AwaitResult(false, elapsedMs(start), false);
      }
    }
  }

  /** Evaluates a predicate on the EDT; a blocked EDT (timeout) reads as "not yet met". */
  private static boolean evaluateOnEdt(BooleanSupplier condition) {
    try {
      return EdtOps.invokeAndWait(condition::getAsBoolean, Duration.ofMillis(500));
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean anyWindowTitleContains(String needleLower) {
    for (Window window : Window.getWindows()) {
      if (!window.isShowing()) {
        continue;
      }
      String title = titleOf(window);
      if (title != null && title.toLowerCase(Locale.ROOT).contains(needleLower)) {
        return true;
      }
    }
    return false;
  }

  private static String titleOf(Window window) {
    if (window instanceof java.awt.Frame frame) {
      return frame.getTitle();
    }
    if (window instanceof java.awt.Dialog dialog) {
      return dialog.getTitle();
    }
    return null;
  }

  private static boolean componentInState(String componentId, ComponentState state) {
    Component component;
    try {
      // Evaluated inside an on-EDT task (see evaluateOnEdt), so this resolve runs inline.
      component = ComponentAddress.resolve(componentId);
    } catch (RuntimeException e) {
      return false; // not present yet, or ambiguous — treat as not-met, keep polling
    }
    if (component == null) {
      return false;
    }
    return switch (state) {
      case VISIBLE -> component.isVisible();
      case SHOWING -> component.isShowing();
      case ENABLED -> component.isEnabled();
      case FOCUSED -> component.isFocusOwner();
    };
  }

  private static boolean componentTextContains(String componentId, String needleLower) {
    Component component;
    try {
      // Evaluated inside an on-EDT task (see evaluateOnEdt), so this resolve runs inline.
      component = ComponentAddress.resolve(componentId);
    } catch (RuntimeException e) {
      return false; // not present yet, or ambiguous — treat as not-met, keep polling
    }
    if (component == null) {
      return false;
    }
    String text = Introspection.displayTextOf(component);
    return text != null && text.toLowerCase(Locale.ROOT).contains(needleLower);
  }

  // ---------------------------------------------------------------------------------------
  // edt_stack
  // ---------------------------------------------------------------------------------------

  /**
   * Probes EDT responsiveness and captures its stack. Posts a no-op and times how long the EDT
   * takes to run it (bounded by a wait cap so the probe always returns, even against a wedged EDT),
   * then reads the EDT thread's stack via {@link Thread#getStackTrace()} — from this transport
   * thread, so it succeeds even when the EDT is fully blocked.
   */
  public static EdtStatus probeStack(Duration threshold) {
    long thresholdMs = threshold.toMillis();
    long capMs = Math.max(thresholdMs, 750);

    Thread edt = findEventDispatchThread();

    long start = System.nanoTime();
    CountDownLatch drained = new CountDownLatch(1);
    javax.swing.SwingUtilities.invokeLater(drained::countDown);
    boolean ran;
    try {
      ran = drained.await(capMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ran = false;
    }
    long latencyMs = ran ? elapsedMs(start) : capMs;
    boolean responsive = ran && latencyMs <= thresholdMs;

    List<String> stack = edt == null ? List.of() : captureStack(edt);
    return new EdtStatus(
        edt != null, edt == null ? null : edt.getName(), responsive, latencyMs, thresholdMs, stack);
  }

  /** Finds an {@code AWT-EventQueue-*} thread, reading the live thread set (not the EDT itself). */
  private static Thread findEventDispatchThread() {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      String name = thread.getName();
      if (name != null && name.startsWith("AWT-EventQueue")) {
        return thread;
      }
    }
    return null;
  }

  private static List<String> captureStack(Thread thread) {
    StackTraceElement[] frames = thread.getStackTrace();
    List<String> out = new ArrayList<>(Math.min(frames.length, MAX_STACK_FRAMES));
    for (int i = 0; i < frames.length && i < MAX_STACK_FRAMES; i++) {
      out.add("at " + frames[i]);
    }
    if (frames.length > MAX_STACK_FRAMES) {
      out.add("… (" + (frames.length - MAX_STACK_FRAMES) + " more frames)");
    }
    return List.copyOf(out);
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
