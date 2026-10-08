package io.github.paul_griffith.swingmcp.core;

import java.awt.Component;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * Renderer-aware text extraction for data components. Real Swing apps rarely store display strings
 * in their models: a {@link JTable} cell, {@link JList}/{@link JComboBox} element, or {@link JTree}
 * node is turned into visible text by a cell renderer or {@link JTree#convertValueToText}. Matching
 * or reading via {@code String.valueOf(modelValue)} therefore sees the wrong text (often a bare
 * {@code toString()} of a domain object).
 *
 * <p>These helpers ask the component how it would render a value — pulling the text from the
 * renderer component when it is a {@link JLabel} (the overwhelmingly common case: {@code
 * DefaultTableCellRenderer}, {@code DefaultListCellRenderer}, and the combo/tree defaults are all
 * {@code JLabel}s) — and fall back to {@code String.valueOf(value)} when the renderer is exotic or
 * throws. All must be called on the Event Dispatch Thread.
 */
final class RenderedText {

  private RenderedText() {}

  /** The visible text of table cell {@code (row, col)} in view coordinates. */
  static String tableCell(JTable table, int row, int col) {
    Object value = table.getValueAt(row, col);
    try {
      TableCellRenderer renderer = table.getCellRenderer(row, col);
      Component c = renderer.getTableCellRendererComponent(table, value, false, false, row, col);
      String label = labelText(c);
      if (label != null) {
        return label;
      }
    } catch (RuntimeException ignored) {
      // exotic/throwing renderer — fall back to the model value's string
    }
    return String.valueOf(value);
  }

  /** The visible text of list element {@code index}. */
  static String listItem(JList<?> list, int index) {
    Object value = list.getModel().getElementAt(index);
    try {
      ListCellRenderer<Object> renderer = asObjectRenderer(list.getCellRenderer());
      Component c = renderer.getListCellRendererComponent(list, value, index, false, false);
      String label = labelText(c);
      if (label != null) {
        return label;
      }
    } catch (RuntimeException ignored) {
      // fall back below
    }
    return String.valueOf(value);
  }

  /** The visible text of combo-box item {@code index}. */
  static String comboItem(JComboBox<?> combo, int index) {
    Object value = combo.getItemAt(index);
    try {
      ListCellRenderer<Object> renderer = asObjectRenderer(combo.getRenderer());
      // A combo renderer is a ListCellRenderer; give it a throwaway JList as the list argument.
      Component c =
          renderer.getListCellRendererComponent(new JList<>(), value, index, false, false);
      String label = labelText(c);
      if (label != null) {
        return label;
      }
    } catch (RuntimeException ignored) {
      // fall back below
    }
    return String.valueOf(value);
  }

  /** The visible text {@code tree} would display for {@code node}. */
  static String treeNode(JTree tree, Object node) {
    try {
      boolean leaf = tree.getModel().isLeaf(node);
      String text = tree.convertValueToText(node, false, false, leaf, -1, false);
      if (text != null) {
        return text;
      }
    } catch (RuntimeException ignored) {
      // fall back below
    }
    return String.valueOf(node);
  }

  private static String labelText(Component rendererComponent) {
    return (rendererComponent instanceof JLabel label) ? label.getText() : null;
  }

  @SuppressWarnings("unchecked")
  private static ListCellRenderer<Object> asObjectRenderer(ListCellRenderer<?> renderer) {
    return (ListCellRenderer<Object>) renderer;
  }
}
