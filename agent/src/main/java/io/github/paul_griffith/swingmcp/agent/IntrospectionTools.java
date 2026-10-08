package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.core.ComponentAddress;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot;
import io.github.paul_griffith.swingmcp.core.Introspection;
import io.github.paul_griffith.swingmcp.core.Screenshots;
import io.github.paul_griffith.swingmcp.core.WindowSnapshot;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-only introspection tools: {@code list_windows}, {@code get_component_tree}, {@code
 * find_components}, {@code screenshot}, {@code get_properties}, and {@code get_text}. Each resolves
 * any component/window id through {@link ComponentAddress} and reads on the EDT via {@link
 * Introspection} / {@link Screenshots}, over the shared {@link ToolSupport}.
 */
final class IntrospectionTools {

  private final ToolSupport s;

  IntrospectionTools(ToolSupport support) {
    this.s = support;
  }

  // ------------------------------------------------------------------ tool: list_windows

  McpServerFeatures.SyncToolSpecification listWindows() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "include_hidden",
        s.booleanProp(
            "Also include windows that are not showing (hidden, not yet shown, or disposed frames "
                + "that linger in the window list). Default false: only showing windows are "
                + "returned."));
    properties.put("title", s.stringProp("Case-insensitive substring filter on the window title."));
    McpSchema.Tool tool =
        McpSchema.Tool.builder("list_windows", s.objectSchema(properties))
            .title("List Swing windows")
            .description(
                """
                List the top-level AWT/Swing windows in the target application (frames, \
                dialogs, popups). By default only SHOWING windows are returned, sorted \
                focused-first; pass `include_hidden: true` for the full window list (stale \
                invisible frames included), and `title` to filter by title substring \
                (case-insensitive). Returns `{windows: [...], hiddenCount?}`; each window has an \
                `id` (the handle \
                used by the other tools), `kind`, `title`, `bounds` \
                (logical px: x, y, w, h), and state flags (visible, showing, and — only when \
                true — focused, active, modal, iconified, maximized). `hiddenCount` (present \
                only when hidden windows were suppressed) reports how many. An `id` is the \
                window's \
                getName() when set and unique, otherwise `w<handle>`. `idKind` tags how durable \
                that id is: `name` = a deliberate, stable getName(); `auto` = an AWT-invented \
                default like `frame2` that changes between runs (do not hard-code it); absent = a \
                `w<handle>` fallback.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  Introspection.WindowFilter filtered =
                      Introspection.filterWindows(
                          Introspection.listWindows(),
                          s.boolArg(request, "include_hidden"),
                          s.stringArg(request, "title"));
                  List<Object> windows = new ArrayList<>();
                  for (WindowSnapshot window : filtered.windows()) {
                    windows.add(s.windowMap(window));
                  }
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("windows", windows);
                  if (filtered.hiddenCount() > 0) {
                    m.put("hiddenCount", filtered.hiddenCount());
                  }
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: get_component_tree

  McpServerFeatures.SyncToolSpecification componentTree() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "window_id",
        s.stringProp(
            "Window handle (id from list_windows) to snapshot. Defaults to the focused/first "
                + "visible window when neither this nor component_id is given."));
    properties.put(
        "component_id",
        s.stringProp(
            "Component handle (id from a previous get_component_tree) to snapshot a subtree. "
                + "Takes precedence over window_id."));
    properties.put(
        "max_depth",
        s.intProp(
            "Maximum descendant depth to include (default "
                + ToolSupport.DEFAULT_MAX_DEPTH
                + "; 0 = the "
                + "root only). A node whose children were cut off by this limit is marked "
                + "childrenTruncated=true while still reporting its true childCount.",
            0));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("get_component_tree", s.objectSchema(properties))
            .title("Get Swing component tree")
            .description(
                """
                Snapshot the Swing component hierarchy under a window or component as a JSON \
                tree. Each node carries an `id` (the stable handle interaction tools accept — \
                getName()-based when the component is uniquely named, otherwise a structural \
                path), `idKind` (`name` = the id is a durable getName(); `auto` = an AWT-invented \
                default that is not stable across runs; absent = a structural path), the raw \
                `name` when it differs from the id, `class`, best-effort `text`/label, `bounds`, \
                `selection` state for \
                combos/lists/tables/tabs/toggles, `childCount`, and nested `children`. \
                `bounds` are in logical (device-independent) pixels: {x, y} is the position \
                within the parent and {w, h} the size. To map bounds onto a screenshot's \
                pixels, multiply by the effective scale the screenshot tool reports (they \
                differ on HiDPI displays). Disabled/hidden/focused state is included only when \
                noteworthy. Use `max_depth` to bound large trees and re-query a subtree via \
                `component_id`.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id =
                      s.firstNonBlank(
                          s.stringArg(request, "component_id"), s.stringArg(request, "window_id"));
                  int maxDepth = s.intArg(request, "max_depth", ToolSupport.DEFAULT_MAX_DEPTH);
                  String tid = s.targetId(id);
                  if (tid == null) {
                    return s.noTargetError("no target: there are no windows to snapshot");
                  }
                  // Resolve + snapshot in a single EDT task (no resolve→read TOCTOU window).
                  ComponentTreeSnapshot tree =
                      ComponentAddress.withComponent(
                          tid,
                          s.timeout(request, Introspection.DEFAULT_TIMEOUT),
                          root ->
                              Introspection.componentTree(root, maxDepth, ToolSupport.EDT_INLINE));
                  return s.jsonText(s.nodeMap(tree));
                }));
  }

  // ------------------------------------------------------------------ tool: find_components

  McpServerFeatures.SyncToolSpecification findComponents() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "name",
        s.stringProp("Substring to match against a component's getName() (case-insensitive)."));
    properties.put(
        "class",
        s.stringProp(
            "Substring to match against the simple or fully-qualified class name "
                + "(case-insensitive), e.g. \"JTextField\"."));
    properties.put(
        "text",
        s.stringProp(
            "Substring to match against a component's best-effort display text"
                + " (case-insensitive)."));
    properties.put(
        "interactable",
        s.booleanProp(
            "When true, return only input widgets (buttons, text fields, combos, lists, tables, "
                + "tabs, spinners, trees, sliders, menu items)."));
    properties.put(
        "window_id",
        s.stringProp("Restrict the search to this window's subtree (id from list_windows)."));
    properties.put(
        "component_id",
        s.stringProp(
            "Restrict the search to this component's subtree; takes precedence over window_id. "
                + "When neither is given, all windows are searched."));
    properties.put(
        "limit",
        s.intProp(
            "Maximum matches to return (default " + Introspection.DEFAULT_FIND_LIMIT + ").", 1));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("find_components", s.objectSchema(properties))
            .title("Find components by name/class/text")
            .description(
                """
                Search the component hierarchy for components matching any combination of \
                `name`, `class`, and `text` (case-insensitive substrings, AND-combined), \
                optionally limited to `interactable` input widgets and scoped to a \
                window/component subtree (default: all windows). Returns a flat array of \
                `matches` — each with the `id` the interaction tools accept, its `idKind`/`name` \
                (locator durability; see get_component_tree), `class`, `text`, \
                `bounds`, `selection`, and `childCount` — plus the total `count` and \
                `truncated` when capped by `limit`. When nothing matched, `scanned` reports how \
                many components were searched and `hint` carries diagnostics: the scope covered \
                (an empty subtree may be lazily built and not exist until shown), plus near-miss \
                names/classes seen during the scan. Use this instead of walking \
                get_component_tree to locate a target in a large UI. At least one filter is \
                required.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  Introspection.FindCriteria criteria =
                      new Introspection.FindCriteria(
                          s.stringArg(request, "name"),
                          s.stringArg(request, "class"),
                          s.stringArg(request, "text"),
                          s.boolArg(request, "interactable"));
                  int limit = s.intArg(request, "limit", Introspection.DEFAULT_FIND_LIMIT);
                  Introspection.FindResult result =
                      Introspection.find(
                          findRoots(request),
                          criteria,
                          limit,
                          s.timeout(request, Introspection.DEFAULT_TIMEOUT));
                  List<Object> matches = new ArrayList<>(result.matches().size());
                  for (ComponentTreeSnapshot match : result.matches()) {
                    matches.add(s.matchMap(match));
                  }
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("count", result.total());
                  if (result.total() == 0) {
                    m.put("scanned", result.scanned());
                    s.putIfNotNull(m, "hint", result.hint());
                  }
                  s.putIfTrue(m, "truncated", result.truncated());
                  m.put("matches", matches);
                  return s.jsonText(m);
                }));
  }

  /**
   * The roots to search: the explicit window/component subtree, or {@code null} to signal "all live
   * windows" — which {@link Introspection#find} resolves internally in its single EDT task (no
   * per-window pre-resolve hop).
   */
  private List<Component> findRoots(McpSchema.CallToolRequest request) {
    String id =
        s.firstNonBlank(s.stringArg(request, "component_id"), s.stringArg(request, "window_id"));
    return id != null ? List.of(ComponentAddress.resolve(id)) : null;
  }

  // ------------------------------------------------------------------ tool: get_items

  McpServerFeatures.SyncToolSpecification getItems() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "component_id",
        s.stringProp("Data-component handle (JTable, JList, JComboBox, or JTree) to read."));
    properties.put(
        "limit",
        s.intProp(
            "Maximum rows/items/nodes to return (default "
                + Introspection.DEFAULT_ITEM_LIMIT
                + ").",
            1));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("get_items", s.objectSchema(properties, "component_id"))
            .title("Read a table/list/combo/tree's contents")
            .description(
                """
                Read the contents of a data component the tree can't show: a JTable (`columns` \
                plus `rows` of cell text), a JList or JComboBox (each option as a single-cell \
                row), or a JTree (nodes as depth-indented rows). Text is renderer-aware — it is \
                the text the user sees (via the cell renderer / JTree.convertValueToText), not a \
                model object's toString(). Returns `{kind, columns?, rows, count, truncated?, \
                selectedIndex?}`, capped at `limit`. Use it to see values before select-by-value, \
                or to read a result table without a screenshot.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  int limit = s.intArg(request, "limit", Introspection.DEFAULT_ITEM_LIMIT);
                  Introspection.DataContents data =
                      ComponentAddress.withComponent(
                          id,
                          s.timeout(request, Introspection.DEFAULT_TIMEOUT),
                          target -> Introspection.readItems(target, limit, ToolSupport.EDT_INLINE));
                  return s.jsonText(s.itemsMap(id, data));
                }));
  }

  // ------------------------------------------------------------------ tool: screenshot

  McpServerFeatures.SyncToolSpecification screenshot() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "window_id",
        s.stringProp(
            "Window handle (id from list_windows) to capture. Defaults to the focused/first "
                + "visible window when neither this nor component_id is given."));
    properties.put(
        "component_id",
        s.stringProp(
            "Component handle (id from get_component_tree) to capture just that component. "
                + "Takes precedence over window_id."));
    properties.put(
        "scale",
        s.numberProp(
            "Target image pixels per logical (component-tree) pixel — `2` renders at twice the "
                + "component's logical size on any display, HiDPI or not. Omit for the display's "
                + "own native resolution. The longest-edge cap of "
                + ToolSupport.SCREENSHOT_MAX_EDGE
                + " px still applies; pass `save_path` to capture above it."));
    properties.put(
        "save_path",
        s.stringProp(
            "Absolute file path to write the PNG to (parent directories are created; an existing "
                + "file is overwritten). When set, the image is saved at full resolution (the "
                + "longest-edge downscale is skipped) and the result is text-only metadata — no "
                + "image content is returned unless `return_image` is true."));
    properties.put(
        "return_image",
        s.booleanProp(
            "With `save_path`: also return the image as MCP image content (default false — the "
                + "point of saving to a file is not spending context on the payload)."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("screenshot", s.objectSchema(properties))
            .title("Screenshot a window or component")
            .description(
                "Render a window or component to a PNG image (returned as MCP image content) by"
                    + " painting it off-screen — works for occluded/background windows and needs no"
                    + " OS screen-recording permission. Ids come from list_windows /"
                    + " get_component_tree. `scale` is the target image px per logical px (`2` ="
                    + " twice the component's logical size on any display); omit it for native"
                    + " resolution. The capture is downscaled so its longest edge is at most "
                    + ToolSupport.SCREENSHOT_MAX_EDGE
                    + " px to keep the payload small. Pass `save_path` (an"
                    + " absolute path) to write the full-resolution PNG to disk instead, skipping"
                    + " that cap, and get back only text metadata {saved, logical size, pixel"
                    + " size, effective scale}; add `return_image: true` to get both.")
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id =
                      s.firstNonBlank(
                          s.stringArg(request, "component_id"), s.stringArg(request, "window_id"));
                  Double scale = s.doubleArg(request, "scale");
                  Path savePath = savePath(request);
                  boolean returnImage = savePath == null || s.boolArg(request, "return_image");
                  String tid = s.targetId(id);
                  if (tid == null) {
                    return s.noTargetError("no target: there are no windows to capture");
                  }
                  // Resolve + render in a single EDT task. `scale` is a target total scale
                  // relative to logical px, so the display's HiDPI factor is divided out.
                  Screenshots.Capture capture =
                      ComponentAddress.withComponent(
                          tid,
                          s.timeout(request, Screenshots.DEFAULT_TIMEOUT),
                          target ->
                              Screenshots.captureAtScale(
                                  target, scale, null, ToolSupport.EDT_INLINE));
                  // A file save keeps full fidelity (a file has no payload budget); everything
                  // returned inline is capped, explicit scale included — a 3x window capture on a
                  // Retina display is otherwise thousands of pixels wide.
                  BufferedImage image =
                      savePath != null
                          ? capture.image()
                          : ToolSupport.downscaleToMaxEdge(
                              capture.image(), ToolSupport.SCREENSHOT_MAX_EDGE);
                  byte[] pngBytes = Screenshots.toPngBytes(image);
                  if (savePath != null) {
                    writePng(savePath, pngBytes);
                  }
                  String label = (id != null) ? id : "default window";

                  // Effective scale relates the FINAL (possibly downscaled) image to the logical
                  // (tree) px.
                  int logicalW = capture.logicalWidth();
                  int logicalH = capture.logicalHeight();
                  double effectiveScale =
                      logicalW > 0 ? (double) image.getWidth() / logicalW : capture.displayScale();
                  McpSchema.TextContent note =
                      McpSchema.TextContent.builder(
                              "Screenshot of "
                                  + label
                                  + (savePath != null ? " saved to " + savePath : "")
                                  + ". "
                                  + "logical size: "
                                  + logicalW
                                  + "x"
                                  + logicalH
                                  + " px; "
                                  + "pixel size: "
                                  + image.getWidth()
                                  + "x"
                                  + image.getHeight()
                                  + " px; "
                                  + "effective scale: "
                                  + s.formatScale(effectiveScale)
                                  + "x. Component/window bounds in get_component_tree are in"
                                  + " logical px; multiply a logical coordinate by the effective"
                                  + " scale to locate it in these screenshot pixels.")
                          .build();
                  McpSchema.CallToolResult.Builder result =
                      McpSchema.CallToolResult.builder().addContent(note);
                  if (returnImage) {
                    String base64 = Base64.getEncoder().encodeToString(pngBytes);
                    result.addContent(McpSchema.ImageContent.builder(base64, "image/png").build());
                  }
                  return result.build();
                }));
  }

  /**
   * The validated {@code save_path} argument as an absolute {@link Path}, or {@code null} when
   * absent. A relative path is refused up front ({@code invalid_argument}) — the agent's working
   * directory belongs to the host application and is nothing a caller can predict.
   */
  private Path savePath(McpSchema.CallToolRequest request) {
    String savePath = s.stringArg(request, "save_path");
    if (savePath == null) {
      return null;
    }
    Path path = Path.of(savePath);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("save_path must be an absolute path, got: " + savePath);
    }
    return path;
  }

  /**
   * Writes the PNG bytes to {@code path}, creating parent directories and overwriting silently
   * (it's a dev tool). An {@link IOException} is wrapped in {@link UncheckedIOException} so {@link
   * ToolSupport#guarded} surfaces it as a structured {@code io_error}.
   */
  private static void writePng(Path path, byte[] pngBytes) {
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(path, pngBytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write screenshot to " + path, e);
    }
  }

  // ------------------------------------------------------------------ tool: get_properties

  McpServerFeatures.SyncToolSpecification getProperties() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to read. Required."));
    properties.put(
        "properties",
        s.stringArrayProp(
            "Property names to read (case-insensitive), e.g. [\"text\", \"enabled\","
                + " \"toolTipText\"]. The token \"@layout\" expands to a curated layout read"
                + " (bounds, insets, border, min/preferred/max size, parent layout +"
                + " constraints, and computed edgeDistances). Omit to read all readable"
                + " properties."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("get_properties", s.objectSchema(properties, "component_id"))
            .title("Read a component's bean properties")
            .description(
                """
                Read a component's bean-style properties (public no-arg getX()/isX()) on the \
                EDT, rendered as strings. Pass `properties` to filter to specific names; omit \
                for all readable ones. Values are rendered structurally rather than by \
                toString(): Color as `#rrggbb` (`/aa` when translucent), Insets as \
                `{top:.., left:.., bottom:.., right:..}`, Dimension as `WxH`, Rectangle as \
                `(x,y WxH)`, a Border as `ClassName{insets…}`, a component-valued property as \
                `ClassName#<id>` (addressable — feed it straight back to another tool), and a \
                list/table/tree model as `ClassName(size=n)`. Pass `["@layout"]` for the \
                layout preset: bounds, insets, border, min/preferred/max size, the parent's \
                layout manager and this component's constraints in it (BorderLayout, \
                GridBagLayout, MigLayout), plus `edgeDistances` — the gap on each side to the \
                parent's border-adjusted inner bounds, which answers "is this exactly 8px from \
                the edge" without screenshot arithmetic. Getters that throw are skipped, and \
                collection/array values are element-capped.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  List<String> names = s.stringListArg(request, "properties");
                  Map<String, String> props =
                      ComponentAddress.withComponent(
                          id,
                          s.timeout(request, Introspection.DEFAULT_TIMEOUT),
                          target ->
                              Introspection.properties(target, names, ToolSupport.EDT_INLINE));
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", id);
                  m.put("properties", props);
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: get_text

  McpServerFeatures.SyncToolSpecification getText() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to read text from. Required."));
    properties.put("offset", s.intProp("First character to return (default 0).", 0));
    properties.put(
        "max_chars",
        s.intProp(
            "How many characters to return (default "
                + Introspection.TEXT_READ_DEFAULT
                + ", capped at "
                + Introspection.TEXT_READ_MAX
                + ").",
            1));
    properties.put(
        "selected_only",
        s.booleanProp("Read only the current selection rather than the whole document."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("get_text", s.objectSchema(properties, "component_id"))
            .title("Read a component's full text")
            .description(
                """
                Read a component's text in full, paged — the way to get past the \
                200-character cap that `text` fields in the component tree, find results, and \
                get_properties all carry. A text component (JTextArea, JTextField, JEditorPane, \
                …) is read from its document; anything else falls back to the same best-effort \
                display text the tree shows, uncapped. Returns `{id, text, length, offset, \
                truncated?, source, resolvedId?}` — `length` is the *full* length, so page with \
                `offset` until `truncated` is absent. Auto-descends into a named composite \
                exactly like set_text (reporting `resolvedId`). Use this instead of \
                scroll_to + screenshot tiling to read a long text area.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  int offset = s.intArg(request, "offset", 0);
                  int maxChars = s.intArg(request, "max_chars", Introspection.TEXT_READ_DEFAULT);
                  boolean selectedOnly = s.boolArg(request, "selected_only");
                  return s.jsonText(
                      ComponentAddress.withComponent(
                          id,
                          s.timeout(request, Introspection.DEFAULT_TIMEOUT),
                          target -> {
                            Introspection.TextRead read =
                                Introspection.readText(
                                    target, offset, maxChars, selectedOnly, ToolSupport.EDT_INLINE);
                            return s.textReadMap(id, target, read);
                          }));
                }));
  }
}
