package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paul_griffith.swingmcp.core.Bounds;
import io.github.paul_griffith.swingmcp.core.ComponentAddress;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot.Selection;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot.SelectionKind;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot.TextSource;
import io.github.paul_griffith.swingmcp.core.Interactions;
import io.github.paul_griffith.swingmcp.core.WindowSnapshot;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Headless-safe unit tests for {@link SwingTools}: the exposed tool metadata, the terse JSON shapes
 * produced from snapshots, and the no-window error path.
 */
class SwingToolsTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final SwingTools tools = new SwingTools(json);
  private final ToolSupport support = tools.support();

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    return json.readValue(json.writeValueAsString(value), Map.class);
  }

  // ------------------------------------------------------------------ tool metadata (tools/list)

  @Test
  void exposesAllToolsInOrder() {
    List<String> names = tools.specifications().stream().map(spec -> spec.tool().name()).toList();
    assertEquals(
        List.of(
            "list_windows",
            "get_component_tree",
            "find_components",
            "get_items",
            "screenshot",
            "click",
            "set_text",
            "type_text",
            "press_key",
            "select",
            "scroll_to",
            "focus",
            "close_window",
            "to_front",
            "get_text",
            "get_properties",
            "await_idle",
            "wait_for",
            "edt_stack"),
        names);
  }

  @Test
  void everyToolHasATitleDescriptionAndObjectInputSchema() {
    for (McpServerFeatures.SyncToolSpecification spec : tools.specifications()) {
      McpSchema.Tool tool = spec.tool();
      assertFalse(tool.title() == null || tool.title().isBlank(), tool.name() + " title");
      assertFalse(
          tool.description() == null || tool.description().isBlank(), tool.name() + " description");
      assertEquals("object", tool.inputSchema().get("type"), tool.name() + " schema type");
    }
  }

  @Test
  void componentTreeAndScreenshotDeclareTheirArguments() {
    Map<String, Object> tree = properties("get_component_tree");
    assertTrue(tree.keySet().containsAll(List.of("window_id", "component_id", "max_depth")));

    Map<String, Object> screenshot = properties("screenshot");
    assertTrue(
        screenshot
            .keySet()
            .containsAll(
                List.of("window_id", "component_id", "scale", "save_path", "return_image")));

    Map<String, Object> listWindows = properties("list_windows");
    assertTrue(listWindows.keySet().containsAll(List.of("include_hidden", "title")));

    Map<String, Object> find = properties("find_components");
    assertTrue(
        find.keySet()
            .containsAll(
                List.of(
                    "name",
                    "class",
                    "text",
                    "interactable",
                    "window_id",
                    "component_id",
                    "limit")));
  }

  @Test
  void interactionToolsDeclareTheirArguments() {
    assertTrue(
        properties("click")
            .keySet()
            .containsAll(List.of("component_id", "button", "count", "x", "y")));
    assertTrue(properties("set_text").keySet().containsAll(List.of("component_id", "text")));
    assertTrue(properties("type_text").keySet().containsAll(List.of("component_id", "text")));
    assertTrue(
        properties("press_key").keySet().containsAll(List.of("component_id", "key", "count")));
    assertEquals(List.of("key"), required("press_key"));
    assertTrue(
        properties("select")
            .keySet()
            .containsAll(List.of("component_id", "index", "value", "path")));
    assertTrue(
        properties("get_properties").keySet().containsAll(List.of("component_id", "properties")));
    assertTrue(
        properties("await_idle")
            .keySet()
            .containsAll(List.of("timeout_ms", "window_title", "component_id", "state")));
    assertTrue(
        properties("wait_for")
            .keySet()
            .containsAll(
                List.of(
                    "condition",
                    "name",
                    "class",
                    "text",
                    "interactable",
                    "window_id",
                    "component_id",
                    "expect_text",
                    "timeout_ms",
                    "poll_ms")));
    assertEquals(List.of("condition"), required("wait_for"));
    assertEquals(
        List.of("appears", "disappears", "enabled", "text_contains"),
        property("wait_for", "condition").get("enum"));
    assertTrue(properties("edt_stack").keySet().contains("threshold_ms"));
  }

  @Test
  void requiredArgumentsAndEnumsAreDeclaredInSchemas() {
    assertEquals(List.of("component_id"), required("click"));
    assertEquals(List.of("component_id", "text"), required("set_text"));

    Map<String, Object> button = property("click", "button");
    assertEquals(List.of("left", "middle", "right"), button.get("enum"));

    Map<String, Object> path = property("select", "path");
    assertEquals("array", path.get("type"));
    assertEquals(Map.of("type", "string"), path.get("items"));
  }

  // ------------------------------------------------------------------ window JSON shape

  @Test
  void frameWindowOmitsRedundantNameAndFalseFlags() {
    WindowSnapshot frame =
        new WindowSnapshot(
            "mainFrame",
            0,
            WindowSnapshot.Kind.FRAME,
            "swing-mcp demo",
            "mainFrame",
            ComponentAddress.IdKind.NAME,
            new Bounds(10, 20, 560, 420),
            true,
            true,
            true,
            true,
            null,
            false,
            Boolean.FALSE,
            Boolean.FALSE);
    Map<String, Object> m = asMap(support.windowMap(frame));

    assertEquals("mainFrame", m.get("id"));
    assertEquals("frame", m.get("kind"));
    assertEquals("swing-mcp demo", m.get("title"));
    assertFalse(m.containsKey("name"), "name equal to id is omitted");
    assertEquals(
        "name", m.get("idKind"), "a deliberate window name is tagged as a durable locator");
    assertEquals(true, m.get("visible"));
    assertEquals(true, m.get("focused"));
    assertFalse(m.containsKey("modal"));
    assertFalse(m.containsKey("iconified"));
    assertEquals(Map.of("x", 10, "y", 20, "w", 560, "h", 420), m.get("bounds"));
  }

  @Test
  void modalDialogReportsModalityAndModalFlag() {
    WindowSnapshot dialog =
        new WindowSnapshot(
            "detailsDialog",
            1,
            WindowSnapshot.Kind.DIALOG,
            "Details",
            "detailsDialog",
            ComponentAddress.IdKind.NAME,
            new Bounds(0, 0, 300, 200),
            true,
            true,
            false,
            false,
            WindowSnapshot.Modality.APPLICATION_MODAL,
            true,
            null,
            null);
    Map<String, Object> m = asMap(support.windowMap(dialog));

    assertEquals("dialog", m.get("kind"));
    assertEquals(true, m.get("modal"));
    assertEquals("application_modal", m.get("modality"));
    assertFalse(m.containsKey("focused"));
  }

  @Test
  void autoNamedWindowIsFlaggedAsUnstable() {
    // An AWT-generated default name (frame2) is used as the id but is not stable across runs.
    WindowSnapshot autoNamed =
        new WindowSnapshot(
            "frame2",
            2,
            WindowSnapshot.Kind.FRAME,
            "Script Console",
            "frame2",
            ComponentAddress.IdKind.AUTO,
            new Bounds(0, 0, 1200, 700),
            true,
            true,
            false,
            false,
            null,
            false,
            Boolean.FALSE,
            Boolean.FALSE);
    Map<String, Object> m = asMap(support.windowMap(autoNamed));

    assertEquals("frame2", m.get("id"));
    assertEquals(
        "auto", m.get("idKind"), "an AWT default name is tagged auto (not a durable locator)");
    assertFalse(m.containsKey("name"), "the auto name equals the id, so it is not repeated");
  }

  // ------------------------------------------------------------------ component-node JSON shape

  @Test
  void standardButtonNodeIsTerse() {
    ComponentTreeSnapshot button =
        node(
            "openDialogButton",
            "openDialogButton",
            "JButton",
            "javax.swing.JButton",
            "Details...",
            TextSource.BUTTON_TEXT,
            true,
            true,
            false,
            null,
            0,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(button));

    assertEquals("openDialogButton", m.get("id"));
    assertEquals("name", m.get("idKind"), "a uniquely-named component is a durable locator");
    assertFalse(m.containsKey("name"), "name equal to id is not repeated");
    assertEquals("JButton", m.get("class"));
    assertFalse(m.containsKey("className"), "standard javax.swing FQN is omitted");
    assertEquals("Details...", m.get("text"));
    assertFalse(m.containsKey("enabled"), "enabled=true is omitted");
    assertFalse(m.containsKey("focused"));
    assertFalse(m.containsKey("childCount"));
  }

  @Test
  void nonStandardClassKeepsFullyQualifiedName() {
    ComponentTreeSnapshot custom =
        node(
            "w0/MyPanel[0]",
            null,
            "MyPanel",
            "com.example.MyPanel",
            null,
            TextSource.NONE,
            true,
            true,
            false,
            null,
            0,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(custom));
    assertEquals("com.example.MyPanel", m.get("className"));
  }

  @Test
  void structuralIdWithADuplicatedNameSurfacesTheNameButNoIdKind() {
    // A named-but-not-unique component falls back to a structural id; the raw name still helps.
    ComponentTreeSnapshot n =
        new ComponentTreeSnapshot(
            "w0/JButton[1]",
            "dup",
            ComponentAddress.IdKind.STRUCTURAL,
            "JButton",
            "javax.swing.JButton",
            "OK",
            TextSource.BUTTON_TEXT,
            new Bounds(0, 0, 80, 24),
            null,
            true,
            true,
            true,
            true,
            false,
            null,
            0,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(n));

    assertEquals("w0/JButton[1]", m.get("id"));
    assertEquals("dup", m.get("name"), "a name that differs from the structural id is surfaced");
    assertFalse(m.containsKey("idKind"), "a structural id emits no idKind (recognizable by shape)");
  }

  @Test
  void disabledAndHiddenStateIsSurfaced() {
    ComponentTreeSnapshot n =
        node(
            "field",
            "field",
            "JTextField",
            "javax.swing.JTextField",
            null,
            TextSource.NONE, /*visible*/
            false, /*enabled*/
            false, /*focused*/
            false,
            null,
            0,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(n));
    assertEquals(false, m.get("enabled"));
    assertEquals(false, m.get("visible"));
  }

  @Test
  void comboSelectionIsEncoded() {
    Selection selection = new Selection(SelectionKind.COMBO_BOX, false, 1, List.of(), "Designer");
    ComponentTreeSnapshot combo =
        node(
            "roleCombo",
            "roleCombo",
            "JComboBox",
            "javax.swing.JComboBox",
            null,
            TextSource.NONE,
            true,
            true,
            false,
            selection,
            0,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(combo));

    @SuppressWarnings("unchecked")
    Map<String, Object> sel = (Map<String, Object>) m.get("selection");
    assertEquals("combo_box", sel.get("kind"));
    assertEquals(1, sel.get("selectedIndex"));
    assertEquals("Designer", sel.get("selectedText"));
    assertFalse(sel.containsKey("selected"), "combo selection has no toggle flag");
  }

  @Test
  void toggleSelectionIsEncoded() {
    Selection selection = new Selection(SelectionKind.TOGGLE, true, -1, List.of(), null);
    ComponentTreeSnapshot toggle =
        node(
            "agreeCheck",
            "agreeCheck",
            "JCheckBox",
            "javax.swing.JCheckBox",
            "Agree",
            TextSource.BUTTON_TEXT,
            true,
            true,
            false,
            selection,
            0,
            List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> sel = (Map<String, Object>) asMap(support.nodeMap(toggle)).get("selection");
    assertEquals("toggle", sel.get("kind"));
    assertEquals(true, sel.get("selected"));
    assertFalse(sel.containsKey("selectedIndex"));
  }

  @Test
  void depthTruncatedChildrenAreFlaggedWithTrueChildCount() {
    ComponentTreeSnapshot truncated =
        node(
            "panel",
            "panel",
            "JPanel",
            "javax.swing.JPanel",
            null,
            TextSource.NONE,
            true,
            true,
            false,
            null, /*childCount*/
            3,
            List.of());
    Map<String, Object> m = asMap(support.nodeMap(truncated));
    assertEquals(3, m.get("childCount"));
    assertEquals(true, m.get("childrenTruncated"));
    assertFalse(m.containsKey("children"));
  }

  @Test
  void presentChildrenAreNestedWithoutTruncationFlag() {
    ComponentTreeSnapshot child =
        node(
            "child",
            "child",
            "JButton",
            "javax.swing.JButton",
            "Go",
            TextSource.BUTTON_TEXT,
            true,
            true,
            false,
            null,
            0,
            List.of());
    ComponentTreeSnapshot root =
        node(
            "root",
            "root",
            "JPanel",
            "javax.swing.JPanel",
            null,
            TextSource.NONE,
            true,
            true,
            false,
            null,
            1,
            List.of(child));
    Map<String, Object> m = asMap(support.nodeMap(root));

    assertInstanceOf(List.class, m.get("children"));
    assertEquals(1, ((List<?>) m.get("children")).size());
    assertFalse(m.containsKey("childrenTruncated"));
  }

  // ------------------------------------------------------------------ dispatch delta JSON shape

  @Test
  void dispatchMapRendersAKnownWindowDelta() {
    Interactions.WindowDelta delta =
        new Interactions.WindowDelta(
            List.of(
                new Interactions.WindowRef(
                    "detailsDialog", "dialog", "Details", new Bounds(10, 20, 300, 200)),
                new Interactions.WindowRef(
                    "rowPopupMenu", "popup", null, new Bounds(0, 0, 80, 40))),
            List.of("w3"));
    Map<String, Object> m = asMap(support.dispatchMap("click", "btn", true, delta));

    assertEquals(true, m.get("dispatched"));
    assertEquals(true, m.get("edtSettled"));
    assertFalse(m.containsKey("deltaUnknown"));
    assertFalse(m.containsKey("note"), "a known delta needs no follow-up note");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> opened = (List<Map<String, Object>>) m.get("opened");
    assertEquals(2, opened.size());
    assertEquals("detailsDialog", opened.get(0).get("id"));
    assertEquals("dialog", opened.get(0).get("kind"));
    assertEquals("Details", opened.get(0).get("title"));
    assertEquals(Map.of("x", 10, "y", 20, "w", 300, "h", 200), opened.get(0).get("bounds"));
    assertEquals("popup", opened.get(1).get("kind"));
    assertFalse(opened.get(1).containsKey("title"), "a null title is omitted");

    assertEquals(List.of("w3"), m.get("closed"));
  }

  @Test
  void dispatchMapOmitsAnEmptyDelta() {
    Interactions.WindowDelta empty = new Interactions.WindowDelta(List.of(), List.of());
    Map<String, Object> m = asMap(support.dispatchMap("click", "btn", true, empty));
    assertFalse(m.containsKey("opened"));
    assertFalse(m.containsKey("closed"));
    assertFalse(m.containsKey("deltaUnknown"));
    assertFalse(m.containsKey("note"), "delta present and empty → the note is dropped entirely");
  }

  @Test
  void dispatchMapFlagsAnUnknownDeltaAndKeepsTheFollowUpNote() {
    Map<String, Object> m = asMap(support.dispatchMap("click", "btn", false, null));
    assertEquals(true, m.get("deltaUnknown"));
    assertEquals(false, m.get("edtSettled"));
    assertTrue(
        String.valueOf(m.get("note")).contains("edt_stack"),
        "an unknown delta keeps the busy-EDT follow-up note: " + m);
    assertFalse(m.containsKey("opened"));
    assertFalse(m.containsKey("closed"));
  }

  // ------------------------------------------------------------------ handler behavior (headless)

  @Test
  void listWindowsReturnsAWindowsWrapperObject() {
    Map<String, Object> m = parse(jsonBody(call("list_windows", Map.of())));
    // Headless: no windows exist, so the array is empty — but the wrapper shape holds.
    assertInstanceOf(List.class, m.get("windows"));
    assertTrue(((List<?>) m.get("windows")).isEmpty());
    assertFalse(m.containsKey("hiddenCount"), "no suppressed windows → hiddenCount omitted");
  }

  @Test
  void getComponentTreeWithNoWindowsReturnsAnError() {
    McpSchema.CallToolResult result = call("get_component_tree", Map.of());
    assertEquals(Boolean.TRUE, result.isError());
  }

  @Test
  void screenshotWithNoWindowsReturnsAnError() {
    McpSchema.CallToolResult result = call("screenshot", Map.of());
    assertEquals(Boolean.TRUE, result.isError());
  }

  @Test
  void screenshotRejectsARelativeSavePath() {
    // save_path validation runs before target resolution, so this holds even headless.
    McpSchema.CallToolResult result = call("screenshot", Map.of("save_path", "relative/shot.png"));
    assertEquals(Boolean.TRUE, result.isError());
    String body = textOf(result);
    assertTrue(body.contains("\"error\":\"invalid_argument\""), body);
    assertTrue(body.contains("absolute"), body);
  }

  // ------------------------------------------------------------------ interaction arg validation

  @Test
  void clickWithoutComponentIdIsAnError() {
    McpSchema.CallToolResult result = call("click", Map.of());
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("component_id is required"));
  }

  @Test
  void clickWithUnknownButtonIsAnError() {
    McpSchema.CallToolResult result =
        call("click", Map.of("component_id", "someId", "button", "sideways"));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("unknown button"));
  }

  @Test
  void setTextWithoutTextIsAnError() {
    McpSchema.CallToolResult result = call("set_text", Map.of("component_id", "someId"));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("text is required"));
  }

  @Test
  void pressKeyWithoutKeyIsAnError() {
    McpSchema.CallToolResult result = call("press_key", Map.of("component_id", "someId"));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("key is required"));
  }

  @Test
  void pressKeyWithUnknownKeyIsAnError() {
    McpSchema.CallToolResult result =
        call("press_key", Map.of("component_id", "someId", "key", "wobble"));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("unknown key"));
  }

  @Test
  void pressKeyWithUnknownModifierIsAnError() {
    McpSchema.CallToolResult result =
        call("press_key", Map.of("component_id", "someId", "key", "hyper+a"));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(textOf(result).contains("unknown modifier"));
  }

  @Test
  void selectRequiresExactlyOneSelector() {
    McpSchema.CallToolResult none = call("select", Map.of("component_id", "someId"));
    assertEquals(Boolean.TRUE, none.isError());
    assertTrue(textOf(none).contains("exactly one"));

    McpSchema.CallToolResult two =
        call("select", Map.of("component_id", "someId", "index", 0, "value", "x"));
    assertEquals(Boolean.TRUE, two.isError());
  }

  // ------------------------------------------------------------------ diagnostics JSON shapes
  // (headless)

  @Test
  void edtStackReturnsAVerdictAndStack() {
    Map<String, Object> m = parse(jsonBody(call("edt_stack", Map.of())));
    assertTrue(
        List.of("responsive", "blocked").contains(m.get("verdict")),
        "verdict: " + m.get("verdict"));
    assertTrue(m.containsKey("latencyMs"));
    assertEquals(200, m.get("thresholdMs"));
    assertInstanceOf(List.class, m.get("stack"));
  }

  @Test
  void waitForDisappearsInAnUnresolvableScopeIsMetImmediately() {
    // Headless: the scope never resolves, which counts as zero matches — met for `disappears`.
    Map<String, Object> m =
        parse(
            jsonBody(
                call(
                    "wait_for",
                    Map.of(
                        "condition", "disappears",
                        "name", "anything",
                        "component_id", "noSuchScope",
                        "timeout_ms", 2000))));
    assertEquals(true, m.get("met"));
    assertFalse(m.containsKey("timedOut"));
    assertFalse(m.containsKey("match"), "disappears has no match to return");
  }

  @Test
  void waitForTimesOutWithMetFalseNotAnError() {
    Map<String, Object> m =
        parse(
            jsonBody(
                call(
                    "wait_for",
                    Map.of(
                        "condition",
                        "appears",
                        "name",
                        "neverMatches",
                        "timeout_ms",
                        200,
                        "poll_ms",
                        100))));
    assertEquals(false, m.get("met"));
    assertEquals(true, m.get("timedOut"));
    assertEquals(0, m.get("lastCount"));
  }

  @Test
  void waitForRejectsAnUnknownConditionAndAMissingFilter() {
    McpSchema.CallToolResult unknown =
        call("wait_for", Map.of("condition", "vanishes", "name", "x"));
    assertEquals(Boolean.TRUE, unknown.isError());
    assertTrue(textOf(unknown).contains("unknown condition"));

    McpSchema.CallToolResult noFilter = call("wait_for", Map.of("condition", "appears"));
    assertEquals(Boolean.TRUE, noFilter.isError());
    assertTrue(textOf(noFilter).contains("at least one filter"));

    McpSchema.CallToolResult noExpect =
        call("wait_for", Map.of("condition", "text_contains", "name", "x"));
    assertEquals(Boolean.TRUE, noExpect.isError());
    assertTrue(textOf(noExpect).contains("expect_text"));
  }

  @Test
  void awaitIdleWithNoConditionFlushesAndReports() {
    Map<String, Object> m = parse(jsonBody(call("await_idle", Map.of("timeout_ms", 1000))));
    assertEquals(true, m.get("met"));
    assertEquals(true, m.get("flushed"));
    assertTrue(m.containsKey("elapsedMs"));
    assertFalse(m.containsKey("condition"));
  }

  // ------------------------------------------------------------------ helpers

  @SuppressWarnings("unchecked")
  private List<String> required(String toolName) {
    McpSchema.Tool tool =
        tools.specifications().stream()
            .map(McpServerFeatures.SyncToolSpecification::tool)
            .filter(t -> t.name().equals(toolName))
            .findFirst()
            .orElseThrow();
    Object required = tool.inputSchema().get("required");
    return required == null ? List.of() : (List<String>) required;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> property(String toolName, String propertyName) {
    return (Map<String, Object>) properties(toolName).get(propertyName);
  }

  private static String jsonBody(McpSchema.CallToolResult result) {
    assertFalse(Boolean.TRUE.equals(result.isError()), "unexpected tool error: " + textOf(result));
    return textOf(result);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(String jsonText) {
    return json.readValue(jsonText, Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> properties(String toolName) {
    McpSchema.Tool tool =
        tools.specifications().stream()
            .map(McpServerFeatures.SyncToolSpecification::tool)
            .filter(t -> t.name().equals(toolName))
            .findFirst()
            .orElseThrow();
    return (Map<String, Object>) tool.inputSchema().get("properties");
  }

  private McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
    return tools.specifications().stream()
        .filter(spec -> spec.tool().name().equals(toolName))
        .findFirst()
        .orElseThrow()
        .callHandler()
        .apply(null, McpSchema.CallToolRequest.builder(toolName).arguments(args).build());
  }

  private static String textOf(McpSchema.CallToolResult result) {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static ComponentTreeSnapshot node(
      String id,
      String name,
      String simpleClass,
      String className,
      String text,
      TextSource source,
      boolean visible,
      boolean enabled,
      boolean focused,
      Selection selection,
      int childCount,
      List<ComponentTreeSnapshot> children) {
    ComponentAddress.IdKind idKind =
        (name != null && name.equals(id))
            ? ComponentAddress.IdKind.NAME
            : ComponentAddress.IdKind.STRUCTURAL;
    return new ComponentTreeSnapshot(
        id,
        name,
        idKind,
        simpleClass,
        className,
        text,
        source,
        new Bounds(0, 0, 100, 30),
        null,
        visible, /*showing*/
        visible,
        enabled,
        /*focusable*/ true,
        focused,
        selection,
        childCount,
        children);
  }
}
