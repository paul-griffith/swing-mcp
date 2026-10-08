package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for {@link Introspection}'s tree logic: display-text selection,
 * selection-state capture, depth limiting, and EDT discipline. These build components but never
 * show a window, so they need no display.
 */
class IntrospectionTest {

  private static ComponentTreeSnapshot snapshot(java.awt.Component component) {
    return Introspection.componentTree(component, 0, Introspection.DEFAULT_TIMEOUT);
  }

  // ------------------------------------------------------------------ display text

  @Test
  void buttonTextIsBestEffortText() {
    ComponentTreeSnapshot node = snapshot(new JButton("Click me"));
    assertEquals("Click me", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.BUTTON_TEXT, node.textSource());
  }

  @Test
  void labelTextIsBestEffortText() {
    ComponentTreeSnapshot node = snapshot(new JLabel("Name:"));
    assertEquals("Name:", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.LABEL_TEXT, node.textSource());
  }

  @Test
  void textComponentContentIsTruncatedToCap() {
    String longText = "x".repeat(500);
    ComponentTreeSnapshot node = snapshot(new JTextArea(longText));
    assertEquals(ComponentTreeSnapshot.TextSource.TEXT_COMPONENT, node.textSource());
    assertEquals(ComponentAddress.TEXT_CAP + 1, node.text().length()); // capped chars + ellipsis
    assertTrue(node.text().endsWith("…"));
    assertTrue(node.text().startsWith("xxx"));
  }

  @Test
  void shortTextComponentContentIsNotTruncated() {
    ComponentTreeSnapshot node = snapshot(new JTextField("hello"));
    assertEquals("hello", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.TEXT_COMPONENT, node.textSource());
  }

