package io.github.paul_griffith.swingmcp.core;

import java.util.List;

/**
 * An immutable, serialization-friendly snapshot of a Swing component subtree.
 *
 * <p>Snapshots are produced on the Event Dispatch Thread (see {@link EdtOps}) and then handed to
 * transport threads for JSON encoding, so this type intentionally holds only plain data — never
 * live {@link java.awt.Component} references. Build one with {@link Introspection#componentTree}.
 *
 * <p><b>Addressing.</b> {@link #id()} is the stable handle an interaction tool passes back to
 * {@link ComponentAddress#resolve(String)} to recover the live component. See {@link
 * ComponentAddress} for the exact id grammar and its stability contract.
 *
 * @param id stable component id (see {@link ComponentAddress})
 * @param name the raw {@link java.awt.Component#getName()} value, or {@code null}
 * @param idKind how {@link #id()} was derived — its stability tier (see {@link
 *     ComponentAddress.IdKind})
 * @param simpleClassName the component's simple class name (e.g. {@code JButton})
 * @param className the component's fully-qualified class name
 * @param text best-effort user-visible text, or {@code null}; see {@link #textSource()}
 * @param textSource which property {@link #text()} came from ({@link TextSource#NONE} when null)
 * @param bounds logical bounds within the parent (never {@code null})
 * @param screenBounds logical on-screen bounds, or {@code null} when the component is not showing
 * @param visible {@link java.awt.Component#isVisible()}
 * @param showing {@link java.awt.Component#isShowing()}
 * @param enabled {@link java.awt.Component#isEnabled()}
 * @param focusable {@link java.awt.Component#isFocusable()}
 * @param focused {@link java.awt.Component#isFocusOwner()}
 * @param selection selection state when the component kind has one, else {@code null}
 * @param childCount the true number of child components, even when {@link #children()} was
 *     truncated by a {@code maxDepth} limit
 * @param children child snapshots; empty when {@code childCount == 0} or a depth limit was hit
 */
public record ComponentTreeSnapshot(
    String id,
    String name,
    ComponentAddress.IdKind idKind,
    String simpleClassName,
    String className,
    String text,
    TextSource textSource,
    Bounds bounds,
    Bounds screenBounds,
    boolean visible,
    boolean showing,
    boolean enabled,
    boolean focusable,
    boolean focused,
    Selection selection,
    int childCount,
    List<ComponentTreeSnapshot> children) {

  /**
   * Which component property supplied {@link ComponentTreeSnapshot#text()}. The introspection
   * engine tries these in declaration order and records the first that yields non-blank text.
   */
  public enum TextSource {
    /** No display text was found. */
    NONE,
    /**
     * An extension's {@link io.github.paul_griffith.swingmcp.api.ComponentDescriber}, which is
     * consulted before the built-in sources below.
     */
    EXTENSION,
    /** {@link javax.swing.AbstractButton#getText()} (buttons, toggles, menu items, menus). */
    BUTTON_TEXT,
    /** {@link javax.swing.JLabel#getText()}. */
    LABEL_TEXT,
    /** {@link javax.swing.text.JTextComponent#getText()}, truncated to a cap. */
    TEXT_COMPONENT,
    /** The title of a {@link javax.swing.border.TitledBorder}. */
    TITLED_BORDER,
    /** {@link javax.swing.JComponent#getToolTipText()}. */
    TOOLTIP,
    /** The component's accessible name. */
    ACCESSIBLE_NAME
  }

  /** The kind of selection a {@link Selection} describes. */
  public enum SelectionKind {
    /** A toggle button, checkbox, or radio button (also their menu-item variants). */
    TOGGLE,
    /** A {@link javax.swing.JComboBox}. */
    COMBO_BOX,
    /** A {@link javax.swing.JList}. */
    LIST,
    /** A {@link javax.swing.JTabbedPane}. */
    TABBED_PANE,
    /** A {@link javax.swing.JTable} (row selection). */
    TABLE
  }

  /**
   * Selection state for the component kinds that have one. Which fields carry meaning depends on
   * {@link #kind()}:
   *
   * <ul>
   *   <li>{@link SelectionKind#TOGGLE}: {@link #selected()}.
   *   <li>{@link SelectionKind#COMBO_BOX}, {@link SelectionKind#TABBED_PANE}: {@link
   *       #selectedIndex()} ({@code -1} when nothing is selected) and {@link #selectedText()}.
   *   <li>{@link SelectionKind#LIST}, {@link SelectionKind#TABLE}: {@link #selectedIndices()}
   *       (rows, in ascending order; empty when nothing is selected), {@link #selectedIndex()} (the
   *       lead/first index or {@code -1}), and {@link #selectedText()} for single lists.
   * </ul>
   *
   * @param kind the selection kind
   * @param selected toggle state ({@link SelectionKind#TOGGLE} only)
   * @param selectedIndex single/lead selected index, or {@code -1}
   * @param selectedIndices selected indices for multi-selection widgets (never {@code null})
   * @param selectedText best-effort human label of the current selection, or {@code null}
   */
  public record Selection(
      SelectionKind kind,
      boolean selected,
      int selectedIndex,
      List<Integer> selectedIndices,
      String selectedText) {}
}
