package io.github.paul_griffith.swingmcp.core;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

/**
 * Synthetic Swing interaction: the driving half of the agent. Every method here mutates or drives a
 * live {@link Component} on the Event Dispatch Thread, mirroring the EDT-safety and
 * programmatic-first patterns of AssertJ&nbsp;Swing / Jemmy without taking them as dependencies.
 *
 * <h2>Programmatic first</h2>
 *
 * Actions prefer the deterministic Swing API over OS-level input: {@link AbstractButton#doClick()}
 * for buttons and menu items, {@link JTextComponent#setText} for a fast state set, and synthesized
 * {@link MouseEvent}/{@link KeyEvent} dispatch (delivered straight to the component's event
 * pipeline) for everything else. This needs no OS focus, no screen coordinates, and no macOS
 * accessibility permission — {@code java.awt.Robot} input is a later, opt-in mode.
 *
 * <h2>Modal-safe dispatch</h2>
 *
 * A click or a menu-item activation can open a <b>modal</b> dialog, whose {@code setVisible(true)}
 * starts a nested event loop and never returns until the dialog closes. Blocking a transport thread
 * on that with {@code invokeAndWait} would hang the tool. So {@link #click} and menu activation
 * post the action fire-and-forget via {@link SwingUtilities#invokeLater} and return promptly, then
 * probe the EDT with a short {@linkplain #DISPATCH_GRACE bounded grace period} (a no-op {@code
 * invokeAndWait}); the returned {@link DispatchResult#edtSettled()} tells the caller whether the
 * EDT drained the action (settled) or is still busy/blocked — the cue to follow up with {@code
 * await_idle} / {@code list_windows} / {@code edt_stack}.
 *
 * <p>Each modal-safe dispatch also reports the <b>UI delta</b> it caused: the showing window/popup
 * set is snapshotted on the EDT just before the action is posted and again by the settle probe, and
 * the diff is returned as a {@link WindowDelta} ({@code opened}/{@code closed}), so a caller learns
 * what a click opened without a follow-up {@code list_windows}.
 */
public final class Interactions {

  /** Default EDT timeout for the synchronous (non-modal) actions (the shared core default). */
  public static final Duration DEFAULT_TIMEOUT = EdtOps.DEFAULT_TIMEOUT;

  /** Per-character EDT-budget allowance added to {@link #DEFAULT_TIMEOUT} by {@link #typeText}. */
  private static final int TYPE_MS_PER_CHAR = 10;

  /**
   * Bounded grace period given to the EDT after a modal-safe dispatch. If a no-op does not drain
   * within this window the EDT is reported busy/blocked rather than the tool hanging.
   */
  public static final Duration DISPATCH_GRACE = Duration.ofMillis(250);

  /** Cap for rendering a selected value into a human-readable description. */
  private static final int VALUE_CAP = 80;

  private Interactions() {}

  /** Which mouse button a synthesized click uses. */
  public enum MouseButton {
    LEFT(MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK),
    MIDDLE(MouseEvent.BUTTON2, InputEvent.BUTTON2_DOWN_MASK),
    RIGHT(MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK);

    final int buttonId;
    final int downMask;

    MouseButton(int buttonId, int downMask) {
      this.buttonId = buttonId;
      this.downMask = downMask;
    }
  }

  /**
   * Outcome of a modal-safe dispatch: the action was posted to the EDT (always {@code true} once
   * this returns without error), whether the EDT {@linkplain #edtSettled() settled} within the
   * {@linkplain #DISPATCH_GRACE grace period}, the {@linkplain WindowDelta window/popup delta} the
   * dispatch caused, and a best-effort read of whether the target could plausibly respond to the
   * click.
   *
   * @param edtSettled {@code true} if the settle probe drained through the EDT within the grace
   *     period (the EDT is responsive — note a settled EDT may still now be pumping a modal
   *     dialog's nested loop); {@code false} if it is still busy or blocked
   * @param actionable {@code true} if the target is a button or has at least one registered {@code
   *     MouseListener}; {@code false} flags a click that likely lands on nothing (e.g. addressed at
   *     a plain container). Defaults to {@code true} when the probe could not read the EDT
   * @param mouseListeners the number of {@code MouseListener}s on the target, or {@code -1} if the
   *     probe could not read the EDT
   * @param enabled the target's {@link Component#isEnabled()} — {@code false} means the click will
   *     no-op (a disabled button's {@code doClick} does nothing). Defaults to {@code true} when the
   *     probe could not read the EDT
   * @param showing the target's {@link Component#isShowing()} — {@code false} means the target is
   *     not on a visible, laid-out window, so the click may have no effect. Defaults to {@code
   *     true} when the probe could not read the EDT
   * @param delta the windows/popups the dispatch opened or closed, or {@code null} when even the
   *     bounded after-snapshot timed out (the delta is unknown)
   */
  public record DispatchResult(
      boolean edtSettled,
      boolean actionable,
      int mouseListeners,
      boolean enabled,
      boolean showing,
      WindowDelta delta) {}

  /**
   * Outcome of a {@code select}.
   *
   * @param modalSafeDispatch {@code true} for menu-item activation (which may open a modal dialog,
   *     so it was dispatched fire-and-forget); {@code false} for a synchronous selection that
   *     completed before returning
   * @param edtSettled for a {@code modalSafeDispatch}, whether the EDT settled within the grace
   *     period; always {@code true} for a synchronous selection
   * @param description a short human-readable description of what was selected/activated
   * @param delta for a {@code modalSafeDispatch}, the windows/popups the activation opened or
   *     closed ({@code null} = unknown, the bounded after-snapshot timed out); always {@code null}
   *     for a synchronous selection, which cannot open windows
   */
  public record SelectionResult(
      boolean modalSafeDispatch, boolean edtSettled, String description, WindowDelta delta) {

    /** Convenience for the synchronous selections, which carry no window delta. */
    public SelectionResult(boolean modalSafeDispatch, boolean edtSettled, String description) {
      this(modalSafeDispatch, edtSettled, description, null);
    }
  }

  // ---------------------------------------------------------------------------------------
  // window-delta observation (opened/closed reporting for modal-safe dispatch)
  // ---------------------------------------------------------------------------------------

  /**
   * One member of the showing window/popup set, observed around a modal-safe dispatch.
   *
   * @param id the handle the other tools accept: the window's unique {@code getName()} when set,
   *     otherwise {@code w<handle>}; for a popup, the {@link JPopupMenu}'s component id
   * @param kind {@code frame}, {@code dialog}, {@code window}, or {@code popup}
   * @param title the frame/dialog title (or a popup's label), or {@code null}
   * @param bounds on-screen bounds in logical px
   */
  public record WindowRef(String id, String kind, String title, Bounds bounds) {}

