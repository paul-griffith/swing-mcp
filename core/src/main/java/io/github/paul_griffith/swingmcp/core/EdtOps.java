package io.github.paul_griffith.swingmcp.core;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;

/**
 * Event Dispatch Thread helpers.
 *
 * <p>Every Swing read and every synthetic interaction the agent performs must run on the EDT.
 * Transport threads call into these helpers, which hop to the EDT and, critically, apply a timeout:
 * a blocked EDT must surface as a diagnosable timeout rather than a hung tool call (see the {@code
 * edt_stack} tool in the plan).
 *
 * <p>Milestone&nbsp;1: {@link #invokeAndWait(Callable, Duration)} is implemented because the
 * timeout discipline is the load-bearing primitive and is unit-testable headlessly. The richer
 * diagnostics (EDT stack capture, responsiveness probe) arrive in milestone&nbsp;4.
 */
public final class EdtOps {

  /**
   * The one shared default EDT wait for reads and synchronous drives across {@code core}. A blocked
   * EDT surfaces as an {@link EdtTimeoutException} after this long rather than hanging the tool.
   * {@link Screenshots} deliberately uses a longer default (rendering a large window can
   * legitimately take longer than a bean read); everything else references this.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private EdtOps() {}

  /**
   * Runs {@code task} on the EDT and returns its result, waiting at most {@code timeout}.
   *
   * <p>If the caller is already on the EDT, the task runs inline (there is nothing to wait for).
   * Otherwise it is dispatched via {@link SwingUtilities#invokeLater} and the result is awaited
   * with a bound.
   *
   * @throws EdtTimeoutException if the EDT does not complete the task within {@code timeout}
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws Exception any exception thrown by {@code task} itself
   */
  public static <T> T invokeAndWait(Callable<T> task, Duration timeout) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return task.call();
    }
    FutureTask<T> future = new FutureTask<>(task);
    SwingUtilities.invokeLater(future);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(false);
      throw new EdtTimeoutException(
          "EDT did not complete task within " + timeout.toMillis() + " ms", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw e;
    }
  }

  /**
   * Runs a side-effecting action on the EDT with a timeout, discarding any result.
   *
   * <p>Note: actions that may open a modal dialog must NOT use this method — per the plan's
   * modal-dialog rule they are dispatched fire-and-forget via {@link SwingUtilities#invokeLater} so
   * they cannot deadlock on the modal loop.
   */
  public static void runAndWait(Runnable action, Duration timeout) throws Exception {
    invokeAndWait(
        () -> {
          action.run();
          return null;
        },
        timeout);
  }

  /**
   * Runs {@code task} on the EDT with a timeout, applying the module-wide checked-exception policy
   * so callers need not repeat it: a {@link RuntimeException} (including {@link
   * EdtTimeoutException} and the {@link ComponentAddress} resolver exceptions) passes through
   * unchanged, and any other checked exception is wrapped in {@link IllegalStateException}. This is
   * the non-throwing façade over {@link #invokeAndWait} that every {@code core} entry point uses.
   *
   * @throws EdtTimeoutException if the EDT does not complete the task within {@code timeout}
   */
  public static <T> T call(Callable<T> task, Duration timeout) {
    try {
      return invokeAndWait(task, timeout);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected checked exception on the EDT", e);
    }
  }

  /**
   * Side-effecting {@link #call}: runs {@code action} on the EDT with a timeout, discarding any
   * result.
   */
  public static void run(Runnable action, Duration timeout) {
    call(
        () -> {
          action.run();
          return null;
        },
        timeout);
  }

  /** Thrown when the EDT fails to complete work within the allotted time. */
  public static final class EdtTimeoutException extends RuntimeException {
    public EdtTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
