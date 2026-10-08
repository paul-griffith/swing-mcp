package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for the {@link Interactions} driving primitives. Components are built
 * but never shown, so no display is needed: {@code doClick()}, {@code setText}, dispatched
 * mouse/key events, and selection all operate on the object model on the EDT.
 */
class InteractionsTest {

  // ------------------------------------------------------------------ click

  @Test
  void leftClickOnButtonFiresActionListener() throws Exception {
    CountDownLatch fired = new CountDownLatch(1);
    JButton button = new JButton("Go");
    button.addActionListener(e -> fired.countDown());

    Interactions.DispatchResult result =
        Interactions.click(button, Interactions.MouseButton.LEFT, 1);

    assertTrue(fired.await(2, TimeUnit.SECONDS), "the button's ActionListener should have fired");
    assertTrue(result.edtSettled(), "an idle EDT should settle within the grace period");
  }

  @Test
  void synthesizedDoubleClickDeliversMouseEventsWithClickCount() throws Exception {
    AtomicInteger clicks = new AtomicInteger();
    AtomicInteger lastClickCount = new AtomicInteger();
    JLabel target = new JLabel("clickable");
    target.setSize(40, 20);
    target.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            clicks.incrementAndGet();
            lastClickCount.set(e.getClickCount());
          }
        });

    Interactions.click(target, Interactions.MouseButton.LEFT, 2);

    // The action runs async on the EDT; flush to be sure it has been processed.
    EdtDiagnostics.flush(java.time.Duration.ofSeconds(2));
    assertEquals(2, clicks.get(), "a double-click synthesizes two MOUSE_CLICKED events");
    assertEquals(2, lastClickCount.get(), "the second click reports clickCount=2");
  }

  // ------------------------------------------------------------------ set_text / type_text

  @Test
  void setTextReplacesTextComponentContent() {
    JTextField field = new JTextField("old");
    Interactions.setText(field, "new value");
    assertEquals("new value", field.getText());
  }

  @Test
  void setTextRejectsNonTextComponents() {
    assertThrows(IllegalArgumentException.class, () -> Interactions.setText(new JButton(), "x"));
  }

  // ------------------------------------------------------------------ text target: auto-descend /
  // reject

  @Test
  void setTextAutoDescendsToUniqueTextFieldChild() {
    // The named handle is a *container* (the "filter field is really a JPanel" case).
    JTextField inner = new JTextField();
    JPanel composite = new JPanel(new BorderLayout());
    composite.setName("filterField");
    composite.add(new JLabel("Filter:"), BorderLayout.WEST);
    composite.add(inner, BorderLayout.CENTER);

    var resolved = Interactions.setText(composite, "hello");

    assertSame(inner, resolved, "set_text should descend to the inner text field");
    assertEquals("hello", inner.getText());
  }

  @Test
  void typeTextAutoDescendsToUniqueTextFieldChild() {
    JTextField inner = new JTextField();
    JPanel composite = new JPanel();
    composite.add(inner);

    var resolved = Interactions.typeText(composite, "abc");

    assertSame(inner, resolved, "type_text should descend to the inner text field");
    assertEquals("abc", inner.getText());
  }

  @Test
  void typeTextRejectsContainerWithNoTextField() {
    // Finding #1: typing at a bare container used to report success while doing nothing.
    JPanel panel = new JPanel();
    panel.add(new JButton("Go"));
    assertThrows(IllegalArgumentException.class, () -> Interactions.typeText(panel, "x"));
  }

  @Test
  void textTargetRejectsAmbiguousMultipleTextFields() {
    JPanel panel = new JPanel();
    panel.add(new JTextField());
    panel.add(new JTextField());
    assertThrows(IllegalArgumentException.class, () -> Interactions.setText(panel, "x"));
  }

  @Test
  void textTargetPrefersTheEditableFieldWhenOneIsReadOnly() {
    JTextField readOnly = new JTextField("display");
    readOnly.setEditable(false);
    JTextField editable = new JTextField();
    JPanel panel = new JPanel();
    panel.add(readOnly);
    panel.add(editable);

    var resolved = Interactions.setText(panel, "typed");

    assertSame(editable, resolved, "the editable field should win when a read-only one is present");
    assertEquals("typed", editable.getText());
    assertEquals("display", readOnly.getText());
  }

  // ------------------------------------------------------------------ click: coordinates /
  // rollover / listener report

  @Test
  void clickAtExplicitCoordinatesDeliversThatPoint() {
    // Finding #3: a centered click missed an edge hit-region; an explicit point hits it.
    AtomicInteger lastX = new AtomicInteger(-1);
    JPanel panel = new JPanel();
    panel.setSize(100, 30);
    panel.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            lastX.set(e.getX());
          }
        });

    Interactions.click(panel, Interactions.MouseButton.LEFT, 1, 90, 15);

    EdtDiagnostics.flush(Duration.ofSeconds(2));
    assertEquals(90, lastX.get(), "the click should land at the requested x, not the center");
  }

  @Test
  void synthesizedClickArmsRolloverWithMouseEntered() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    JPanel panel = new JPanel();
    panel.setSize(40, 20);
    panel.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(MouseEvent e) {
            entered.countDown();
          }
        });

    Interactions.click(panel, Interactions.MouseButton.LEFT, 1);

    assertTrue(
        entered.await(2, TimeUnit.SECONDS), "a synthesized click should send MOUSE_ENTERED first");
  }

  @Test
  void clickReportsActionabilityAndListenerCount() {
    Interactions.DispatchResult onButton =
        Interactions.click(new JButton("Go"), Interactions.MouseButton.LEFT, 1);
    assertTrue(onButton.actionable(), "a button is actionable");

    Interactions.DispatchResult onInert =
        Interactions.click(new JPanel(), Interactions.MouseButton.LEFT, 1);
    assertFalse(onInert.actionable(), "a plain panel with no mouse listeners is not actionable");
    assertEquals(0, onInert.mouseListeners());
  }

  @Test
  void clickReportsDisabledAndNotShowingTargets() {
    // Finding #2: a click on a disabled button silently no-ops; the probe surfaces it so the tool
    // can warn instead of leaving the caller confused.
    JButton disabled = new JButton("Go");
    disabled.setEnabled(false);
    Interactions.DispatchResult result =
        Interactions.click(disabled, Interactions.MouseButton.LEFT, 1);
    assertFalse(result.enabled(), "a disabled target is reported so the tool can warn");

    // A never-shown component isShowing()==false — a click on it likely lands nowhere useful.
    Interactions.DispatchResult unshown =
        Interactions.click(new JButton("Go"), Interactions.MouseButton.LEFT, 1);
    assertFalse(unshown.showing(), "an unrealized component is reported as not showing");
  }

  @Test
  void typeTextInsertsPerCharAndFiresDocumentListenerEachKeystroke() {
    JTextField field = new JTextField();
    AtomicInteger inserts = new AtomicInteger();
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                inserts.incrementAndGet();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {}

              @Override
              public void changedUpdate(DocumentEvent e) {}
            });

    Interactions.typeText(field, "abc");

    assertEquals("abc", field.getText(), "each KEY_TYPED should insert one character");
    assertEquals(3, inserts.get(), "the DocumentListener should fire once per character");
  }

  // ------------------------------------------------------------------ press_key

  @Test
  void pressKeyDeliversPressAndReleaseAndSuppressesTypedForANamedKey() throws Exception {
    AtomicInteger pressedCode = new AtomicInteger(-1);
    AtomicInteger typedCount = new AtomicInteger();
    CountDownLatch released = new CountDownLatch(1);
    JTextField field = new JTextField();
    field.addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(KeyEvent e) {
            pressedCode.set(e.getKeyCode());
          }

          @Override
          public void keyTyped(KeyEvent e) {
            typedCount.incrementAndGet();
          }

          @Override
          public void keyReleased(KeyEvent e) {
            released.countDown();
          }
        });

    Interactions.Settle settle =
        Interactions.pressKey(field, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0, 1);

    assertTrue(released.await(2, TimeUnit.SECONDS), "the key press should be delivered");
    assertEquals(KeyEvent.VK_ESCAPE, pressedCode.get());
    assertEquals(0, typedCount.get(), "a CHAR_UNDEFINED keyChar sends no KEY_TYPED event");
    assertTrue(settle.edtSettled(), "an idle EDT settles within the grace period");
    assertNotNull(settle.delta(), "a settled dispatch carries a known window delta");
    assertTrue(settle.delta().isEmpty(), "a key press into a bare field opens/closes nothing");
  }

  @Test
  void pressKeyRepeatsTheTypedCharAndCarriesModifiers() {
    AtomicInteger typed = new AtomicInteger();
    AtomicInteger lastModifiers = new AtomicInteger();
    JTextField field = new JTextField();
    field.addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyTyped(KeyEvent e) {
            typed.incrementAndGet();
          }

          @Override
          public void keyPressed(KeyEvent e) {
            lastModifiers.set(e.getModifiersEx());
          }
        });

    Interactions.pressKey(field, KeyEvent.VK_A, 'a', 0, 2);
    EdtDiagnostics.flush(Duration.ofSeconds(2));
    assertEquals(2, typed.get(), "count=2 sends the KEY_TYPED char twice");

    Interactions.pressKey(
        field, KeyEvent.VK_SPACE, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK, 1);
    EdtDiagnostics.flush(Duration.ofSeconds(2));
    assertEquals(
        InputEvent.CTRL_DOWN_MASK,
        lastModifiers.get() & InputEvent.CTRL_DOWN_MASK,
        "the modifier bit reaches the key listener");
  }

  // ------------------------------------------------------------------ select: index

  @Test
  void selectIndexOnComboList() {
    JComboBox<String> combo = new JComboBox<>(new String[] {"A", "B", "C"});
    Interactions.selectIndex(combo, 2);
    assertEquals(2, combo.getSelectedIndex());

    JList<String> list = new JList<>(new String[] {"x", "y", "z"});
    Interactions.selectIndex(list, 1);
    assertEquals(1, list.getSelectedIndex());
  }

  @Test
  void selectIndexOnTabAndTableRow() {
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("One", new JLabel());
    tabs.addTab("Two", new JLabel());
    Interactions.selectIndex(tabs, 1);
    assertEquals(1, tabs.getSelectedIndex());

    DefaultTableModel model = new DefaultTableModel(new Object[] {"Name"}, 0);
    model.addRow(new Object[] {"Ada"});
    model.addRow(new Object[] {"Grace"});
    JTable table = new JTable(model);
    Interactions.selectIndex(table, 1);
    assertEquals(1, table.getSelectedRow());
  }

  @Test
  void selectIndexOutOfRangeThrows() {
    JComboBox<String> combo = new JComboBox<>(new String[] {"A"});
    assertThrows(IllegalArgumentException.class, () -> Interactions.selectIndex(combo, 5));
  }

  @Test
  void selectIndexUnsupportedComponentThrows() {
    assertThrows(IllegalArgumentException.class, () -> Interactions.selectIndex(new JButton(), 0));
  }

  // ------------------------------------------------------------------ select: value

  @Test
  void selectValueMatchesComboItemTextCaseInsensitively() {
    JComboBox<String> combo = new JComboBox<>(new String[] {"Engineer", "Designer", "Manager"});
    Interactions.selectValue(combo, "manager");
    assertEquals(2, combo.getSelectedIndex());
  }

  @Test
  void selectValueMatchesTableCell() {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Role"}, 0);
    model.addRow(new Object[] {"Ada", "Engineer"});
    model.addRow(new Object[] {"Grace", "Manager"});
    JTable table = new JTable(model);
    Interactions.selectValue(table, "Manager");
    assertEquals(1, table.getSelectedRow());
  }

  @Test
  void selectValueOnSpinner() {
    JSpinner listSpinner =
        new JSpinner(new SpinnerListModel(new String[] {"Low", "Medium", "High"}));
    Interactions.selectValue(listSpinner, "High");
    assertEquals("High", listSpinner.getValue());

    JSpinner numberSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
    Interactions.selectValue(numberSpinner, "42");
    assertEquals(42, ((Number) numberSpinner.getValue()).intValue());
  }

  @Test
  void selectValueNoMatchThrows() {
    JComboBox<String> combo = new JComboBox<>(new String[] {"A", "B"});
    assertThrows(IllegalArgumentException.class, () -> Interactions.selectValue(combo, "Z"));
  }

  @Test
  void selectValueMatchesRendererTextNotModelToString() {
    // Renderer-aware matching (finding 4.2): the model holds Integers but the user sees "Role N".
    JList<Integer> list = new JList<>(new Integer[] {1, 2, 3});
    list.setCellRenderer((l, v, i, sel, foc) -> new JLabel("Role " + v));
    Interactions.selectValue(list, "Role 2");
    assertEquals(1, list.getSelectedIndex(), "match should be against the rendered text");
  }

  // ------------------------------------------------------------------ select: path (tree)

  @Test
  void selectTreePathWalksNodesByText() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
    DefaultMutableTreeNode colors = new DefaultMutableTreeNode("Colors");
    DefaultMutableTreeNode blue = new DefaultMutableTreeNode("Blue");
    colors.add(blue);
    root.add(colors);
    JTree tree = new JTree(root);

    Interactions.SelectionResult result =
        Interactions.selectPath(tree, List.of("Root", "Colors", "Blue"));

    assertFalse(result.modalSafeDispatch(), "tree selection is synchronous");
    assertNotNull(tree.getSelectionPath());
    assertEquals(blue, tree.getSelectionPath().getLastPathComponent());
  }

  @Test
  void selectTreePathUnknownNodeThrows() {
    JTree tree = new JTree(new DefaultMutableTreeNode("Root"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Interactions.selectPath(tree, List.of("Root", "Nope")));
  }

  // ------------------------------------------------------------------ select: path (menu)

  @Test
  void menuPathActivationIsModalSafeAndFiresLeafAction() throws Exception {
    CountDownLatch fired = new CountDownLatch(1);
    JMenuItem open = new JMenuItem("Open...");
    open.addActionListener(e -> fired.countDown());
    JMenu file = new JMenu("File");
    file.add(open);
    JMenuBar menuBar = new JMenuBar();
    menuBar.add(file);

    Interactions.SelectionResult result =
        Interactions.selectPath(menuBar, List.of("File", "Open..."));

    assertTrue(result.modalSafeDispatch(), "menu-item activation is dispatched modal-safely");
    assertTrue(fired.await(2, TimeUnit.SECONDS), "the terminal menu item's action should fire");
  }

  @Test
  void menuPathUnknownEntryThrows() {
    JMenuBar menuBar = new JMenuBar();
    menuBar.add(new JMenu("File"));
    assertThrows(
        IllegalArgumentException.class, () -> Interactions.selectPath(menuBar, List.of("Edit")));
  }

  // ------------------------------------------------------------------ set_text append mode

  @Test
  void appendModeAddsToTheEndOfTheDocument() {
    JTextField field = new JTextField("abc");
    Interactions.setText(field, "def", true);
    assertEquals("abcdef", field.getText());
  }

  @Test
  void appendModeFiresDocumentEventsLikeTyping() {
    JTextField field = new JTextField("a");
    AtomicInteger inserts = new AtomicInteger();
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                inserts.incrementAndGet();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {}

              @Override
              public void changedUpdate(DocumentEvent e) {}
            });
    Interactions.setText(field, "b", true);
    assertEquals("ab", field.getText());
    assertEquals(1, inserts.get());
  }

  @Test
  void appendModeIsRejectedOnAReadOnlyTargetRatherThanSilentlyNoOpping() {
    JTextField field = new JTextField("abc");
    field.setEditable(false);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Interactions.setText(field, "d", true));
    assertTrue(e.getMessage().contains("not editable"), e.getMessage());
    assertEquals("abc", field.getText());
  }

  @Test
  void replaceModeStillWorksOnAReadOnlyTarget() {
    JTextField field = new JTextField("abc");
    field.setEditable(false);
    Interactions.setText(field, "xyz", false);
    assertEquals("xyz", field.getText());
  }

  // ------------------------------------------------------------------ table-cell addressing

  private static JTable table() {
    return new JTable(
        new DefaultTableModel(
            new Object[][] {{"r0c0", "r0c1"}, {"r1c0", "r1c1"}}, new Object[] {"A", "B"}));
  }

  @Test
  void clickPointRejectsMixingAPointWithACell() {
    assertThrows(IllegalArgumentException.class, () -> new Interactions.ClickPoint(5, 5, 1, 1));
  }

  @Test
  void clickPointRequiresBothRowAndColumn() {
    assertThrows(
        IllegalArgumentException.class, () -> new Interactions.ClickPoint(null, null, 1, null));
  }

  @Test
  void clickPointAllowsAPlainPointOrCentre() {
    assertNotNull(Interactions.ClickPoint.at(3, 4));
    assertNotNull(Interactions.ClickPoint.at(null, null));
    assertNotNull(Interactions.ClickPoint.cell(0, 1));
  }

  @Test
  void selectCellSelectsTheCellAndDescribesIt() {
    JTable table = table();
    Interactions.SelectionResult result = Interactions.selectCell(table, 1, 1);
    assertEquals(1, table.getSelectedRow());
    assertTrue(result.description().contains("(1,1)"), result.description());
    assertTrue(result.description().contains("r1c1"), result.description());
  }

  @Test
  void selectCellOutOfRangeNamesTheActualDimensions() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Interactions.selectCell(table(), 5, 0));
    assertTrue(e.getMessage().contains("2 rows"), e.getMessage());
    assertTrue(e.getMessage().contains("2 columns"), e.getMessage());
  }

  @Test
  void selectCellRejectsANonTable() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> Interactions.selectCell(new JLabel("x"), 0, 0));
    assertTrue(e.getMessage().contains("JTable"), e.getMessage());
  }

  // ------------------------------------------------------------- no-match errors list the children

  @Test
  void aMissingMenuEntryErrorListsTheAvailableChildren() {
    JMenuBar bar = new JMenuBar();
    JMenu file = new JMenu("File");
    file.add(new JMenuItem("Open"));
    file.add(new JMenuItem("Save As…"));
    bar.add(file);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Interactions.selectPath(bar, List.of("File", "Save")));
    assertTrue(e.getMessage().contains("children:"), e.getMessage());
    assertTrue(e.getMessage().contains("Open"), e.getMessage());
    assertTrue(e.getMessage().contains("Save As…"), e.getMessage());
  }

  @Test
  void aMissingTreeNodeErrorListsTheRenderedChildLabels() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    root.add(new DefaultMutableTreeNode("(system,onStartup)"));
    root.add(new DefaultMutableTreeNode("(system,onShutdown)"));
    JTree tree = new JTree(root);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Interactions.selectPath(tree, List.of("root", "onStartup")));
    assertTrue(e.getMessage().contains("children:"), e.getMessage());
    assertTrue(e.getMessage().contains("(system,onStartup)"), e.getMessage());
  }
}