  /**
   * The showing window/popup set diff across a modal-safe dispatch: what appeared and what went
   * away between the moment just before the action was posted and the settle probe after it.
   *
   * @param opened windows/popups showing after the dispatch that were not before
   * @param closed the ids of windows/popups showing before the dispatch that no longer are
   */
  public record WindowDelta(List<WindowRef> opened, List<String> closed) {
    /** {@code true} when the dispatch neither opened nor closed anything. */
    public boolean isEmpty() {
      return opened.isEmpty() && closed.isEmpty();
    }
  }

  /**
   * Settling outcome of a modal-safe dispatch that carries no other probe data ({@code press_key}).
   *
   * @param edtSettled whether the settle probe drained within the {@linkplain #DISPATCH_GRACE grace
   *     period}
   * @param delta the window/popup delta, or {@code null} when even the bounded after-snapshot timed
   *     out (delta unknown)
   */
  public record Settle(boolean edtSettled, WindowDelta delta) {}

  /**
   * On the EDT: the current showing window/popup set, keyed for diffing (stable window handle /
   * popup identity — deliberately not the emitted id, whose name-uniqueness can shift between the
   * two snapshots).
   *
   * <p>Popup-<i>container</i> windows ({@link Window.Type#POPUP}: heavyweight menu popups,
   * tooltips) are excluded from the window scan; menu popups are instead captured as the {@link
   * JPopupMenu} itself via {@link MenuSelectionManager}, which covers lightweight and heavyweight
   * popups uniformly (and keeps tooltip flicker out of the delta).
   */
  private static Map<String, WindowRef> showingWindowsOnEdt() {
    Map<String, WindowRef> out = new LinkedHashMap<>();
    List<Window> windows = ComponentAddress.nameScopeWindows();
    Map<String, Integer> nameFrequency = ComponentAddress.nameFrequency(windows);
    for (Window window : windows) {
      if (!window.isShowing() || window.getType() == Window.Type.POPUP) {
        continue;
      }
      int handle = ComponentAddress.windowHandle(window);
      String name = ComponentAddress.usableName(window);
      String id = (name != null && nameFrequency.getOrDefault(name, 0) == 1) ? name : "w" + handle;
      String kind;
      String title;
      if (window instanceof Frame frame) {
        kind = "frame";
        title = frame.getTitle();
      } else if (window instanceof Dialog dialog) {
        kind = "dialog";
        title = dialog.getTitle();
      } else {
        kind = "window";
        title = null;
      }
      out.put(
          "w" + handle, new WindowRef(id, kind, blankToNull(title), Bounds.of(window.getBounds())));
    }
    for (MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
      if (element instanceof JPopupMenu popup && popup.isShowing()) {
        Bounds bounds = Bounds.of(new Rectangle(popup.getLocationOnScreen(), popup.getSize()));
        out.put(
            "popup#" + System.identityHashCode(popup),
            new WindowRef(
                ComponentAddress.idOf(popup), "popup", blankToNull(popup.getLabel()), bounds));
      }
    }
    return out;
  }

  /**
   * The settle wait for a modal-safe dispatch, upgraded from a bare no-op flush: a bounded EDT task
   * takes the <i>after</i> snapshot of the showing window/popup set and diffs it against {@code
   * before}. Because the task is queued behind the just-posted action, a synchronously-opened
   * window (even a modal dialog — its nested loop keeps pumping the queue) is visible to it. When
   * the EDT does not answer within the grace period the delta is unknown ({@code null}) and {@code
   * edtSettled} is {@code false} — exactly the old {@code tryFlush} semantics.
   */
  private static Settle settleAndDiff(Map<String, WindowRef> before, Duration grace) {
    Map<String, WindowRef> after;
    try {
      after = EdtOps.call(Interactions::showingWindowsOnEdt, grace);
    } catch (Exception e) {
      return new Settle(false, null);
    }
    List<WindowRef> opened = new ArrayList<>();
    for (Map.Entry<String, WindowRef> entry : after.entrySet()) {
      if (!before.containsKey(entry.getKey())) {
        opened.add(entry.getValue());
      }
    }
    List<String> closed = new ArrayList<>();
    for (Map.Entry<String, WindowRef> entry : before.entrySet()) {
      if (!after.containsKey(entry.getKey())) {
        closed.add(entry.getValue().id());
      }
    }
    return new Settle(true, new WindowDelta(List.copyOf(opened), List.copyOf(closed)));
  }

  private static String blankToNull(String text) {
    return (text != null && !text.isBlank()) ? text : null;
  }

  // ---------------------------------------------------------------------------------------
  // click
  // ---------------------------------------------------------------------------------------

  /** {@link #click(Component, MouseButton, int, Integer, Integer)} at the component center. */
  public static DispatchResult click(Component component, MouseButton button, int count) {
    return click(component, button, count, null, null);
  }

  /**
   * Clicks {@code component} (modal-safe). A left single-click on an {@link AbstractButton} with no
   * explicit point uses {@link AbstractButton#doClick()}; every other case synthesizes a {@link
   * MouseEvent} sequence — a rollover ({@code ENTERED}/{@code MOVED}) to arm hover-sensitive
   * targets, then press/release/click, then {@code EXITED} — delivered directly to the component at
   * {@code (x, y)} or, when those are {@code null}, its center.
   *
   * <p>The explicit point exists for hit-regions the component tree does not expose as children —
   * e.g. an overlay "clear ✕" a text field paints at its right edge, which a center click misses.
   *
   * <p>The probe read and the {@link SwingUtilities#invokeLater} dispatch run in a single EDT task,
   * so the action cannot deadlock the caller if it opens a modal dialog; see {@link
   * DispatchResult}. Prefer {@link #clickById} when acting on an addressed component so the resolve
   * rides in that same task.
   *
   * @param component the target (must be non-null)
   * @param button which mouse button (ignored by the {@code doClick} fast path, which is left-only)
   * @param count number of clicks (≥ 1; {@code 2} = double-click)
   * @param x x within the component in logical px, or {@code null} for the horizontal center
   * @param y y within the component in logical px, or {@code null} for the vertical center
   * @return the dispatch outcome
   */
  public static DispatchResult click(
      Component component, MouseButton button, int count, Integer x, Integer y) {
    return click(component, button, count, ClickPoint.at(x, y));
  }

  /** {@link #click(Component, MouseButton, int, Integer, Integer)} with a resolved click point. */
  public static DispatchResult click(
      Component component, MouseButton button, int count, ClickPoint point) {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(button, "button");
    ClickProbe probe =
        runOnEdt(() -> postClickOnEdt(component, button, count, point), DEFAULT_TIMEOUT);
    return dispatchResult(probe);
  }

