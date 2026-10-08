package io.github.paul_griffith.swingmcp.core;

/**
 * An immutable snapshot of a single top-level {@link java.awt.Window}, as produced by {@link
 * Introspection#listWindows}.
 *
 * <p>Like {@link ComponentTreeSnapshot} this is plain data (no live component references) so it can
 * cross from the EDT to a transport thread for encoding.
 *
 * @param id stable id: the window's {@link java.awt.Component#getName()} when set and unique,
 *     otherwise {@code w{handle}} (see {@link ComponentAddress})
 * @param ordinal the window's stable process-lifetime handle — the {@code n} in a {@code w{n}} id
 *     (see {@link ComponentAddress#windowHandle}); assigned once and never reused or renumbered, so
 *     unlike an index into {@link java.awt.Window#getWindows()} it does not rot as windows
 *     open/close
 * @param kind whether the window is a frame, dialog, or plain window
 * @param title the frame/dialog title, or {@code null} for a plain {@link java.awt.Window}
 * @param name the raw {@link java.awt.Component#getName()} value, or {@code null}
 * @param idKind how {@link #id()} was derived — its stability tier (see {@link
 *     ComponentAddress.IdKind}); top-level windows commonly carry an {@link
 *     ComponentAddress.IdKind#AUTO auto} name like {@code frame2}
 * @param bounds the window's on-screen bounds in logical pixels
 * @param visible {@link java.awt.Component#isVisible()}
 * @param showing {@link java.awt.Component#isShowing()}
 * @param focused {@link java.awt.Window#isFocused()}
 * @param active {@link java.awt.Window#isActive()}
 * @param modality the dialog's modality type, or {@code null} for non-dialogs
 * @param modal convenience flag: {@code true} when {@link #modality()} is a modal type
 * @param iconified frames only: {@code true} when minimized; {@code null} for non-frames
 * @param maximized frames only: {@code true} when maximized in both directions; {@code null} for
 *     non-frames
 */
public record WindowSnapshot(
    String id,
    int ordinal,
    Kind kind,
    String title,
    String name,
    ComponentAddress.IdKind idKind,
    Bounds bounds,
    boolean visible,
    boolean showing,
    boolean focused,
    boolean active,
    Modality modality,
    boolean modal,
    Boolean iconified,
    Boolean maximized) {

  /** The coarse kind of a window. */
  public enum Kind {
    /** A {@link java.awt.Frame} / {@link javax.swing.JFrame}. */
    FRAME,
    /** A {@link java.awt.Dialog} / {@link javax.swing.JDialog}. */
    DIALOG,
    /** Any other {@link java.awt.Window} (e.g. a tooltip or popup window). */
    WINDOW
  }

  /**
   * Mirror of {@link java.awt.Dialog.ModalityType} without the AWT dependency leaking into data.
   */
  public enum Modality {
    MODELESS,
    DOCUMENT_MODAL,
    APPLICATION_MODAL,
    TOOLKIT_MODAL
  }
}