  @Test
  void titledBorderTitleIsUsedWhenNoStrongerSource() {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createTitledBorder("Person"));
    ComponentTreeSnapshot node = snapshot(panel);
    assertEquals("Person", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.TITLED_BORDER, node.textSource());
  }

  @Test
  void tooltipIsUsedWhenEarlierSourcesAreBlank() {
    JButton iconButton = new JButton(); // no text
    iconButton.setToolTipText("Save the document");
    ComponentTreeSnapshot node = snapshot(iconButton);
    assertEquals("Save the document", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.TOOLTIP, node.textSource());
  }

  @Test
  void accessibleNameIsTheLastResort() {
    JPanel panel = new JPanel();
    panel.getAccessibleContext().setAccessibleName("accessible-only");
    ComponentTreeSnapshot node = snapshot(panel);
    assertEquals("accessible-only", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.ACCESSIBLE_NAME, node.textSource());
  }

  // ------------------------------------------------------------------ shared text cap

  private static final String HUGE = "y".repeat(10_000);

  private static void assertCapped(String text) {
    assertNotNull(text);
    assertEquals(ComponentAddress.TEXT_CAP + 1, text.length(), "capped chars + ellipsis");
    assertTrue(text.endsWith("…"));
  }

  @Test
  void everyDisplayTextSourceIsCapped() {
    assertCapped(snapshot(new JLabel(HUGE)).text());
    assertCapped(snapshot(new JButton(HUGE)).text());
    JButton tooltipOnly = new JButton();
    tooltipOnly.setToolTipText(HUGE);
    assertCapped(snapshot(tooltipOnly).text());
  }

  @Test
  void selectionTextIsCappedForCombosListsAndTabs() {
    JComboBox<String> combo = new JComboBox<>(new String[] {HUGE});
    combo.setSelectedIndex(0);
    assertCapped(snapshot(combo).selection().selectedText());

    JList<String> list = new JList<>(new String[] {HUGE});
    list.setSelectedIndex(0);
    assertCapped(snapshot(list).selection().selectedText());

    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab(HUGE, new JPanel());
    tabs.setSelectedIndex(0);
    assertCapped(snapshot(tabs).selection().selectedText());
  }

  @Test
  void findMatchTextIsCapped() {
    JPanel root = new JPanel();
    root.add(new JTextArea(HUGE));
    Introspection.FindResult result =
        Introspection.find(
            List.of(root),
            new Introspection.FindCriteria(null, "JTextArea", null, false),
            50,
            Introspection.DEFAULT_TIMEOUT);
    assertCapped(result.matches().get(0).text());
  }

  @Test
  void readItemsCellTextIsCapped() {
    Introspection.DataContents list =
        Introspection.readItems(
            new JList<>(new String[] {HUGE}),
            Introspection.DEFAULT_ITEM_LIMIT,
            Introspection.DEFAULT_TIMEOUT);
    assertCapped(list.rows().get(0).get(0));

    DefaultTableModel model = new DefaultTableModel(new Object[] {HUGE}, 0);
    model.addRow(new Object[] {HUGE});
    Introspection.DataContents table =
        Introspection.readItems(
            new JTable(model), Introspection.DEFAULT_ITEM_LIMIT, Introspection.DEFAULT_TIMEOUT);
    assertCapped(table.columns().get(0));
    assertCapped(table.rows().get(0).get(0));
  }

  @Test
  void plainPanelHasNoText() {
    ComponentTreeSnapshot node = snapshot(new JPanel());
    assertNull(node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.NONE, node.textSource());
  }

  // ------------------------------------------------------------------ find_components

  private static Introspection.FindResult find(
      java.awt.Component root, Introspection.FindCriteria criteria, int limit) {
    return Introspection.find(List.of(root), criteria, limit, Introspection.DEFAULT_TIMEOUT);
  }

  @Test
  void findByNameSubstringMatchesComponents() {
    JPanel root = new JPanel();
    JButton save = new JButton("Save");
    save.setName("saveButton");
    JButton cancel = new JButton("Cancel");
    cancel.setName("cancelButton");
    root.add(save);
    root.add(cancel);

    assertEquals(
        2, find(root, new Introspection.FindCriteria("button", null, null, false), 50).total());
    assertEquals(
        1, find(root, new Introspection.FindCriteria("save", null, null, false), 50).total());
  }

  @Test
  void findByClassAndByTextSubstring() {
    JPanel root = new JPanel();
    root.add(new JButton("Save"));
    root.add(new JLabel("Save")); // same text, different class
    root.add(new JTextField("Save me")); // text contains "Save"

    assertEquals(
        1, find(root, new Introspection.FindCriteria(null, "JButton", null, false), 50).total());
    assertEquals(
        3, find(root, new Introspection.FindCriteria(null, null, "save", false), 50).total());
  }

  @Test
  void findInteractableOnlyExcludesContainersAndLabels() {
    JPanel root = new JPanel();
    root.add(new JLabel("hi"));
    root.add(new JButton("go"));
    root.add(new JTextField());

    // button + text field are interactable; the panel and label are not.
    assertEquals(2, find(root, new Introspection.FindCriteria(null, null, null, true), 50).total());
  }

  @Test
  void findRespectsLimitAndReportsTruncation() {
    JPanel root = new JPanel();
    for (int i = 0; i < 5; i++) {
      root.add(new JButton("B"));
    }
    Introspection.FindResult result =
        find(root, new Introspection.FindCriteria(null, "JButton", null, false), 2);
    assertEquals(5, result.total());
    assertEquals(2, result.matches().size());
    assertTrue(result.truncated());
  }

  @Test
  void findRequiresAtLeastOneFilter() {
    assertThrows(
        IllegalArgumentException.class,
        () -> find(new JPanel(), new Introspection.FindCriteria(null, null, null, false), 50));
  }

  // ------------------------------------------------------------------ find zero-result diagnostics

  @Test
  void nameNearMissPickerAcceptsPrefixSharersAndSubstrings() {
    assertTrue(Introspection.isNameNearMiss("saveButton", "saveXyz"), "shared prefix >= 3");
    assertTrue(Introspection.isNameNearMiss("SaveButton", "sAvEXyz"), "prefix is case-insensitive");
    assertTrue(Introspection.isNameNearMiss("nameField", "Field"), "name containing the query");
    assertFalse(Introspection.isNameNearMiss("okButton", "save"), "no prefix, no substring");
    assertFalse(Introspection.isNameNearMiss("sxButton", "syButton"), "prefix shorter than 3");
  }

  @Test
  void classNearMissPickerAcceptsSubstringsEitherWayAndFqcnTails() {
    assertTrue(Introspection.isClassNearMiss("JideTabbedPane", "TabbedPane"));
    assertTrue(Introspection.isClassNearMiss("TabbedPane", "JideTabbedPane"), "reverse substring");
    assertTrue(
        Introspection.isClassNearMiss("JTabbedPane", "javax.swing.JTabbedPane"),
        "FQCN query compared by its simple tail");
    assertFalse(Introspection.isClassNearMiss("JButton", "JTree"));
  }

  @Test
  void zeroResultFindReportsScannedAndNearMissHint() {
    JPanel root = new JPanel();
    JButton save = new JButton("Save");
    save.setName("saveButton");
    root.add(save);

    Introspection.FindResult result =
        find(root, new Introspection.FindCriteria("saveXyz", null, null, false), 50);
    assertEquals(0, result.total());
    assertEquals(2, result.scanned(), "panel + button were both visited");
    assertNotNull(result.hint());
    assertTrue(result.hint().contains("saveButton"), "near-miss name expected: " + result.hint());
    assertTrue(result.hint().contains("searched"), "scope note expected: " + result.hint());
  }

  @Test
  void zeroResultFindOnAnEmptyScopeCallsOutTheMissingDescendants() {
    Introspection.FindResult result =
        find(new JPanel(), new Introspection.FindCriteria("anything", null, null, false), 50);
    assertEquals(0, result.total());
    assertEquals(1, result.scanned());
    assertTrue(
        result.hint().contains("no descendants"), "empty-scope note expected: " + result.hint());
  }

  @Test
  void zeroResultFindWithADottedClassQueryMentionsFqcnMatching() {
    JPanel root = new JPanel();
    root.add(new JButton("x"));
    Introspection.FindResult result =
        find(root, new Introspection.FindCriteria(null, "com.example.NoSuchPane", null, false), 50);
    assertEquals(0, result.total());
    assertTrue(
        result.hint().contains("fully-qualified"), "FQCN reminder expected: " + result.hint());
  }

  @Test
  void successfulFindCarriesNoHint() {
    JPanel root = new JPanel();
    root.add(new JButton("Save"));
    Introspection.FindResult result =
        find(root, new Introspection.FindCriteria(null, "JButton", null, false), 50);
    assertEquals(1, result.total());
    assertNull(result.hint());
    assertEquals(2, result.scanned());
  }

  // ------------------------------------------------------------------ wait_for

  @Test
  void waitConditionParsesCaseInsensitivelyAndRejectsUnknowns() {
    assertEquals(Introspection.WaitCondition.APPEARS, Introspection.WaitCondition.parse("appears"));
    assertEquals(
        Introspection.WaitCondition.TEXT_CONTAINS,
        Introspection.WaitCondition.parse(" Text_Contains "));
    assertNull(Introspection.WaitCondition.parse("vanishes"));
    assertNull(Introspection.WaitCondition.parse(null));
  }

  @Test
  void evaluateConditionAppearsAndDisappears() {
    ComponentTreeSnapshot button = snapshot(new JButton("Go"));
    Introspection.ConditionProbe appears =
        Introspection.evaluateCondition(
            Introspection.WaitCondition.APPEARS, List.of(button), 1, null);
    assertTrue(appears.met());
    assertEquals(button, appears.match(), "the first match is returned");

    assertFalse(
        Introspection.evaluateCondition(Introspection.WaitCondition.APPEARS, List.of(), 0, null)
            .met());
    assertTrue(
        Introspection.evaluateCondition(Introspection.WaitCondition.DISAPPEARS, List.of(), 0, null)
            .met());
    // DISAPPEARS uses the *total*: matches capped away by the find limit still count.
    assertFalse(
        Introspection.evaluateCondition(Introspection.WaitCondition.DISAPPEARS, List.of(), 3, null)
            .met());
  }

  @Test
  void evaluateConditionEnabledSkipsDisabledMatches() {
    JButton disabled = new JButton("No");
    disabled.setEnabled(false);
    ComponentTreeSnapshot disabledSnap = snapshot(disabled);
    ComponentTreeSnapshot enabledSnap = snapshot(new JButton("Yes"));

    Introspection.ConditionProbe probe =
        Introspection.evaluateCondition(
            Introspection.WaitCondition.ENABLED, List.of(disabledSnap, enabledSnap), 2, null);
    assertTrue(probe.met());
    assertEquals(enabledSnap, probe.match(), "the first ENABLED match wins, not just the first");

    assertFalse(
        Introspection.evaluateCondition(
                Introspection.WaitCondition.ENABLED, List.of(disabledSnap), 1, null)
            .met());
  }

  @Test
  void evaluateConditionTextContainsIsCaseInsensitive() {
    ComponentTreeSnapshot label = snapshot(new JLabel("Task complete"));
    assertTrue(
        Introspection.evaluateCondition(
                Introspection.WaitCondition.TEXT_CONTAINS, List.of(label), 1, "COMPLETE")
            .met());
    assertFalse(
        Introspection.evaluateCondition(
                Introspection.WaitCondition.TEXT_CONTAINS, List.of(label), 1, "failed")
            .met());
  }

  @Test
  void waitForValidatesItsArguments() {
    Introspection.FindCriteria valid = new Introspection.FindCriteria("x", null, null, false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Introspection.waitFor(
                null,
                new Introspection.FindCriteria(null, null, null, false),
                Introspection.WaitCondition.APPEARS,
                null,
                Duration.ofMillis(100),
                Introspection.DEFAULT_WAIT_POLL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Introspection.waitFor(
                null, valid, null, null, Duration.ofMillis(100), Introspection.DEFAULT_WAIT_POLL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Introspection.waitFor(
                null,
                valid,
                Introspection.WaitCondition.TEXT_CONTAINS,
                " ",
                Duration.ofMillis(100),
                Introspection.DEFAULT_WAIT_POLL));
  }

  @Test
  void waitForTimesOutWithMetFalseAndLastCount() {
    long start = System.nanoTime();
    Introspection.WaitResult result =
        Introspection.waitFor(
            null,
            new Introspection.FindCriteria("neverMatches", null, null, false),
            Introspection.WaitCondition.APPEARS,
            null,
            Duration.ofMillis(300),
            Duration.ofMillis(100));
    long wallMs = (System.nanoTime() - start) / 1_000_000;
    assertFalse(result.met());
    assertEquals(0, result.lastCount());
    assertNull(result.match());
    assertTrue(wallMs >= 300, "should poll until the deadline, waited " + wallMs + "ms");
  }

  @Test
  void waitForTreatsAnUnresolvableScopeAsZeroMatchesNotAnError() {
    Introspection.FindCriteria criteria = new Introspection.FindCriteria("x", null, null, false);
    // appears in a scope that never resolves → clean timeout, no ComponentNotFound error.
    Introspection.WaitResult appears =
        Introspection.waitFor(
            "noSuchScope",
            criteria,
            Introspection.WaitCondition.APPEARS,
            null,
            Duration.ofMillis(150),
            Duration.ofMillis(100));
    assertFalse(appears.met());
    assertEquals(0, appears.lastCount());

    // disappears in an unresolvable scope is met immediately: nothing there matches.
    Introspection.WaitResult gone =
        Introspection.waitFor(
            "noSuchScope",
            criteria,
            Introspection.WaitCondition.DISAPPEARS,
            null,
            Duration.ofSeconds(5),
            Duration.ofMillis(100));
    assertTrue(gone.met());
    assertTrue(gone.elapsedMs() < 4000, "should be met on the first probe: " + gone.elapsedMs());
  }

  // ------------------------------------------------------------------ list_windows filter/sort

  private static WindowSnapshot window(String id, String title, boolean showing, boolean focused) {
    return new WindowSnapshot(
        id,
        0,
        WindowSnapshot.Kind.FRAME,
        title,
        id,
        ComponentAddress.IdKind.NAME,
        new Bounds(0, 0, 100, 100),
        showing,
        showing,
        focused,
        focused,
        null,
        false,
        Boolean.FALSE,
        Boolean.FALSE);
  }

  @Test
  void filterWindowsDropsHiddenWindowsByDefaultAndCountsThem() {
    List<WindowSnapshot> all =
        List.of(
            window("stale1", "Old Dialog", false, false),
            window("main", "Main", true, false),
            window("stale2", "Older Dialog", false, false));
    Introspection.WindowFilter filtered = Introspection.filterWindows(all, false, null);
    assertEquals(List.of("main"), filtered.windows().stream().map(WindowSnapshot::id).toList());
    assertEquals(2, filtered.hiddenCount());
  }

  @Test
  void filterWindowsIncludeHiddenKeepsEverything() {
    List<WindowSnapshot> all =
        List.of(window("stale", "Old", false, false), window("main", "Main", true, false));
    Introspection.WindowFilter filtered = Introspection.filterWindows(all, true, null);
    assertEquals(2, filtered.windows().size());
    assertEquals(0, filtered.hiddenCount());
  }

  @Test
  void filterWindowsTitleFilterIsACaseInsensitiveSubstring() {
    List<WindowSnapshot> all =
        List.of(
            window("main", "swing-mcp demo", true, false),
            window("other", "Details", true, false),
            window("untitled", null, true, false),
            window("hiddenOther", "Detached", false, false));
    Introspection.WindowFilter filtered = Introspection.filterWindows(all, false, "DEMO");
    assertEquals(List.of("main"), filtered.windows().stream().map(WindowSnapshot::id).toList());
    assertEquals(0, filtered.hiddenCount(), "hidden windows failing the title filter don't count");
  }

  @Test
  void filterWindowsSortsFocusedFirstKeepingZOrderOtherwise() {
    List<WindowSnapshot> all =
        List.of(
            window("a", "A", true, false),
            window("b", "B", true, false),
            window("focused", "F", true, true),
            window("c", "C", true, false));
    Introspection.WindowFilter filtered = Introspection.filterWindows(all, false, null);
    assertEquals(
        List.of("focused", "a", "b", "c"),
        filtered.windows().stream().map(WindowSnapshot::id).toList());
  }

  // ------------------------------------------------------------------ selection

  @Test
  void toggleSelectionIsCaptured() {
    JCheckBox checkBox = new JCheckBox("Agree");
    checkBox.setSelected(true);
    ComponentTreeSnapshot.Selection selection = snapshot(checkBox).selection();
    assertNotNull(selection);
    assertEquals(ComponentTreeSnapshot.SelectionKind.TOGGLE, selection.kind());
    assertTrue(selection.selected());
  }

  @Test
  void comboBoxSelectionIsCaptured() {
    JComboBox<String> combo = new JComboBox<>(new String[] {"A", "B", "C"});
    combo.setSelectedIndex(1);
    ComponentTreeSnapshot.Selection selection = snapshot(combo).selection();
    assertEquals(ComponentTreeSnapshot.SelectionKind.COMBO_BOX, selection.kind());
    assertEquals(1, selection.selectedIndex());
    assertEquals("B", selection.selectedText());
  }

  @Test
  void listSelectionIsCaptured() {
    JList<String> list = new JList<>(new String[] {"x", "y", "z"});
    list.setSelectedIndices(new int[] {0, 2});
    ComponentTreeSnapshot.Selection selection = snapshot(list).selection();
    assertEquals(ComponentTreeSnapshot.SelectionKind.LIST, selection.kind());
    assertIterableEquals(List.of(0, 2), selection.selectedIndices());
    assertEquals(0, selection.selectedIndex());
    assertEquals("x", selection.selectedText());
  }

  @Test
  void tabbedPaneSelectionIsCaptured() {
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("One", new JPanel());
    tabs.addTab("Two", new JPanel());
    tabs.setSelectedIndex(1);
    ComponentTreeSnapshot.Selection selection = snapshot(tabs).selection();
    assertEquals(ComponentTreeSnapshot.SelectionKind.TABBED_PANE, selection.kind());
    assertEquals(1, selection.selectedIndex());
    assertEquals("Two", selection.selectedText());
  }

  @Test
  void tableSelectionIsCaptured() {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"Col"}, 0);
    model.addRow(new Object[] {"r0"});
    model.addRow(new Object[] {"r1"});
    model.addRow(new Object[] {"r2"});
    JTable table = new JTable(model);
    table.setRowSelectionInterval(1, 1);
    ComponentTreeSnapshot.Selection selection = snapshot(table).selection();
    assertEquals(ComponentTreeSnapshot.SelectionKind.TABLE, selection.kind());
    assertIterableEquals(List.of(1), selection.selectedIndices());
    assertEquals(1, selection.selectedIndex());
  }

  @Test
  void componentsWithoutSelectionReportNull() {
    assertNull(snapshot(new JButton("plain")).selection());
    assertNull(snapshot(new JLabel("label")).selection());
    assertNull(snapshot(new JPanel()).selection());
  }

  // ------------------------------------------------------------------ depth / childCount

  /** three-level tree: root > mid > leaf. */
  private static JPanel nestedTree() {
    JPanel leaf = new JPanel();
    leaf.add(new JButton("deep"));
    JPanel mid = new JPanel();
    mid.add(leaf);
    JPanel root = new JPanel();
    root.add(mid);
    return root;
  }

  @Test
  void maxDepthZeroYieldsRootOnlyButKeepsTrueChildCount() {
    ComponentTreeSnapshot tree =
        Introspection.componentTree(nestedTree(), 0, Introspection.DEFAULT_TIMEOUT);
    assertTrue(tree.children().isEmpty());
    assertEquals(1, tree.childCount());
  }

  @Test
  void maxDepthOneIncludesOnlyDirectChildren() {
    ComponentTreeSnapshot tree =
        Introspection.componentTree(nestedTree(), 1, Introspection.DEFAULT_TIMEOUT);
    assertEquals(1, tree.children().size());
    ComponentTreeSnapshot mid = tree.children().get(0);
    assertTrue(mid.children().isEmpty(), "grandchildren should be truncated at depth 1");
    assertEquals(1, mid.childCount(), "but the true child count is still reported");
  }

  @Test
  void unlimitedDepthWalksTheWholeTree() {
    ComponentTreeSnapshot tree = Introspection.componentTree(nestedTree());
    ComponentTreeSnapshot mid = tree.children().get(0);
    ComponentTreeSnapshot leaf = mid.children().get(0);
    ComponentTreeSnapshot button = leaf.children().get(0);
    assertEquals("JButton", button.simpleClassName());
    assertEquals("deep", button.text());
  }

  // ------------------------------------------------------------------ EDT discipline

  @Test
  void snapshotIsCallableFromTheEdtWithoutDeadlock() throws Exception {
    AtomicReference<ComponentTreeSnapshot> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () ->
            result.set(Introspection.componentTree(new JButton("edt"), 0, Duration.ofSeconds(5))));
    assertNotNull(result.get());
    assertEquals("edt", result.get().text());
  }

  @Test
  void snapshotTimesOutWhenTheEdtIsBlocked() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          try {
            gate.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      assertThrows(
          EdtOps.EdtTimeoutException.class,
          () -> Introspection.componentTree(new JPanel(), 0, Duration.ofMillis(50)));
    } finally {
      gate.countDown(); // release the EDT so later tests are unaffected
    }
  }

  // ------------------------------------------------------------------ get_items (readItems)

  private static Introspection.DataContents items(java.awt.Component c) {
    return Introspection.readItems(
        c, Introspection.DEFAULT_ITEM_LIMIT, Introspection.DEFAULT_TIMEOUT);
  }

  @Test
  void readItemsTableReturnsColumnsAndCellText() {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Role"}, 0);
    model.addRow(new Object[] {"Ada", "Engineer"});
    model.addRow(new Object[] {"Grace", "Manager"});
    JTable table = new JTable(model);
    table.setRowSelectionInterval(1, 1);

    Introspection.DataContents d = items(table);
    assertEquals(Introspection.DataKind.TABLE, d.kind());
    assertEquals(List.of("Name", "Role"), d.columns());
    assertEquals(2, d.total());
    assertEquals(List.of("Ada", "Engineer"), d.rows().get(0));
    assertEquals(1, d.selectedIndex());
  }

  @Test
  void readItemsListAndComboReturnRenderedItems() {
    Introspection.DataContents list = items(new JList<>(new String[] {"x", "y", "z"}));
    assertEquals(Introspection.DataKind.LIST, list.kind());
    assertEquals(3, list.total());
    assertEquals(List.of("x"), list.rows().get(0));

    Introspection.DataContents combo = items(new JComboBox<>(new String[] {"A", "B"}));
    assertEquals(Introspection.DataKind.COMBO_BOX, combo.kind());
    assertEquals(2, combo.total());
    assertEquals(List.of("A"), combo.rows().get(0));
  }

  @Test
  void readItemsIsRendererAwareNotModelToString() {
    // A custom renderer turns the Integer model values into different visible text.
    JList<Integer> list = new JList<>(new Integer[] {1, 2});
    list.setCellRenderer((l, v, i, sel, foc) -> new JLabel("Item " + v));
    Introspection.DataContents d = items(list);
    assertEquals(List.of("Item 1"), d.rows().get(0), "list read should use the cell renderer text");
  }

  @Test
  void readItemsTreeFlattensNodesDepthIndented() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
    DefaultMutableTreeNode child = new DefaultMutableTreeNode("Child");
    root.add(child);
    Introspection.DataContents d = items(new JTree(root));
    assertEquals(Introspection.DataKind.TREE, d.kind());
    assertEquals(2, d.total());
    assertEquals(List.of("Root"), d.rows().get(0));
    assertEquals(List.of("  Child"), d.rows().get(1), "a depth-1 node is indented");
  }

  @Test
  void readItemsCapsToLimitAndFlagsTruncation() {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"n"}, 0);
    for (int i = 0; i < 5; i++) {
      model.addRow(new Object[] {i});
    }
    Introspection.DataContents d =
        Introspection.readItems(new JTable(model), 2, Introspection.DEFAULT_TIMEOUT);
    assertEquals(5, d.total());
    assertEquals(2, d.rows().size());
    assertTrue(d.truncated());
  }

  @Test
  void readItemsRejectsANonDataComponent() {
    assertThrows(IllegalArgumentException.class, () -> items(new JButton("x")));
  }

  // ------------------------------------------------------------------ get_properties

  @Test
  void readablePropertiesIncludeCommonBeanGetters() {
    JButton button = new JButton("Go");
    button.setToolTipText("tip");
    java.util.Map<String, String> props =
        Introspection.properties(button, List.of(), Introspection.DEFAULT_TIMEOUT);
    assertEquals("Go", props.get("text"));
    assertEquals("tip", props.get("toolTipText"));
  }

  @Test
  void sideEffectingGettersAreNeverInvoked() {
    // Finding #3: getGraphics() allocates a Graphics that is never disposed — it is denied
    // outright.
    java.util.Map<String, String> props =
        Introspection.properties(new JButton("Go"), List.of(), Introspection.DEFAULT_TIMEOUT);
    assertFalse(props.containsKey("graphics"), "getGraphics must not be read: " + props.keySet());
  }

  @Test
  void classNamesAreBothSimpleAndFullyQualified() {
    ComponentTreeSnapshot node = snapshot(new JButton("x"));
    assertEquals("JButton", node.simpleClassName());
    assertEquals("javax.swing.JButton", node.className());
    assertFalse(node.showing());
    assertNull(node.screenBounds(), "an unshown component has no on-screen bounds");
    assertNotNull(node.bounds());
  }

  // ------------------------------------------------------------------ structured value rendering

  private static String property(java.awt.Component component, String name) {
    return Introspection.properties(component, List.of(name), Introspection.DEFAULT_TIMEOUT)
        .get(name);
  }

  @Test
  void colorRendersAsHex() {
    JLabel label = new JLabel("x");
    label.setForeground(new java.awt.Color(0, 120, 215));
    assertEquals("#0078d7", property(label, "foreground"));
  }

  @Test
  void translucentColorCarriesItsAlpha() {
    JLabel label = new JLabel("x");
    label.setForeground(new java.awt.Color(255, 0, 0, 128));
    assertEquals("#ff0000/80", property(label, "foreground"));
  }

  @Test
  void dimensionAndBoundsRenderCompactly() {
    JLabel label = new JLabel("x");
    label.setBounds(4, 8, 120, 24);
    assertEquals("120x24", property(label, "size"));
    assertEquals("(4,8 120x24)", property(label, "bounds"));
    assertEquals("(4,8)", property(label, "location"));
  }

  @Test
  void insetsRenderWithLabelledEdges() {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createEmptyBorder(1, 2, 3, 4));
    assertEquals("{top:1, left:2, bottom:3, right:4}", property(panel, "insets"));
  }

  @Test
  void borderRendersAsClassNamePlusInsets() {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
    assertEquals("EmptyBorder{top:0, left:8, bottom:0, right:8}", property(panel, "border"));
  }

  @Test
  void componentValuedPropertyRendersAsClassAndId() {
    JPanel parent = new JPanel();
    JLabel child = new JLabel("x");
    parent.setName("theParent");
    parent.add(child);
    String rendered = property(child, "parent");
    assertTrue(rendered.startsWith("JPanel#"), "expected JPanel#<id>, got " + rendered);
    assertTrue(rendered.contains("theParent"), "expected the parent's id, got " + rendered);
  }

  @Test
  void modelsRenderAsSizeSummaries() {
    JList<String> list = new JList<>(new String[] {"a", "b", "c"});
    assertTrue(property(list, "model").endsWith("(size=3)"), property(list, "model"));
    JTable table = new JTable(new DefaultTableModel(new Object[] {"c1", "c2"}, 4));
    assertTrue(property(table, "model").endsWith("(rows=4, cols=2)"), property(table, "model"));
  }

  @Test
  void unrenderableValuesStillFallBackToToString() {
    // A plain object property keeps the old behaviour rather than vanishing.
    JLabel label = new JLabel("x");
    assertNotNull(property(label, "font"));
  }

  // ------------------------------------------------------------------ the @layout preset

  @Test
  void layoutPresetComputesEdgeDistances() {
    JPanel parent = new JPanel(null);
    parent.setSize(200, 100);
    JLabel child = new JLabel("x");
    child.setBounds(8, 4, 100, 20);
    parent.add(child);

    java.util.Map<String, String> layout =
        Introspection.properties(
            child, List.of(Introspection.LAYOUT_PRESET), Introspection.DEFAULT_TIMEOUT);
    // left 8, right 200-0-(8+100)=92, top 4, bottom 100-0-(4+20)=76
    assertEquals("{left:8, right:92, top:4, bottom:76}", layout.get("edgeDistances"));
    assertEquals("(8,4 100x20)", layout.get("bounds"));
    assertEquals("JPanel", layout.get("parent"));
  }

  @Test
  void edgeDistancesAccountForTheParentsBorder() {
    JPanel parent = new JPanel(null);
    parent.setSize(200, 100);
    parent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    JLabel child = new JLabel("x");
    child.setBounds(10, 10, 100, 20);
    parent.add(child);

    java.util.Map<String, String> layout =
        Introspection.properties(
            child, List.of(Introspection.LAYOUT_PRESET), Introspection.DEFAULT_TIMEOUT);
    // Flush against the border on the top-left: 0 away from the parent's inner bounds.
    assertEquals("{left:0, right:80, top:0, bottom:60}", layout.get("edgeDistances"));
  }

  @Test
  void layoutPresetReportsBorderLayoutConstraints() {
    JPanel parent = new JPanel(new java.awt.BorderLayout());
    JLabel child = new JLabel("x");
    parent.add(child, java.awt.BorderLayout.SOUTH);
    java.util.Map<String, String> layout =
        Introspection.properties(
            child, List.of(Introspection.LAYOUT_PRESET), Introspection.DEFAULT_TIMEOUT);
    assertEquals("BorderLayout", layout.get("parentLayout"));
    assertEquals("South", layout.get("constraints"));
  }

  @Test
  void layoutPresetReportsGridBagConstraints() {
    JPanel parent = new JPanel(new java.awt.GridBagLayout());
    JLabel child = new JLabel("x");
    java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
    gbc.gridx = 1;
    gbc.gridy = 2;
    gbc.weightx = 1.0;
    gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
    parent.add(child, gbc);
    String constraints =
        Introspection.properties(
                child, List.of(Introspection.LAYOUT_PRESET), Introspection.DEFAULT_TIMEOUT)
            .get("constraints");
    assertTrue(constraints.contains("gridx=1"), constraints);
    assertTrue(constraints.contains("gridy=2"), constraints);
    assertTrue(constraints.contains("fill=HORIZONTAL"), constraints);
  }

  @Test
  void layoutPresetOnAParentlessComponentStillReports() {
    java.util.Map<String, String> layout =
        Introspection.properties(
            new JLabel("x"), List.of(Introspection.LAYOUT_PRESET), Introspection.DEFAULT_TIMEOUT);
    assertEquals("null", layout.get("parent"));
    assertNull(layout.get("edgeDistances"));
  }

  // ------------------------------------------------------------------ get_text

  private static Introspection.TextRead read(
      java.awt.Component component, int offset, int maxChars) {
    return Introspection.readText(
        component, offset, maxChars, false, Introspection.DEFAULT_TIMEOUT);
  }

  @Test
  void readTextReturnsTheWholeDocumentUncapped() {
    String body = "x".repeat(1000);
    Introspection.TextRead result = read(new JTextArea(body), 0, 4000);
    assertEquals(1000, result.text().length());
    assertEquals(1000, result.length());
    assertFalse(result.truncated());
    assertEquals("document", result.source());
  }

  @Test
  void readTextPagesWithOffsetAndReportsTheFullLength() {
    JTextArea area = new JTextArea("abcdefghij");
    Introspection.TextRead first = read(area, 0, 4);
    assertEquals("abcd", first.text());
    assertEquals(10, first.length());
    assertTrue(first.truncated());

    Introspection.TextRead last = read(area, 8, 4);
    assertEquals("ij", last.text());
    assertEquals(8, last.offset());
    assertFalse(last.truncated());
  }

  @Test
  void readTextClampsAnOffsetPastTheEnd() {
    Introspection.TextRead result = read(new JTextArea("abc"), 99, 10);
    assertEquals("", result.text());
    assertEquals(3, result.length());
    assertEquals(3, result.offset());
  }

  @Test
  void readTextAutoDescendsIntoANamedComposite() {
    JPanel composite = new JPanel();
    JTextField field = new JTextField("inner");
    composite.add(field);
    Introspection.TextRead result = read(composite, 0, 100);
    assertEquals("inner", result.text());
    assertEquals(field, result.target());
  }

  @Test
  void readTextFallsBackToDisplayTextForNonTextComponents() {
    Introspection.TextRead result = read(new JButton("Press"), 0, 100);
    assertEquals("Press", result.text());
    assertEquals("button_text", result.source());
  }

  @Test
  void readTextRejectsASelectionReadOnANonTextComponent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Introspection.readText(
                new JButton("Press"), 0, 100, true, Introspection.DEFAULT_TIMEOUT));
  }

  @Test
  void treeTextIsCappedButMatchingSeesTheWholeString() {
    // The 200-char emission cap must not narrow what find_components can match on.
    String body = "y".repeat(300) + "needle";
    JTextArea area = new JTextArea(body);
    assertTrue(snapshot(area).text().length() <= ComponentAddress.TEXT_CAP + 1);
    assertEquals(body.length(), read(area, 0, 4000).length());
  }
}