  /**
   * Where a click lands within its target: an explicit logical point, a {@link JTable} cell, or —
   * when nothing is set — the component's centre.
   *
   * <p>The cell form exists because addressing a table cell by pixel arithmetic (multiplying a row
   * index by {@code rowHeight}) is both tedious and wrong the moment row heights vary or the table
   * is scrolled; {@link JTable#getCellRect} already knows the answer.
   */
  public record ClickPoint(Integer x, Integer y, Integer row, Integer column) {

    /** Click the component's centre. */
    public static final ClickPoint CENTER = new ClickPoint(null, null, null, null);

    public ClickPoint {
      boolean hasPoint = x != null || y != null;
      boolean hasCell = row != null || column != null;
      if (hasPoint && hasCell) {
        throw new IllegalArgumentException(
            "give either x/y or row/column, not both — a table-cell click computes its own point");
      }
      if (hasCell && (row == null || column == null)) {
        throw new IllegalArgumentException("a table-cell click needs both row and column");
      }
    }

    /** An explicit point, or {@link #CENTER} when both coordinates are absent. */
    public static ClickPoint at(Integer x, Integer y) {
      return new ClickPoint(x, y, null, null);
    }

    /** The centre of a {@link JTable} cell, in view (not model) coordinates. */
    public static ClickPoint cell(int row, int column) {
      return new ClickPoint(null, null, row, column);
    }
  }

  /**
   * Resolves {@code id} against the live windows and clicks it, with the resolve, the click probe,
   * and the fire-and-forget dispatch all folded into <b>one</b> EDT task (closing the resolve→act
   * TOCTOU and spending at most one EDT timeout), then reports whether the EDT settled within the
   * {@linkplain #DISPATCH_GRACE grace period}. The modal-safe counterpart to {@link
   * ComponentAddress#withComponent}.
   *
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws ComponentAddress.AmbiguousComponentException if a name id is no longer unique
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static DispatchResult clickById(
      String id, MouseButton button, int count, Integer x, Integer y, Duration timeout) {
    return clickById(id, button, count, ClickPoint.at(x, y), timeout);
  }

  /**
   * {@link #clickById(String, MouseButton, int, Integer, Integer, Duration)} with a click point.
   */
  public static DispatchResult clickById(
      String id, MouseButton button, int count, ClickPoint point, Duration timeout) {
    Objects.requireNonNull(button, "button");
    ClickProbe probe =
        ComponentAddress.withComponent(
            id, timeout, component -> postClickOnEdt(component, button, count, point));
    return dispatchResult(probe);
  }

  private record ClickProbe(
      boolean actionable,
      int mouseListeners,
      boolean enabled,
      boolean showing,
      Map<String, WindowRef> windowsBefore) {}

  /**
   * On the EDT: snapshots the showing window/popup set (the delta baseline), reads the click probe
   * (listener presence, enabled/showing), and posts the click action fire-and-forget via {@link
   * SwingUtilities#invokeLater}, so the probe rides with dispatch in a single task rather than
   * preceding it in a separate hop. Never fails the click. Reports {@link Component#isEnabled()}
   * and {@link Component#isShowing()} so the caller can warn about a click that will silently no-op
   * (a disabled or not-yet-shown target).
   */
  private static ClickProbe postClickOnEdt(
      Component component, MouseButton button, int count, ClickPoint point) {
    Integer x = point.x();
    Integer y = point.y();
    if (point.row() != null) {
      Point cell = tableCellCentre(component, point.row(), point.column());
      x = cell.x;
      y = cell.y;
    }
    Map<String, WindowRef> before = showingWindowsOnEdt();
    int clicks = Math.max(1, count);
    int listeners = component.getMouseListeners().length;
    boolean actionable = component instanceof AbstractButton || listeners > 0;
    Integer px = x;
    Integer py = y;
    Runnable action;
    if (component instanceof AbstractButton abstractButton
        && button == MouseButton.LEFT
        && clicks == 1
        && px == null
        && py == null) {
      action = abstractButton::doClick;
    } else {
      action = () -> synthesizeClicks(component, button, clicks, px, py);
    }
    SwingUtilities.invokeLater(action);
    return new ClickProbe(
        actionable, listeners, component.isEnabled(), component.isShowing(), before);
  }

  /**
   * Wraps a probe with the grace-period settle-and-diff read (done on the calling transport
   * thread).
   */
  private static DispatchResult dispatchResult(ClickProbe probe) {
    Settle settle = settleAndDiff(probe.windowsBefore(), DISPATCH_GRACE);
    return new DispatchResult(
        settle.edtSettled(),
        probe.actionable(),
        probe.mouseListeners(),
        probe.enabled(),
        probe.showing(),
        settle.delta());
  }

  /**
   * Dispatches a rollover-then-press/release/click sequence at {@code (px, py)} (or the component
   * center when either is {@code null}). Runs on the EDT.
   */
  private static void synthesizeClicks(
      Component component, MouseButton button, int count, Integer px, Integer py) {
    int x = px != null ? px : Math.max(0, component.getWidth() / 2);
    int y = py != null ? py : Math.max(0, component.getHeight() / 2);
    boolean popupTrigger = button == MouseButton.RIGHT;
    long base = System.currentTimeMillis();
    // Arm hover-sensitive targets (rollover-highlighted overlay buttons, etc.) before pressing.
    component.dispatchEvent(
        new MouseEvent(
            component, MouseEvent.MOUSE_ENTERED, base, 0, x, y, 0, false, MouseEvent.NOBUTTON));
    component.dispatchEvent(
        new MouseEvent(
            component, MouseEvent.MOUSE_MOVED, base, 0, x, y, 0, false, MouseEvent.NOBUTTON));
    for (int i = 1; i <= count; i++) {
      long when = base + 2L * i;
      component.dispatchEvent(
          new MouseEvent(
              component,
              MouseEvent.MOUSE_PRESSED,
              when,
              button.downMask,
              x,
              y,
              i,
              false,
              button.buttonId));
      component.dispatchEvent(
          new MouseEvent(
              component,
              MouseEvent.MOUSE_RELEASED,
              when + 1,
              0,
              x,
              y,
              i,
              popupTrigger,
              button.buttonId));
      component.dispatchEvent(
          new MouseEvent(
              component, MouseEvent.MOUSE_CLICKED, when + 1, 0, x, y, i, false, button.buttonId));
    }
    component.dispatchEvent(
        new MouseEvent(
            component,
            MouseEvent.MOUSE_EXITED,
            base + 2L * count + 2,
            0,
            x,
            y,
            0,
            false,
            MouseEvent.NOBUTTON));
  }

  // ---------------------------------------------------------------------------------------
  // text
  // ---------------------------------------------------------------------------------------

