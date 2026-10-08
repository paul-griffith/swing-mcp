package io.github.paul_griffith.swingmcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.modelcontextprotocol.spec.McpSchema;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The core end-to-end suite: forks the real {@code demo-java} app with the shaded agent attached,
 * connects with the MCP Java SDK client, and drives every tool against the demo's known, named
 * components. Guarded with {@code assumeFalse(GraphicsEnvironment.isHeadless())} so it skips
 * cleanly with no display (runs headed locally / under Xvfb on Linux CI). One process is shared
 * across the scenarios; each mutating scenario sets and reads its own component, and the modal
 * round-trip cleans up after itself.
 */
@Tag("headed")
class DemoJavaEndToEndTest {

  private static final List<String> EXPECTED_TOOLS =
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
          "edt_stack");

  private static ForkedApp app;
  private static Mcp mcp;

  @BeforeAll
  static void startAndConnect() {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    app = ForkedApp.launchWithAgent(ForkedApp.DEMO_JAVA_MAIN, ForkedApp.demoJavaClasspath());
    int port = app.awaitPort(Duration.ofSeconds(30));
    mcp = Mcp.connect(port);
    // Wait for the demo frame to actually be up before asserting against it.
    JsonNode awaited =
        mcp.call("await_idle", Map.of("window_title", "swing-mcp demo", "timeout_ms", 10_000));
    assertTrue(awaited.get("met").asBoolean(), "demo frame did not appear:\n" + app.logSnapshot());
  }

  @AfterAll
  static void tearDown() {
    if (mcp != null) {
      mcp.close();
    }
    if (app != null) {
      app.close();
    }
  }

  @Test
  void initializeAndListsExactlyTheExpectedTools() {
    List<String> names = mcp.toolNames();
    assertEquals(EXPECTED_TOOLS.size(), names.size(), "unexpected tool count, got " + names);
    assertTrue(names.containsAll(EXPECTED_TOOLS), "missing tools; got " + names);
  }

  @Test
  void serverVersionMatchesTheProjectVersion() {
    // Version alignment: serverInfo version comes from the shaded jar's Implementation-Version,
    // which is set from the Gradle project version.
    assertEquals("swing-mcp", mcp.serverName());
    assertEquals(System.getProperty("swingmcp.projectVersion"), mcp.serverVersion());
  }

  @Test
  void listWindowsReportsTheDemoFrame() {
    JsonNode result = mcp.call("list_windows", Map.of());
    JsonNode windows = result.get("windows");
    assertNotNull(windows, "list_windows should return {windows: [...]}: " + result);
    assertTrue(windows.isArray(), "windows should be an array");
    JsonNode frame = arrayItemWhere(windows, "id", "mainFrame");
    assertNotNull(frame, "mainFrame not found in " + windows);
    assertEquals("frame", frame.get("kind").asString());
    assertEquals("swing-mcp demo", frame.get("title").asString());
    assertTrue(frame.get("showing").asBoolean());
    assertTrue(frame.get("bounds").get("w").asInt() > 0);
  }

  @Test
  void componentTreeExposesNamedIds() {
    JsonNode tree = mcp.call("get_component_tree", Map.of("window_id", "mainFrame"));
    assertEquals("mainFrame", tree.get("id").asString());

    JsonNode button = findById(tree, "openDialogButton");
    assertNotNull(button, "openDialogButton not found in tree");
    assertEquals("JButton", button.get("class").asString());
    assertEquals("Details...", button.get("text").asString());

    assertNotNull(findById(tree, "roleCombo"), "roleCombo not found");
    assertNotNull(findById(tree, "peopleTable"), "peopleTable not found");
  }

  @Test
  void componentTreeHonoursMaxDepthTruncation() {
    JsonNode shallow =
        mcp.call("get_component_tree", Map.of("window_id", "mainFrame", "max_depth", 1));
    // The frame's direct child (the root pane) has descendants, so at depth 1 they are truncated.
    assertTrue(
        hasTruncatedNode(shallow),
        "expected a childrenTruncated node at max_depth=1, tree was " + shallow);
    // A generous depth exposes the deep, named button that depth 1 could never reach.
    JsonNode deep =
        mcp.call("get_component_tree", Map.of("window_id", "mainFrame", "max_depth", 20));
    assertNotNull(
        findById(deep, "openDialogButton"), "openDialogButton should be reachable at depth 20");
  }

  @Test
  void screenshotDecodesToAPngWithMetadata() throws Exception {
    McpSchema.CallToolResult result = mcp.callRaw("screenshot", Map.of("window_id", "mainFrame"));
    assertFalse(
        Boolean.TRUE.equals(result.isError()), "screenshot errored: " + Mcp.firstText(result));

    McpSchema.ImageContent image = Mcp.firstImage(result);
    assertNotNull(image, "no image content returned");
    assertEquals("image/png", image.mimeType());

    BufferedImage png =
        ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.data())));
    assertNotNull(png, "image content was not decodable PNG");
    assertTrue(
        png.getWidth() > 100 && png.getHeight() > 100,
        "unexpected screenshot dimensions " + png.getWidth() + "x" + png.getHeight());
    assertTrue(
        distinctColors(png) > 25, "screenshot looks blank; distinct colors=" + distinctColors(png));

    // Supplementary metadata text so an LLM can map logical tree bounds onto screenshot pixels.
    String note = Mcp.firstText(result);
    assertNotNull(note, "no metadata text content");
    assertTrue(
        note.contains("logical size")
            && note.contains("pixel size")
            && note.contains("effective scale"),
        "metadata text missing HiDPI fields: " + note);
  }

  @Test
  void listWindowsFiltersByTitleAndReturnsOnlyShowingWindowsByDefault() {
    JsonNode filtered = mcp.call("list_windows", Map.of("title", "swing-mcp")).get("windows");
    assertNotNull(arrayItemWhere(filtered, "id", "mainFrame"), "title filter missed: " + filtered);
    for (JsonNode w : filtered) {
      assertTrue(w.get("showing").asBoolean(), "default list must be showing-only: " + w);
    }
    JsonNode none = mcp.call("list_windows", Map.of("title", "no-such-window-title"));
    assertTrue(
        none.get("windows").isEmpty(),
        "a non-matching title filter should return nothing: " + none);
  }

  @Test
  void screenshotSavePathWritesAFullResolutionPngAndReturnsTextOnly() throws Exception {
    Path dir = Files.createTempDirectory("swingmcp-e2e");
    Path target = dir.resolve("nested/shot.png"); // the parent dir must be created by the tool
    McpSchema.CallToolResult result =
        mcp.callRaw("screenshot", Map.of("window_id", "mainFrame", "save_path", target.toString()));
    assertFalse(
        Boolean.TRUE.equals(result.isError()), "screenshot errored: " + Mcp.firstText(result));
    assertNull(Mcp.firstImage(result), "no image content expected when save_path is set");

    String note = Mcp.firstText(result);
    assertNotNull(note, "text metadata expected");
    assertTrue(note.contains("saved to " + target), "saved path missing from note: " + note);

    byte[] bytes = Files.readAllBytes(target);
    assertTrue(bytes.length > 8, "file too small to be a PNG");
    assertEquals(0x89, bytes[0] & 0xFF, "PNG magic");
    assertEquals('P', bytes[1]);
    assertEquals('N', bytes[2]);
    assertEquals('G', bytes[3]);

    BufferedImage png = ImageIO.read(target.toFile());
    assertNotNull(png, "saved file was not decodable PNG");
    assertTrue(png.getWidth() > 100 && png.getHeight() > 100, "unexpected saved dimensions");
    assertTrue(
        note.contains("pixel size: " + png.getWidth() + "x" + png.getHeight()),
        "reported pixel size should match the file: " + note);
  }

  @Test
  void screenshotSavePathWithReturnImageAttachesTheImageToo() throws Exception {
    Path target = Files.createTempDirectory("swingmcp-e2e").resolve("both.png");
    McpSchema.CallToolResult result =
        mcp.callRaw(
            "screenshot",
            Map.of("window_id", "mainFrame", "save_path", target.toString(), "return_image", true));
    assertFalse(
        Boolean.TRUE.equals(result.isError()), "screenshot errored: " + Mcp.firstText(result));
    assertTrue(Files.exists(target), "the file should still be written");
    assertNotNull(Mcp.firstImage(result), "return_image=true should attach the image content");
  }

  @Test
  void screenshotRejectsARelativeSavePath() {
    McpSchema.CallToolResult result =
        mcp.callRaw(
            "screenshot", Map.of("window_id", "mainFrame", "save_path", "relative/shot.png"));
    assertTrue(Boolean.TRUE.equals(result.isError()), "a relative save_path should be refused");
    String body = Mcp.firstText(result);
    assertTrue(
        body.contains("\"error\":\"invalid_argument\""),
        "expected a structured invalid_argument error, got: " + body);
  }

  @Test
  void findComponentsZeroMatchesReportsScannedAndAHint() {
    // A near-miss name query: shares the "nameF" prefix with nameField but matches nothing.
    JsonNode nearMiss = mcp.call("find_components", Map.of("name", "nameFld"));
    assertEquals(0, nearMiss.get("count").asInt(), "" + nearMiss);
    assertTrue(nearMiss.get("scanned").asInt() > 0, "scanned expected: " + nearMiss);
    assertTrue(
        nearMiss.get("hint").asString().contains("nameField"),
        "near-miss hint should suggest nameField: " + nearMiss);

    // An empty scope (a leaf component) is called out as having no descendants.
    JsonNode emptyScope =
        mcp.call(
            "find_components", Map.of("component_id", "openDialogButton", "name", "zzz-nothing"));
    assertEquals(0, emptyScope.get("count").asInt());
    assertTrue(
        emptyScope.get("hint").asString().contains("no descendants"),
        "empty-scope hint expected: " + emptyScope);
  }

  @Test
  void clickReportsTheOpenedModelessDialogAndEscapeCloseReportsClosed() {
    // Opening a modeless dialog: the click's own result carries the new window in opened[].
    JsonNode click = mcp.call("click", Map.of("component_id", "openNotesButton"));
    assertTrue(click.get("dispatched").asBoolean());
    JsonNode opened = click.get("opened");
    assertNotNull(opened, "the click should report the dialog it opened: " + click);
    JsonNode dialog = arrayItemWhere(opened, "id", "notesDialog");
    assertNotNull(dialog, "notesDialog missing from opened[]: " + click);
    assertEquals("dialog", dialog.get("kind").asString());
    assertEquals("Notes", dialog.get("title").asString());
    assertTrue(dialog.get("bounds").get("w").asInt() > 0, "opened[] entries carry bounds");
    assertFalse(click.has("note"), "a known delta needs no follow-up note: " + click);

    // Escape (a window-scoped binding in the demo) dismisses it; the key press's delta reports
    // the close.
    JsonNode esc =
        mcp.call("press_key", Map.of("component_id", "notesCloseButton", "key", "escape"));
    JsonNode closed = esc.get("closed");
    assertNotNull(closed, "the escape should report the dialog it closed: " + esc);
    assertTrue(containsString(closed, "notesDialog"), "notesDialog missing from closed[]: " + esc);
  }

  @Test
  void clickReportsAnOpenedPopupMenuAndAnOutsideClickReportsItClosed() {
    // A JPopupMenu is not a top-level window; the delta still reports it (kind "popup").
    JsonNode click = mcp.call("click", Map.of("component_id", "openPopupButton"));
    JsonNode opened = click.get("opened");
    assertNotNull(opened, "the click should report the popup it opened: " + click);
    JsonNode popup = arrayItemWhere(opened, "id", "rowPopupMenu");
    assertNotNull(popup, "rowPopupMenu missing from opened[]: " + click);
    assertEquals("popup", popup.get("kind").asString());

    // A click elsewhere auto-dismisses the popup (the LAF's mouse grabber); the dismissing
    // click's delta reports the close.
    JsonNode outside = mcp.call("click", Map.of("component_id", "nameField"));
    JsonNode closed = outside.get("closed");
    assertNotNull(closed, "the outside click should report the popup closing: " + outside);
    assertTrue(
        containsString(closed, "rowPopupMenu"), "rowPopupMenu missing from closed[]: " + outside);
  }

  @Test
  void waitForObservesADelayedAppearanceAndDisappearance() {
    // The demo's Slow Toggle adds slowTaskLabel via a 600 ms Timer — an async change the click's
    // delta cannot see. wait_for polls it into view.
    mcp.call("click", Map.of("component_id", "slowToggleButton"));
    JsonNode appeared =
        mcp.call(
            "wait_for",
            Map.of("condition", "appears", "name", "slowTaskLabel", "timeout_ms", 5000));
    assertTrue(appeared.get("met").asBoolean(), "the delayed label never appeared: " + appeared);
    assertNotNull(appeared.get("match"), "appears should return the match: " + appeared);
    assertEquals("slowTaskLabel", appeared.get("match").get("id").asString());
    assertTrue(appeared.get("elapsedMs").asLong() >= 0);

    // A second toggle removes it (again Timer-delayed); wait for the disappearance.
    mcp.call("click", Map.of("component_id", "slowToggleButton"));
    JsonNode gone =
        mcp.call(
            "wait_for",
            Map.of("condition", "disappears", "name", "slowTaskLabel", "timeout_ms", 5000));
    assertTrue(gone.get("met").asBoolean(), "the label never disappeared: " + gone);
    assertFalse(gone.has("match"), "disappears has no match to return: " + gone);
  }

  @Test
  void waitForTimesOutWithMetFalseAndLastCount() {
    JsonNode r =
        mcp.call(
            "wait_for",
            Map.of(
                "condition",
                "appears",
                "name",
                "zzz-never-exists",
                "timeout_ms",
                500,
                "poll_ms",
                100));
    assertFalse(r.get("met").asBoolean(), "a missing component should time out: " + r);
    assertTrue(r.get("timedOut").asBoolean());
    assertEquals(0, r.get("lastCount").asInt());
    assertTrue(r.get("elapsedMs").asLong() >= 500, "should wait out the timeout: " + r);
  }

  @Test
  void modalDialogRoundTrip() {
    // 1. Click the opener; it returns promptly even though it opens an application-modal dialog.
    // Even for a MODAL dialog the click's result carries the delta: the nested event loop keeps
    // pumping the queue, so the bounded after-snapshot still runs.
    JsonNode click = mcp.call("click", Map.of("component_id", "openDialogButton"));
    assertTrue(click.get("dispatched").asBoolean());
    JsonNode openedModal = arrayItemWhere(click.get("opened"), "id", "detailsDialog");
    assertNotNull(openedModal, "the modal dialog should be reported in opened[]: " + click);
    assertEquals("dialog", openedModal.get("kind").asString());

    // 2. Await the dialog by title, then confirm it shows up in list_windows.
    JsonNode awaited =
        mcp.call("await_idle", Map.of("window_title", "Details", "timeout_ms", 5000));
    assertTrue(awaited.get("met").asBoolean(), "the Details dialog never appeared");

    JsonNode windows = mcp.call("list_windows", Map.of()).get("windows");
    JsonNode dialog = arrayItemWhere(windows, "id", "detailsDialog");
    assertNotNull(dialog, "detailsDialog not in list_windows: " + windows);
    assertEquals("dialog", dialog.get("kind").asString());
    assertTrue(dialog.get("showing").asBoolean(), "dialog should be showing");
    assertTrue(dialog.get("modal").asBoolean(), "dialog should be modal");

    // 3. Inspect the dialog subtree and find its close button.
    JsonNode dialogTree = mcp.call("get_component_tree", Map.of("component_id", "detailsDialog"));
    assertNotNull(
        findById(dialogTree, "detailsCloseButton"), "close button missing from dialog tree");

    // 4. Close it and confirm it is gone — the closing click's own delta already reports it.
    JsonNode close = mcp.call("click", Map.of("component_id", "detailsCloseButton"));
    assertTrue(
        containsString(close.get("closed"), "detailsDialog"),
        "the dismissing click should report the dialog in closed[]: " + close);
    boolean gone =
        pollUntil(
            () -> {
              JsonNode w =
                  arrayItemWhere(
                      mcp.call("list_windows", Map.of()).get("windows"), "id", "detailsDialog");
              return w == null || !w.get("showing").asBoolean();
            },
            Duration.ofSeconds(5));
    assertTrue(gone, "detailsDialog should be gone after clicking its close button");

    // 5. The disposed dialog lingers as a non-showing window: the default list suppresses it and
    // reports it in hiddenCount; include_hidden surfaces it again.
    JsonNode defaults = mcp.call("list_windows", Map.of());
    assertNull(
        arrayItemWhere(defaults.get("windows"), "id", "detailsDialog"),
        "a hidden window should be suppressed by default: " + defaults);
    assertTrue(
        defaults.has("hiddenCount") && defaults.get("hiddenCount").asInt() > 0,
        "expected a hiddenCount field: " + defaults);

    JsonNode full = mcp.call("list_windows", Map.of("include_hidden", true));
    JsonNode lingering = arrayItemWhere(full.get("windows"), "id", "detailsDialog");
    assertNotNull(lingering, "include_hidden should surface the disposed dialog: " + full);
    assertFalse(lingering.get("showing").asBoolean());
    assertFalse(full.has("hiddenCount"), "include_hidden suppresses nothing: " + full);
  }

  @Test
  void setTextThenReadBackViaProperties() {
    String value = "Grace Hopper";
    JsonNode set = mcp.call("set_text", Map.of("component_id", "nameField", "text", value));
    assertTrue(set.get("ok").asBoolean());
    assertEquals(value.length(), set.get("length").asInt());

    JsonNode props =
        mcp.call(
            "get_properties", Map.of("component_id", "nameField", "properties", List.of("text")));
    assertEquals(value, props.get("properties").get("text").asString());
  }

  @Test
  void typeTextFiresIntoTheField() {
    String value = "ada@example.com";
    mcp.call("type_text", Map.of("component_id", "emailField", "text", value));

    JsonNode props =
        mcp.call(
            "get_properties", Map.of("component_id", "emailField", "properties", List.of("text")));
    assertEquals(value, props.get("properties").get("text").asString());
  }

  @Test
  void selectComboByValue() {
    mcp.call("select", Map.of("component_id", "roleCombo", "value", "Designer"));

    JsonNode tree = mcp.call("get_component_tree", Map.of("window_id", "mainFrame"));
    JsonNode combo = findById(tree, "roleCombo");
    assertNotNull(combo);
    assertEquals("Designer", combo.get("selection").get("selectedText").asString());
  }

  @Test
  void findComponentsLocatesByNameSubstring() {
    JsonNode result = mcp.call("find_components", Map.of("name", "field"));
    assertTrue(
        result.get("count").asInt() >= 4, "expected the named *field components; got " + result);
    JsonNode matches = result.get("matches");
    assertNotNull(arrayItemWhere(matches, "id", "nameField"), "nameField missing from " + matches);
    assertNotNull(
        arrayItemWhere(matches, "id", "filterField"), "filterField missing from " + matches);
  }

  @Test
  void findComponentsInteractableWithinAWindow() {
    JsonNode result =
        mcp.call("find_components", Map.of("window_id", "mainFrame", "interactable", true));
    JsonNode matches = result.get("matches");
    assertNotNull(
        arrayItemWhere(matches, "id", "openDialogButton"), "button missing from " + matches);
    assertNotNull(arrayItemWhere(matches, "id", "roleCombo"), "combo missing from " + matches);
    assertNotNull(arrayItemWhere(matches, "id", "peopleTable"), "table missing from " + matches);
  }

  @Test
  void setTextAutoDescendsFromNamedContainer() {
    // filterField is a JPanel (the named handle), wrapping an unnamed JTextField.
    JsonNode set = mcp.call("set_text", Map.of("component_id", "filterField", "text", "widget"));
    assertTrue(set.get("ok").asBoolean());
    assertTrue(set.has("resolvedId"), "set_text should report the field it descended to: " + set);
    String resolvedId = set.get("resolvedId").asString();
    assertNotEquals(
        "filterField", resolvedId, "resolvedId should be the inner field, not the container");

    JsonNode props =
        mcp.call(
            "get_properties", Map.of("component_id", resolvedId, "properties", List.of("text")));
    assertEquals("widget", props.get("properties").get("text").asString());
  }

  @Test
  void typeTextRejectsANonTextContainer() {
    // buttonBar is a JPanel holding a button — no text field to descend to.
    McpSchema.CallToolResult result =
        mcp.callRaw("type_text", Map.of("component_id", "buttonBar", "text", "nope"));
    assertTrue(
        Boolean.TRUE.equals(result.isError()), "typing at a non-text container should error");
    assertTrue(
        Mcp.firstText(result).contains("text"), "error should explain the missing text target");
  }

  @Test
  void clickHitsAnOverlayRegionOnlyWithExplicitCoordinates() {
    // The clearable field clears itself only when clicked in its right-edge ✕ zone.
    mcp.call("set_text", Map.of("component_id", "clearableField", "text", "clear me"));

    // A centered click misses the ✕ zone (finding #3's failure mode) — text survives.
    mcp.call("click", Map.of("component_id", "clearableField"));
    JsonNode afterCenter =
        mcp.call(
            "get_properties",
            Map.of("component_id", "clearableField", "properties", List.of("text")));
    assertEquals(
        "clear me",
        afterCenter.get("properties").get("text").asString(),
        "a centered click should miss the edge ✕ zone");

    // A click near the right edge lands in the zone and clears it (the coordinate fix).
    JsonNode found = mcp.call("find_components", Map.of("name", "clearableField"));
    int width =
        arrayItemWhere(found.get("matches"), "id", "clearableField").get("bounds").get("w").asInt();
    mcp.call("click", Map.of("component_id", "clearableField", "x", width - 6, "y", 10));

    boolean cleared =
        pollUntil(
            () ->
                mcp.call(
                        "get_properties",
                        Map.of("component_id", "clearableField", "properties", List.of("text")))
                    .get("properties")
                    .get("text")
                    .asString()
                    .isEmpty(),
            Duration.ofSeconds(3));
    assertTrue(cleared, "a click at the right edge should hit the ✕ zone and clear the field");
  }

  @Test
  void clickWarnsWhenTargetHasNoListeners() {
    // formPanel is a plain container: the click is dispatched but flagged as likely-ineffective.
    JsonNode result = mcp.call("click", Map.of("component_id", "formPanel"));
    assertTrue(result.get("dispatched").asBoolean());
    assertTrue(
        result.has("warning"), "a click on an inert container should carry a warning: " + result);
  }

  @Test
  void edtStackReportsResponsive() {
    JsonNode status = mcp.call("edt_stack", Map.of());
    assertEquals("responsive", status.get("verdict").asString());
    assertTrue(status.get("responsive").asBoolean());
    assertTrue(
        status.get("stack").isArray() && !status.get("stack").isEmpty(), "expected an EDT stack");
  }

  @Test
  void bogusComponentIdYieldsAStructuredToolError() {
    McpSchema.CallToolResult result =
        mcp.callRaw("get_properties", Map.of("component_id", "noSuchComponent"));
    assertTrue(Boolean.TRUE.equals(result.isError()), "expected an error result");
    String body = Mcp.firstText(result);
    assertNotNull(body);
    // Structured error shape: {error, message, hint?} — clients branch on the stable code.
    assertTrue(
        body.contains("\"error\":\"component_not_found\""),
        "expected a structured component_not_found error, got: " + body);
    assertTrue(
        body.contains("\"hint\""), "a component_not_found error should carry a hint: " + body);
  }

  @Test
  void getItemsReadsTheTableRendererAware() {
    JsonNode data = mcp.call("get_items", Map.of("component_id", "peopleTable"));
    assertEquals("table", data.get("kind").asString());
    assertTrue(data.get("columns").toString().contains("Name"), "expected a Name column: " + data);
    assertTrue(data.get("count").asInt() >= 3, "expected the demo's people rows: " + data);
    assertEquals(
        "Ada Lovelace",
        data.get("rows").get(0).get(0).asString(),
        "first cell should be the rendered person name");
  }

  @Test
  void focusScrollAndToFrontSucceed() {
    assertTrue(mcp.call("focus", Map.of("component_id", "nameField")).get("ok").asBoolean());
    assertTrue(mcp.call("scroll_to", Map.of("component_id", "peopleTable")).get("ok").asBoolean());
    assertTrue(mcp.call("to_front", Map.of("component_id", "mainFrame")).get("ok").asBoolean());
  }

  @Test
  void awaitIdleTextConditionObservesAFieldUpdate() {
    mcp.call("set_text", Map.of("component_id", "nameField", "text", "Zaphod Beeblebrox"));
    JsonNode r =
        mcp.call(
            "await_idle",
            Map.of("component_id", "nameField", "text", "Zaphod", "timeout_ms", 3000));
    assertTrue(r.get("met").asBoolean(), "should observe the field's updated text: " + r);
  }

  // close_window is validated in the headed core test InteractionsWindowIntegrationTest, using a
  // fresh uniquely-named frame — the demo's single reusable detailsDialog can't host a second
  // dialog-opening scenario (a disposed one lingers in Window.getWindows() and makes the name
  // ambiguous, exactly as InteractionsModalIntegrationTest documents).

  // ------------------------------------------------------------------ helpers

  /** Depth-first search for a tree node whose {@code id} equals {@code id}. */
  static JsonNode findById(JsonNode node, String id) {
    if (node == null) {
      return null;
    }
    JsonNode idNode = node.get("id");
    if (idNode != null && id.equals(idNode.asString())) {
      return node;
    }
    JsonNode children = node.get("children");
    if (children != null && children.isArray()) {
      for (JsonNode child : children) {
        JsonNode hit = findById(child, id);
        if (hit != null) {
          return hit;
        }
      }
    }
    return null;
  }

  private static boolean hasTruncatedNode(JsonNode node) {
    JsonNode truncated = node.get("childrenTruncated");
    if (truncated != null && truncated.asBoolean()) {
      return true;
    }
    JsonNode children = node.get("children");
    if (children != null && children.isArray()) {
      for (JsonNode child : children) {
        if (hasTruncatedNode(child)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether a JSON string array contains {@code value}. */
  private static boolean containsString(JsonNode array, String value) {
    if (array == null || !array.isArray()) {
      return false;
    }
    for (JsonNode item : array) {
      if (value.equals(item.asString())) {
        return true;
      }
    }
    return false;
  }

  /** The first element of a JSON array whose {@code field} string value equals {@code value}. */
  private static JsonNode arrayItemWhere(JsonNode array, String field, String value) {
    if (array == null || !array.isArray()) {
      return null;
    }
    for (JsonNode item : array) {
      JsonNode f = item.get(field);
      if (f != null && value.equals(f.asString())) {
        return item;
      }
    }
    return null;
  }

  private static int distinctColors(BufferedImage image) {
    Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < image.getHeight(); y += 2) {
      for (int x = 0; x < image.getWidth(); x += 2) {
        colors.add(image.getRGB(x, y));
        if (colors.size() > 1000) {
          return colors.size();
        }
      }
    }
    return colors.size();
  }

  private static boolean pollUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }

  // ------------------------------------------------------------------ get_text (paged reads)

  @Test
  void getTextReadsAWholeDocumentPastTheSnapshotCap() {
    JsonNode tree = mcp.call("find_components", Map.of("name", "notesArea", "class", "JTextArea"));
    String capped = tree.get("matches").get(0).get("text").asText();
    assertTrue(capped.endsWith("…"), "the tree's text should still be capped: " + capped);

    JsonNode read = mcp.call("get_text", Map.of("component_id", "notesArea"));
    assertEquals("document", read.get("source").asText());
    assertTrue(read.get("length").asInt() > 500, "expected a long document");
    assertTrue(read.get("text").asText().contains("Note line 40"), "expected the whole document");
    assertFalse(read.has("truncated"), "a 4000-char default should cover it");
  }

  @Test
  void getTextPagesWithOffsetAndReportsTheFullLength() {
    JsonNode first = mcp.call("get_text", Map.of("component_id", "notesArea", "max_chars", 40));
    assertTrue(first.get("truncated").asBoolean(), "a 40-char page must be truncated");
    int length = first.get("length").asInt();
    assertEquals(40, first.get("text").asText().length());

    JsonNode second =
        mcp.call("get_text", Map.of("component_id", "notesArea", "offset", 40, "max_chars", 40));
    assertEquals(40, second.get("offset").asInt());
    assertEquals(length, second.get("length").asInt(), "full length is stable across pages");
    assertNotEquals(first.get("text").asText(), second.get("text").asText());
  }

  @Test
  void getTextFallsBackToDisplayTextForANonTextComponent() {
    JsonNode read = mcp.call("get_text", Map.of("component_id", "openNotesButton"));
    assertEquals("button_text", read.get("source").asText());
    assertEquals("Notes...", read.get("text").asText());
  }

  // ------------------------------------------------------------------ set_text append mode

  @Test
  void setTextAppendAddsToTheEndOfTheDocument() {
    JsonNode original = mcp.call("get_text", Map.of("component_id", "notesArea"));
    int before = original.get("length").asInt();
    try {
      mcp.call(
          "set_text",
          Map.of("component_id", "notesArea", "text", "APPENDED-MARKER", "mode", "append"));

      JsonNode after = mcp.call("get_text", Map.of("component_id", "notesArea"));
      assertEquals(before + "APPENDED-MARKER".length(), after.get("length").asInt());
      assertTrue(after.get("text").asText().endsWith("APPENDED-MARKER"), "append lands at the end");
    } finally {
      // Restore the document verbatim — the get_text tests read it, and JUnit does not promise
      // an order.
      mcp.call(
          "set_text", Map.of("component_id", "notesArea", "text", original.get("text").asText()));
    }
  }

  // ------------------------------------------------------------------ table-cell addressing

  @Test
  void selectByRowAndColumnSelectsThatCell() {
    JsonNode result =
        mcp.call("select", Map.of("component_id", "peopleTable", "row", 1, "column", 1));
    assertTrue(result.get("ok").asBoolean());
    String description = result.get("description").asText();
    assertTrue(description.contains("(1,1)"), description);
    assertTrue(description.contains("grace@example.com"), description);
  }

  @Test
  void selectRejectsARowWithoutAColumn() {
    McpSchema.CallToolResult result =
        mcp.callRaw("select", Map.of("component_id", "peopleTable", "row", 1));
    assertTrue(Boolean.TRUE.equals(result.isError()), "row without column should be an error");
    assertTrue(Mcp.firstText(result).contains("row and column"), Mcp.firstText(result));
  }

  @Test
  void selectReportsAnOutOfRangeCellWithTheActualDimensions() {
    McpSchema.CallToolResult result =
        mcp.callRaw("select", Map.of("component_id", "peopleTable", "row", 99, "column", 0));
    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertTrue(Mcp.firstText(result).contains("3 rows"), Mcp.firstText(result));
  }

  @Test
  void doubleClickingACellByRowAndColumnStartsThatCellsEditor() {
    mcp.call("click", Map.of("component_id", "peopleTable", "row", 2, "column", 0, "count", 2));
    mcp.call("await_idle", Map.of());

    // The editor is a text field inside the table; it should carry the clicked cell's value.
    JsonNode editor =
        mcp.call("find_components", Map.of("component_id", "peopleTable", "class", "JTextField"));
    assertTrue(editor.get("count").asInt() >= 1, "a cell editor should have opened: " + editor);
    assertEquals(
        "Alan Turing",
        mcp.call(
                "get_text", Map.of("component_id", editor.get("matches").get(0).get("id").asText()))
            .get("text")
            .asText());
    mcp.call("press_key", Map.of("key", "escape"));
  }

  @Test
  void clickRejectsMixingAnExplicitPointWithACell() {
    McpSchema.CallToolResult result =
        mcp.callRaw(
            "click", Map.of("component_id", "peopleTable", "x", 5, "y", 5, "row", 0, "column", 0));
    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertTrue(Mcp.firstText(result).contains("not both"), Mcp.firstText(result));
  }

  // ------------------------------------------------------------------ menu/tree no-match errors

  @Test
  void aMissingMenuEntryErrorNamesTheAvailableChildren() {
    McpSchema.CallToolResult result =
        mcp.callRaw(
            "select", Map.of("component_id", "menuBar", "path", List.of("File", "Nonexistent")));
    assertTrue(Boolean.TRUE.equals(result.isError()));
    String message = Mcp.firstText(result);
    assertTrue(message.contains("children:"), message);
    assertTrue(message.contains("Open"), message);
  }

  // ------------------------------------------------------------------ screenshot scale semantics

  @Test
  void scaleIsRelativeToLogicalPixelsNotTheDisplayScale() throws Exception {
    // scale: 1 must mean "one image pixel per logical pixel" on any display, HiDPI included.
    Path target = Files.createTempDirectory("swingmcp-e2e").resolve("scale1.png");
    McpSchema.CallToolResult result =
        mcp.callRaw(
            "screenshot",
            Map.of("component_id", "peopleTable", "scale", 1, "save_path", target.toString()));
    assertFalse(Boolean.TRUE.equals(result.isError()), Mcp.firstText(result));

    JsonNode bounds =
        mcp.call("find_components", Map.of("name", "peopleTable", "class", "JTable"))
            .get("matches")
            .get(0)
            .get("bounds");
    BufferedImage png = ImageIO.read(target.toFile());
    assertEquals(bounds.get("w").asInt(), png.getWidth(), "1 image px per logical px");
    assertEquals(bounds.get("h").asInt(), png.getHeight(), "1 image px per logical px");
  }

  @Test
  void anExplicitScaleStillRespectsTheLongestEdgeCapWhenReturnedInline() {
    McpSchema.CallToolResult result =
        mcp.callRaw("screenshot", Map.of("window_id", "mainFrame", "scale", 8));
    assertFalse(Boolean.TRUE.equals(result.isError()), Mcp.firstText(result));
    String note = Mcp.firstText(result);
    assertNotNull(note);
    // 8x a 560px-wide window would be 4480px without the cap.
    assertTrue(
        note.contains("pixel size: 1200x") || note.contains("x1200"),
        "expected the longest edge capped at 1200: " + note);
  }
}