  /**
   * Sets the text of a {@link JTextComponent} directly (fast state set; fires the document's own
   * change events but bypasses per-keystroke key bindings and input validation). Runs to completion
   * on the EDT.
   *
   * <p>When {@code component} is not itself a text component but a container of exactly one (a
   * common "named composite widget" pattern — e.g. a filter panel wrapping a field), the operation
   * {@linkplain #resolveTextTarget auto-descends} to that inner field.
   *
   * @return the {@link JTextComponent} that actually received the text (possibly a descendant of
   *     {@code component})
   * @throws IllegalArgumentException if {@code component} is neither a text component nor the
   *     unique container of one
   */
  public static Component setText(Component component, String text) {
    return setText(component, text, false);
  }

  /**
   * {@link #setText(Component, String)} with an append mode. {@code append} moves the caret to the
   * end of the document and calls {@code replaceSelection}, so the text arrives through the same
   * document-edit path a user's typing would — the three-call click/{@code press_key
   * meta+end}/{@code type_text} workaround, in one call.
   *
   * <p>Because {@code replaceSelection} is the editing path, it silently does nothing on a
   * read-only target; rather than report a successful no-op, appending to a disabled or
   * non-editable component is rejected.
   *
   * @throws IllegalArgumentException if the target is not a text component (or the unique container
   *     of one), or if {@code append} is asked of a non-editable/disabled one
   */
  public static Component setText(Component component, String text, boolean append) {
    Objects.requireNonNull(component, "component");
    String value = text != null ? text : "";
    return runOnEdt(
        () -> {
          JTextComponent textComponent = resolveTextTarget(component);
          if (!append) {
            textComponent.setText(value);
            return textComponent;
          }
          if (!textComponent.isEditable() || !textComponent.isEnabled()) {
            throw new IllegalArgumentException(
                describe(textComponent)
                    + " is "
                    + (textComponent.isEnabled() ? "not editable" : "disabled")
                    + ", so an append would silently do nothing; use mode 'replace' to set its "
                    + "text programmatically");
          }
          textComponent.setCaretPosition(textComponent.getDocument().getLength());
          textComponent.replaceSelection(value);
          return textComponent;
        },
        DEFAULT_TIMEOUT);
  }

  /**
   * Resolves the {@link JTextComponent} a text operation should act on: {@code target} itself when
   * it is one, otherwise the single text-component descendant reached by descending its subtree.
   * Among candidates, visible-and-editable fields are preferred when that narrows the choice, so a
   * composite pairing a read-only display with an edit field resolves to the editable one. Call on
   * the EDT.
   *
   * @throws IllegalArgumentException if {@code target} is not a text component and has zero, or
   *     more than one, text-component descendant to disambiguate to
   */
  static JTextComponent resolveTextTarget(Component target) {
    JTextComponent found = textTargetOrNull(target);
    if (found == null) {
      throw new IllegalArgumentException(
          describe(target)
              + " is not a text component (JTextComponent) and has no "
              + "text-field descendant to type into");
    }
    return found;
  }

  /**
   * {@link #resolveTextTarget} without the "no text component here" failure: returns {@code null}
   * when {@code target} has no text-component candidate at all, so a *read* ({@code get_text}) can
   * fall back to best-effort display text. An <i>ambiguous</i> target still throws — "which of
   * these 3 fields?" is a question the caller must answer either way, and silently picking one
   * would read the wrong field.
   *
   * <p>Call on the EDT.
   */
  static JTextComponent textTargetOrNull(Component target) {
    if (target instanceof JTextComponent textComponent) {
      return textComponent;
    }
    List<JTextComponent> visible = new ArrayList<>();
    collectTextComponents(target, visible);
    List<JTextComponent> editable = new ArrayList<>();
    for (JTextComponent candidate : visible) {
      if (candidate.isEnabled() && candidate.isEditable()) {
        editable.add(candidate);
      }
    }
    List<JTextComponent> candidates = editable.isEmpty() ? visible : editable;
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      return null;
    }
    throw new IllegalArgumentException(
        describe(target)
            + " contains "
            + candidates.size()
            + " text fields; address one "
            + "directly (candidates: "
            + describeAll(candidates)
            + ")");
  }

  /**
   * Collects the visible {@link JTextComponent} descendants of {@code component}, in tree order.
   */
  private static void collectTextComponents(Component component, List<JTextComponent> out) {
    if (!component.isVisible()) {
      return;
    }
    if (component instanceof JTextComponent textComponent) {
      out.add(textComponent);
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectTextComponents(child, out);
      }
    }
  }

  /**
   * Focuses {@code component} and types {@code text} one character at a time by delivering a
   * KEY_PRESSED / KEY_TYPED / KEY_RELEASED trio per character straight into the component's event
   * pipeline. Unlike {@link #setText}, this exercises {@code DocumentListener}s, key bindings, and
   * input validation exactly as real typing would. Runs to completion on the EDT.
   *
   * <p>Events are delivered via {@link KeyboardFocusManager#redispatchEvent} rather than a bare
   * {@link Component#dispatchEvent}: that targets this exact component and suppresses the focus
   * manager's normal re-routing, so the keys land on the intended component whether or not it has
   * become the real focus owner (focus transfer is asynchronous), and it works without a shown
   * window.
   *
   * <p>Like {@link #setText}, this requires a text target: when {@code component} is not itself a
   * {@link JTextComponent} it {@linkplain #resolveTextTarget auto-descends} to the unique text
   * field within it, and rejects a target that has none — so typing at a bare container (which
   * previously reported success while doing nothing) now fails loudly.
   *
   * <p>The whole string is typed inside one EDT task, so the EDT budget is scaled with its length
   * ({@link #DEFAULT_TIMEOUT} plus {@value #TYPE_MS_PER_CHAR} ms per character): a long string into
   * a field with heavy {@code DocumentListener}s can otherwise exceed a fixed timeout and leave
   * partially-typed state.
   *
   * @return the {@link JTextComponent} the text was typed into (possibly a descendant of {@code
   *     component})
   * @throws IllegalArgumentException if {@code component} is neither a text component nor the
   *     unique container of one
   */
  public static Component typeText(Component component, String text) {
    Objects.requireNonNull(component, "component");
    String value = text != null ? text : "";
    return runOnEdt(
        () -> {
          JTextComponent target = resolveTextTarget(component);
          target.requestFocusInWindow();
          KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
          for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            long when = System.currentTimeMillis();
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(ch);
            focusManager.redispatchEvent(
                target,
                new KeyEvent(
                    target, KeyEvent.KEY_PRESSED, when, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
            focusManager.redispatchEvent(
                target,
                new KeyEvent(target, KeyEvent.KEY_TYPED, when, 0, KeyEvent.VK_UNDEFINED, ch));
            focusManager.redispatchEvent(
                target,
                new KeyEvent(
                    target, KeyEvent.KEY_RELEASED, when, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
          }
          return target;
        },
        typeTextTimeout(value.length()));
  }

  /**
   * The EDT budget for typing a string of {@code length} characters: the base {@link
   * #DEFAULT_TIMEOUT} plus {@value #TYPE_MS_PER_CHAR} ms per character, so typing a long string
   * (which runs synchronously on the EDT, one key trio per char) does not time out mid-string. A
   * caller that folds {@link #typeText} into its own single EDT task (e.g. via {@link
   * ComponentAddress#withComponent}) should size that task with this same budget.
   */
  public static Duration typeTextTimeout(int length) {
    return DEFAULT_TIMEOUT.plusMillis((long) TYPE_MS_PER_CHAR * Math.max(0, length));
  }

  // ---------------------------------------------------------------------------------------
  // key
  // ---------------------------------------------------------------------------------------

  /**
   * Delivers a single named key or chord to {@code component} as a KEY_PRESSED / (optional)
   * KEY_TYPED / KEY_RELEASED trip, {@code count} times. Like {@link #typeText}, events go through
   * {@link KeyboardFocusManager#redispatchEvent} so they land on this exact component — driving its
   * {@code KeyListener}s and {@code InputMap} bindings — whether or not it currently holds focus,
   * and without needing a shown window.
   *
   * <p>Unlike {@link #typeText} this does <b>not</b> require (or descend to) a text component: it
   * is the tool for {@code Escape}/{@code Enter}/{@code Tab}/arrow navigation, function keys, and
   * chords like {@code Ctrl+Space}. A {@link KeyEvent#CHAR_UNDEFINED} {@code keyChar} suppresses
   * the KEY_TYPED event (used for non-typing keys and for modified chords, where only the
   * KEY_PRESSED drives the binding).
   *
   * <p>Modal-safe: the events are posted with {@link SwingUtilities#invokeLater} (pressing {@code
   * Enter} on a default button, or an accelerator, can open a modal dialog), so this returns
   * promptly and reports whether the EDT settled within the {@linkplain #DISPATCH_GRACE grace
   * period} rather than blocking on a nested event loop.
   *
   * @param component the target (must be non-null)
   * @param keyCode the {@link KeyEvent} {@code VK_*} code for KEY_PRESSED/KEY_RELEASED
   * @param keyChar the character for KEY_TYPED, or {@link KeyEvent#CHAR_UNDEFINED} to send none
   * @param modifiers the {@link InputEvent} {@code *_DOWN_MASK} modifier bits (0 for none)
   * @param count how many times to send the key ({@code < 1} is treated as 1)
   * @return whether the EDT settled within the grace period, plus the window/popup delta the key
   *     press caused (a submitted dialog closing, an accelerator opening one)
   */
  public static Settle pressKey(
      Component component, int keyCode, char keyChar, int modifiers, int count) {
    Objects.requireNonNull(component, "component");
    Map<String, WindowRef> before =
        runOnEdt(
            () -> {
              Map<String, WindowRef> windows = showingWindowsOnEdt();
              postKeys(component, keyCode, keyChar, modifiers, count);
              return windows;
            },
            DEFAULT_TIMEOUT);
    return settleAndDiff(before, DISPATCH_GRACE);
  }

  /**
   * Resolves {@code id} against the live windows and delivers the key to it, with the resolve, the
   * before-snapshot, and the fire-and-forget dispatch folded into one EDT task (closing the
   * resolve→act TOCTOU), then reports whether the EDT settled within the {@linkplain
   * #DISPATCH_GRACE grace period} and the window/popup delta. The modal-safe by-id counterpart to
   * {@link #pressKey}.
   *
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws ComponentAddress.AmbiguousComponentException if a name id is no longer unique
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Settle pressKeyById(
      String id, int keyCode, char keyChar, int modifiers, int count, Duration timeout) {
    Map<String, WindowRef> before =
        ComponentAddress.withComponent(
            id,
            timeout,
            component -> {
              Map<String, WindowRef> windows = showingWindowsOnEdt();
              postKeys(component, keyCode, keyChar, modifiers, count);
              return windows;
            });
    return settleAndDiff(before, DISPATCH_GRACE);
  }

  /** Posts the KEY_PRESSED / (optional) KEY_TYPED / KEY_RELEASED trip, {@code count} times. */
  private static void postKeys(
      Component component, int keyCode, char keyChar, int modifiers, int count) {
    int times = Math.max(1, count);
    SwingUtilities.invokeLater(
        () -> {
          KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
          for (int i = 0; i < times; i++) {
            long when = System.currentTimeMillis();
            focusManager.redispatchEvent(
                component,
                new KeyEvent(
                    component,
                    KeyEvent.KEY_PRESSED,
                    when,
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED));
            if (keyChar != KeyEvent.CHAR_UNDEFINED) {
              focusManager.redispatchEvent(
                  component,
                  new KeyEvent(
                      component,
                      KeyEvent.KEY_TYPED,
                      when,
                      modifiers,
                      KeyEvent.VK_UNDEFINED,
                      keyChar));
            }
            focusManager.redispatchEvent(
                component,
                new KeyEvent(
                    component,
                    KeyEvent.KEY_RELEASED,
                    when,
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED));
          }
        });
  }

  /**
   * The current permanent focus owner read on the EDT, or {@code null} when there is none (e.g. the
   * driven application is not the OS-focused app) or the EDT could not be reached. Lets a caller
   * default a key press to "whatever has focus" when no explicit target is given.
   */
  public static Component currentFocusOwner() {
    try {
      return EdtOps.invokeAndWait(
          () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(),
          DEFAULT_TIMEOUT);
    } catch (Exception e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------
  // focus / scroll / window management
  // ---------------------------------------------------------------------------------------

  /**
   * Resolves {@code id} and requests keyboard focus for it in its window (on the EDT). Returns the
   * {@link Component#requestFocusInWindow()} verdict — {@code true} when the request is likely to
   * succeed (focus transfer is asynchronous, so the component may not be the focus owner yet).
   *
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static boolean requestFocusById(String id, Duration timeout) {
    return ComponentAddress.withComponent(id, timeout, Component::requestFocusInWindow);
  }

  /**
   * Resolves {@code id} and scrolls its enclosing scroll pane so the component is visible (on the
   * EDT), via {@link JComponent#scrollRectToVisible} — useful before screenshotting an off-screen
   * component.
   *
   * @throws IllegalArgumentException if the component is not a {@link JComponent} (only those can
   *     ask an enclosing viewport to scroll)
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static void scrollToVisibleById(String id, Duration timeout) {
    ComponentAddress.withComponent(
        id,
        timeout,
        component -> {
          if (!(component instanceof JComponent jComponent)) {
            throw new IllegalArgumentException(
                "scroll_to requires a JComponent; "
                    + className(component)
                    + " cannot ask an enclosing viewport to scroll");
          }
          jComponent.scrollRectToVisible(
              new Rectangle(0, 0, jComponent.getWidth(), jComponent.getHeight()));
          return null;
        });
  }

  /**
   * Resolves {@code id}, finds its enclosing {@link Window}, and brings it to the front (on the
   * EDT). Synchronous.
   *
   * @throws IllegalArgumentException if the component has no enclosing window
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static void toFrontById(String id, Duration timeout) {
    ComponentAddress.withComponent(
        id,
        timeout,
        component -> {
          enclosingWindowOrThrow(component, id).toFront();
          return null;
        });
  }

  /**
   * Resolves {@code id}, finds its enclosing {@link Window}, and posts a {@link
   * WindowEvent#WINDOW_CLOSING} to it — the same event the OS window-close button delivers, so the
   * app's own close handling (confirm dialogs, {@code WindowListener}s, default close operation)
   * runs. <b>Modal-safe</b>: the event is dispatched fire-and-forget (a close can open a modal
   * "save changes?" dialog), and this reports whether the EDT settled within the {@linkplain
   * #DISPATCH_GRACE grace period}.
   *
   * @throws IllegalArgumentException if the component has no enclosing window
   * @throws ComponentAddress.ComponentNotFoundException if nothing matches {@code id}
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static boolean closeWindowById(String id, Duration timeout) {
    ComponentAddress.withComponent(
        id,
        timeout,
        component -> {
          Window window = enclosingWindowOrThrow(component, id);
          SwingUtilities.invokeLater(
              () -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
          return null;
        });
    return tryFlush(DISPATCH_GRACE);
  }

  private static Window enclosingWindowOrThrow(Component component, String id) {
    Window window = ComponentAddress.enclosingWindow(component);
    if (window == null) {
      throw new IllegalArgumentException("'" + id + "' has no enclosing window");
    }
    return window;
  }

  // ---------------------------------------------------------------------------------------
  // select
  // ---------------------------------------------------------------------------------------

  /**
   * Selects by zero-based index on a {@link JComboBox}, {@link JList}, {@link JTabbedPane}, or
   * {@link JTable} (row). Runs to completion on the EDT.
   *
   * @throws IllegalArgumentException if the component kind has no index selection, or the index is
   *     out of range
   */
  public static SelectionResult selectIndex(Component component, int index) {
    return runOnEdt(() -> selectIndexOnEdt(component, index), DEFAULT_TIMEOUT);
  }

  /** By-id counterpart to {@link #selectIndex}: resolve and select in one EDT task. */
  public static SelectionResult selectIndexById(String id, int index, Duration timeout) {
    return ComponentAddress.withComponent(id, timeout, comp -> selectIndexOnEdt(comp, index));
  }

  private static SelectionResult selectIndexOnEdt(Component component, int index) {
    if (component instanceof JComboBox<?> combo) {
      requireInRange(index, combo.getItemCount(), "combo item");
      combo.setSelectedIndex(index);
      return synced("combo item", index, String.valueOf(combo.getSelectedItem()));
    }
    if (component instanceof JList<?> list) {
      requireInRange(index, list.getModel().getSize(), "list item");
      list.setSelectedIndex(index);
      list.ensureIndexIsVisible(index);
      return synced("list item", index, String.valueOf(list.getSelectedValue()));
    }
    if (component instanceof JTabbedPane tabs) {
      requireInRange(index, tabs.getTabCount(), "tab");
      tabs.setSelectedIndex(index);
      return synced("tab", index, tabs.getTitleAt(index));
    }
    if (component instanceof JTable table) {
      requireInRange(index, table.getRowCount(), "table row");
      table.setRowSelectionInterval(index, index);
      table.scrollRectToVisible(table.getCellRect(index, 0, true));
      return synced("table row", index, null);
    }
    throw new IllegalArgumentException(
        "select by index is not supported for "
            + className(component)
            + " (expected a combo box, list, tabbed pane, or table)");
  }

  /**
   * Selects by matching an item's text on a {@link JComboBox}, {@link JList}, {@link JTabbedPane}
   * (tab title), {@link JTable} (any cell), or {@link JSpinner} (sets the value). Matching is exact
   * first, then case-insensitive. Runs to completion on the EDT.
   *
   * @throws IllegalArgumentException if the kind is unsupported or no item matches
   */
  public static SelectionResult selectValue(Component component, String value) {
    Objects.requireNonNull(value, "value");
    return runOnEdt(() -> selectValueOnEdt(component, value), DEFAULT_TIMEOUT);
  }

  /** By-id counterpart to {@link #selectValue}: resolve and select in one EDT task. */
  public static SelectionResult selectValueById(String id, String value, Duration timeout) {
    Objects.requireNonNull(value, "value");
    return ComponentAddress.withComponent(id, timeout, comp -> selectValueOnEdt(comp, value));
  }

  private static SelectionResult selectValueOnEdt(Component component, String value) {
    if (component instanceof JComboBox<?> combo) {
      int i = matchIndex(comboItems(combo), value);
      requireFound(i, "combo box", value, comboItems(combo));
      combo.setSelectedIndex(i);
      return synced("combo item", i, value);
    }
    if (component instanceof JList<?> list) {
      int i = matchIndex(listItems(list), value);
      requireFound(i, "list", value, listItems(list));
      list.setSelectedIndex(i);
      list.ensureIndexIsVisible(i);
      return synced("list item", i, value);
    }
    if (component instanceof JTabbedPane tabs) {
      int i = matchIndex(tabTitles(tabs), value);
      requireFound(i, "tabbed pane", value, tabTitles(tabs));
      tabs.setSelectedIndex(i);
      return synced("tab", i, value);
    }
    if (component instanceof JTable table) {
      int row = matchTableRow(table, value);
      if (row < 0) {
        throw new IllegalArgumentException("no cell in the table matches '" + value + "'");
      }
      table.setRowSelectionInterval(row, row);
      table.scrollRectToVisible(table.getCellRect(row, 0, true));
      return synced("table row", row, value);
    }
    if (component instanceof JSpinner spinner) {
      setSpinnerValue(spinner, value);
      return new SelectionResult(
          false, true, "set spinner to '" + String.valueOf(spinner.getValue()) + "'");
    }
    throw new IllegalArgumentException(
        "select by value is not supported for "
            + className(component)
            + " (expected a combo box, list, tabbed pane, table, or spinner)");
  }

  /**
   * Selects/activates by path. On a {@link JTree} it selects the node reached by matching each path
   * segment against a node's text (synchronous). On a {@link JMenuBar}, {@link JMenu}, or {@link
   * javax.swing.JPopupMenu} it walks the menu structure by item text and <b>activates the terminal
   * item</b> — which may open a modal dialog, so that final step follows the modal-safe dispatch
   * rule (see {@link #click}).
   *
   * @throws IllegalArgumentException if the kind is unsupported or a path segment matches nothing
   */
  public static SelectionResult selectPath(Component component, List<String> path) {
    requireNonEmptyPath(path);
    return finishSelectPath(runOnEdt(() -> selectPathOnEdt(component, path), DEFAULT_TIMEOUT));
  }

  /**
   * By-id counterpart to {@link #selectPath}: resolve then select/activate, folding the resolve.
   */
  public static SelectionResult selectPathById(String id, List<String> path, Duration timeout) {
    requireNonEmptyPath(path);
    return finishSelectPath(
        ComponentAddress.withComponent(id, timeout, comp -> selectPathOnEdt(comp, path)));
  }

  private static void requireNonEmptyPath(List<String> path) {
    Objects.requireNonNull(path, "path");
    if (path.isEmpty()) {
      throw new IllegalArgumentException("path must have at least one segment");
    }
  }

  /**
   * Outcome of {@link #selectPathOnEdt}: either a menu-item {@code doClick} was posted modal-safely
   * (so the caller must measure settling off the EDT, diffing against {@code windowsBefore}), or a
   * synchronous {@link SelectionResult} (tree) already completed.
   */
  private record PathOutcome(
      boolean menuPosted,
      String description,
      SelectionResult syncResult,
      Map<String, WindowRef> windowsBefore) {}

  /**
   * On the EDT: for a menu root, snapshot the showing window/popup set (the delta baseline),
   * resolve the terminal item, and post its {@code doClick} fire-and-forget (modal-safe); for a
   * {@link JTree}, select the path synchronously.
   */
  private static PathOutcome selectPathOnEdt(Component component, List<String> path) {
    if (component instanceof JMenuBar
        || component instanceof JMenu
        || component instanceof javax.swing.JPopupMenu) {
      Map<String, WindowRef> before = showingWindowsOnEdt();
      JMenuItem leaf = resolveMenuLeaf(component, path);
      SwingUtilities.invokeLater(leaf::doClick);
      return new PathOutcome(true, "activated menu path " + path, null, before);
    }
    if (component instanceof JTree tree) {
      TreePath treePath = resolveTreePath(tree, path);
      tree.setSelectionPath(treePath);
      tree.scrollPathToVisible(treePath);
      return new PathOutcome(
          false, null, new SelectionResult(false, true, "selected tree path " + path), null);
    }
    throw new IllegalArgumentException(
        "select by path is supported for trees and menus; got " + className(component));
  }

  /**
   * Applies the grace-period settle-and-diff read for a posted menu activation; passes trees
   * through.
   */
  private static SelectionResult finishSelectPath(PathOutcome outcome) {
    if (outcome.menuPosted()) {
      Settle settle = settleAndDiff(outcome.windowsBefore(), DISPATCH_GRACE);
      return new SelectionResult(true, settle.edtSettled(), outcome.description(), settle.delta());
    }
    return outcome.syncResult();
  }

  /**
   * The centre of a table cell in the table's own coordinate space, for a {@link ClickPoint#cell}
   * click. View coordinates, matching what the caller sees in {@code get_items}. Call on the EDT.
   */
  private static Point tableCellCentre(Component component, int row, int column) {
    JTable table = requireTable(component, "row/column clicks");
    requireCellInRange(table, row, column);
    Rectangle cell = table.getCellRect(row, column, false);
    return new Point(cell.x + cell.width / 2, cell.y + cell.height / 2);
  }

  private static JTable requireTable(Component component, String what) {
    if (component instanceof JTable table) {
      return table;
    }
    throw new IllegalArgumentException(
        what + " need a JTable, but " + describe(component) + " is not one");
  }

  private static void requireCellInRange(JTable table, int row, int column) {
    if (row < 0 || row >= table.getRowCount() || column < 0 || column >= table.getColumnCount()) {
      throw new IllegalArgumentException(
          "cell ("
              + row
              + ","
              + column
              + ") is out of range; the table has "
              + table.getRowCount()
              + " rows and "
              + table.getColumnCount()
              + " columns");
    }
  }

  /**
   * Selects a single {@link JTable} cell (view coordinates), scrolling it visible. The row-only
   * {@link #selectIndex} cannot express "this cell"; {@code changeSelection} is used rather than
   * the row/column interval setters so the table's own selection model decides what a cell
   * selection means.
   */
  public static SelectionResult selectCell(Component component, int row, int column) {
    Objects.requireNonNull(component, "component");
    return runOnEdt(() -> selectCellOnEdt(component, row, column), DEFAULT_TIMEOUT);
  }

  /** By-id counterpart to {@link #selectCell}, folding the resolve into the same EDT task. */
  public static SelectionResult selectCellById(String id, int row, int column, Duration timeout) {
    return ComponentAddress.withComponent(
        id, timeout, component -> selectCellOnEdt(component, row, column));
  }

  private static SelectionResult selectCellOnEdt(Component component, int row, int column) {
    JTable table = requireTable(component, "row/column selections");
    requireCellInRange(table, row, column);
    table.changeSelection(row, column, false, false);
    table.scrollRectToVisible(table.getCellRect(row, column, true));
    return new SelectionResult(
        false,
        true,
        "selected table cell ("
            + row
            + ","
            + column
            + ") ('"
            + truncateValue(RenderedText.tableCell(table, row, column))
            + "')");
  }

  private static String truncateValue(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > VALUE_CAP ? text.substring(0, VALUE_CAP) + "…" : text;
  }

  private static JMenuItem resolveMenuLeaf(Component root, List<String> path) {
    Container current = (Container) root;
    JMenuItem leaf = null;
    for (int i = 0; i < path.size(); i++) {
      String segment = path.get(i);
      JMenuItem match = findMenuChild(current, segment);
      if (match == null) {
        throw new IllegalArgumentException(
            "no menu entry '"
                + segment
                + "' under "
                + describe(current)
                + " for path "
                + path
                + "; children: "
                + capList(menuChildLabels(current)));
      }
      leaf = match;
      if (i < path.size() - 1) {
        if (!(match instanceof JMenu submenu)) {
          throw new IllegalArgumentException(
              "menu entry '"
                  + segment
                  + "' is not a submenu but path "
                  + path
                  + " continues past it");
        }
        current = submenu;
      }
    }
    return leaf;
  }

  private static JMenuItem findMenuChild(Container menuContainer, String text) {
    for (Component child : menuChildren(menuContainer)) {
      if (child instanceof JMenuItem item && valueMatches(item.getText(), text)) {
        return item;
      }
    }
    return null;
  }

  private static Component[] menuChildren(Container menuContainer) {
    return menuContainer instanceof JMenu menu
        ? menu.getMenuComponents()
        : menuContainer.getComponents();
  }

  /**
   * The labels a caller could have used for the next path segment, for a no-match error. Guessing
   * blind is the dominant failure mode on menu and tree paths (labels are renderer-produced and
   * rarely match the model values a caller expects), so the error carries the answer. Separators
   * and other non-item children are skipped.
   */
  private static List<String> menuChildLabels(Container menuContainer) {
    List<String> labels = new ArrayList<>();
    for (Component child : menuChildren(menuContainer)) {
      if (child instanceof JMenuItem item) {
        labels.add(String.valueOf(item.getText()));
      }
    }
    return labels;
  }

  // ---------------------------------------------------------------------------------------
  // tree helpers
  // ---------------------------------------------------------------------------------------

  private static TreePath resolveTreePath(JTree tree, List<String> path) {
    TreeModel model = tree.getModel();
    Object root = model.getRoot();
    if (root == null) {
      throw new IllegalArgumentException("tree has no root");
    }
    List<Object> nodes = new ArrayList<>();
    nodes.add(root);
    int start = 0;
    // When the root is shown, the first path segment may name it; consume it if so.
    if (tree.isRootVisible() && valueMatches(RenderedText.treeNode(tree, root), path.get(0))) {
      start = 1;
    }
    Object current = root;
    for (int i = start; i < path.size(); i++) {
      Object child = findTreeChild(tree, current, path.get(i));
      if (child == null) {
        throw new IllegalArgumentException(
            "no tree node '"
                + path.get(i)
                + "' under '"
                + RenderedText.treeNode(tree, current)
                + "' for path "
                + path
                + "; children: "
                + capList(treeChildLabels(tree, current)));
      }
      nodes.add(child);
      current = child;
    }
    return new TreePath(nodes.toArray());
  }

  /**
   * The rendered labels of {@code parent}'s children — what {@link #findTreeChild} actually matched
   * against, so a no-match error shows the caller the real (renderer-produced) strings rather than
   * the model values they guessed from.
   */
  private static List<String> treeChildLabels(JTree tree, Object parent) {
    TreeModel model = tree.getModel();
    int count = model.getChildCount(parent);
    List<String> labels = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      labels.add(RenderedText.treeNode(tree, model.getChild(parent, i)));
    }
    return labels;
  }

  private static Object findTreeChild(JTree tree, Object parent, String text) {
    TreeModel model = tree.getModel();
    int count = model.getChildCount(parent);
    for (int i = 0; i < count; i++) {
      Object child = model.getChild(parent, i);
      if (valueMatches(
          RenderedText.treeNode(tree, child), text)) { // renderer-aware (convertValueToText)
        return child;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------
  // spinner / value helpers
  // ---------------------------------------------------------------------------------------

  private static void setSpinnerValue(JSpinner spinner, String value) {
    if (spinner.getModel() instanceof SpinnerListModel listModel) {
      for (Object item : listModel.getList()) {
        if (valueMatches(String.valueOf(item), value)) {
          spinner.setValue(item);
          return;
        }
      }
      throw new IllegalArgumentException("spinner has no value '" + value + "'");
    }
    if (spinner.getModel() instanceof SpinnerNumberModel) {
      try {
        spinner.setValue(Double.valueOf(value.trim()));
        return;
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "spinner expects a number; '" + value + "' is not numeric");
      }
    }
    spinner.setValue(value);
  }

  private static List<String> comboItems(JComboBox<?> combo) {
    List<String> out = new ArrayList<>(combo.getItemCount());
    for (int i = 0; i < combo.getItemCount(); i++) {
      out.add(RenderedText.comboItem(combo, i)); // renderer-aware: match the visible text
    }
    return out;
  }

  private static List<String> listItems(JList<?> list) {
    int size = list.getModel().getSize();
    List<String> out = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      out.add(RenderedText.listItem(list, i)); // renderer-aware: match the visible text
    }
    return out;
  }

  private static List<String> tabTitles(JTabbedPane tabs) {
    List<String> out = new ArrayList<>(tabs.getTabCount());
    for (int i = 0; i < tabs.getTabCount(); i++) {
      out.add(tabs.getTitleAt(i));
    }
    return out;
  }

  private static int matchIndex(List<String> items, String value) {
    for (int i = 0; i < items.size(); i++) {
      if (value.equals(items.get(i))) {
        return i;
      }
    }
    for (int i = 0; i < items.size(); i++) {
      if (valueMatches(items.get(i), value)) {
        return i;
      }
    }
    return -1;
  }

  private static int matchTableRow(JTable table, String value) {
    for (int row = 0; row < table.getRowCount(); row++) {
      for (int col = 0; col < table.getColumnCount(); col++) {
        if (valueMatches(RenderedText.tableCell(table, row, col), value)) {
          return row;
        }
      }
    }
    return -1;
  }

  private static boolean valueMatches(String actual, String wanted) {
    if (actual == null) {
      return false;
    }
    return actual.equals(wanted) || actual.trim().equalsIgnoreCase(wanted.trim());
  }

  private static void requireInRange(int index, int size, String kind) {
    if (index < 0 || index >= size) {
      throw new IllegalArgumentException(
          kind + " index " + index + " is out of range (0.." + (size - 1) + ")");
    }
  }

  private static void requireFound(int index, String kind, String value, List<String> available) {
    if (index < 0) {
      throw new IllegalArgumentException(
          "no " + kind + " item matches '" + value + "'; available: " + capList(available));
    }
  }

  private static String capList(List<String> items) {
    int show = Math.min(items.size(), 12);
    String joined = String.join(", ", items.subList(0, show));
    return show < items.size() ? "[" + joined + ", … (" + items.size() + ")]" : "[" + joined + "]";
  }

  private static SelectionResult synced(String kind, int index, String text) {
    StringBuilder sb = new StringBuilder("selected ").append(kind).append(' ').append(index);
    if (text != null && !"null".equals(text)) {
      String value = truncateValue(text);
      sb.append(" ('").append(value).append("')");
    }
    return new SelectionResult(false, true, sb.toString());
  }

  // ---------------------------------------------------------------------------------------
  // shared EDT plumbing
  // ---------------------------------------------------------------------------------------

  /** Runs a no-op through the EDT with a bounded timeout; {@code true} if it drained in time. */
  static boolean tryFlush(Duration grace) {
    try {
      EdtOps.runAndWait(() -> {}, grace);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static <T> T runOnEdt(Callable<T> task, Duration timeout) {
    return EdtOps.call(task, timeout);
  }

  private static String className(Component component) {
    return component == null ? "null" : component.getClass().getName();
  }

  private static String describe(Component component) {
    String name = component.getName();
    return ComponentAddress.simpleClassName(component.getClass())
        + (name != null ? "(" + name + ")" : "");
  }

  private static String describeAll(List<? extends Component> components) {
    List<String> parts = new ArrayList<>(components.size());
    for (Component component : components) {
      parts.add(describe(component));
    }
    return String.join(", ", parts);
  }
}
