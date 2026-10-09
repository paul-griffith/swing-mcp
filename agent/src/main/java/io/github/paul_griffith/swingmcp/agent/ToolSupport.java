package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.core.Bounds;
import io.github.paul_griffith.swingmcp.core.ComponentAddress;
import io.github.paul_griffith.swingmcp.core.ComponentTreeSnapshot;
import io.github.paul_griffith.swingmcp.core.EdtOps;
import io.github.paul_griffith.swingmcp.core.Interactions;
import io.github.paul_griffith.swingmcp.core.Introspection;
import io.github.paul_griffith.swingmcp.core.WindowSnapshot;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing for the per-family tool classes ({@link IntrospectionTools}, {@link
 * InteractionTools}, {@link DiagnosticTools}): argument readers, JSON-schema builders, the {@link
 * #guarded} exception mapper, target resolution, and the terse-JSON render helpers. Splitting these
 * out keeps each family class to just its tool schemas + handlers (see {@code SwingTools}).
 *
 * <p>Results deliberately omit {@code null} and uninteresting {@code false} fields to keep the
 * payload small for an LLM consumer.
 */
final class ToolSupport {

  /**
   * Default depth for {@code get_component_tree} when the caller does not specify {@code
   * max_depth}.
   */
  static final int DEFAULT_MAX_DEPTH = 12;

  /**
   * Default cap on a screenshot's longest edge, in pixels. Full-resolution Retina captures are
   * large; unless the caller passes an explicit {@code scale}, the image is downscaled so its
   * longest edge is at most this many pixels.
   */
  static final int SCREENSHOT_MAX_EDGE = 1200;

  /**
   * Timeout handed to a core read that is invoked <i>inside</i> a {@link
   * ComponentAddress#withComponent} task — i.e. already on the EDT, where the read runs inline and
   * its own timeout never applies. Named for intent; a generous value guards the (unexpected) case
   * of being called off the EDT.
   */
  static final Duration EDT_INLINE = Duration.ofSeconds(30);

  private final JsonMapper json;

  ToolSupport(JsonMapper json) {
    this.json = json;
  }

  // ------------------------------------------------------------------ target resolution

  /**
   * The id a read/screenshot should target: the explicit {@code id}, or the default window's id
   * when none was given; {@code null} when no window exists. Returning the <i>id</i> (not a
   * resolved component) lets the caller fold resolve+act into a single EDT task via {@link
   * ComponentAddress#withComponent}.
   */
  String targetId(String id) {
    return id != null ? id : defaultWindowId();
  }

  /** The id of the focused/first-visible window (frames preferred), or {@code null} when none. */
  String defaultWindowId() {
    List<WindowSnapshot> windows = Introspection.listWindows();
    if (windows.isEmpty()) {
      return null;
    }
    WindowSnapshot chosen = null;
    for (WindowSnapshot w : windows) {
      if (w.focused() && w.showing()) {
        return w.id();
      }
      if (chosen == null && w.showing() && w.kind() == WindowSnapshot.Kind.FRAME) {
        chosen = w;
      }
    }
    if (chosen != null) {
      return chosen.id();
    }
    for (WindowSnapshot w : windows) {
      if (w.showing()) {
        return w.id();
      }
    }
    return windows.get(0).id();
  }

  // ------------------------------------------------------------------ terse JSON encoding

  Map<String, Object> windowMap(WindowSnapshot w) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", w.id());
    m.put("ordinal", w.ordinal());
    m.put("kind", lower(w.kind().name()));
    putIfNotNull(m, "title", w.title());
    putIdentity(m, w.id(), w.name(), w.idKind());
    m.put("bounds", boundsMap(w.bounds()));
    m.put("visible", w.visible());
    m.put("showing", w.showing());
    putIfTrue(m, "focused", w.focused());
    putIfTrue(m, "active", w.active());
    putIfTrue(m, "modal", w.modal());
    if (w.modality() != null && w.modality() != WindowSnapshot.Modality.MODELESS) {
      m.put("modality", lower(w.modality().name()));
    }
    putIfTrue(m, "iconified", Boolean.TRUE.equals(w.iconified()));
    putIfTrue(m, "maximized", Boolean.TRUE.equals(w.maximized()));
    return m;
  }

  /** Full component-tree node rendering, including nested {@code children}. */
  Map<String, Object> nodeMap(ComponentTreeSnapshot n) {
    return nodeMap(n, true);
  }

  /** Flat rendering of a single {@code find_components} match: the node fields without children. */
  Map<String, Object> matchMap(ComponentTreeSnapshot n) {
    return nodeMap(n, false);
  }

  /**
   * The shared node field-writer. With {@code includeChildren} it nests {@code children} (or a
   * {@code childrenTruncated} flag when a depth limit cut them off); without, it is the flat {@code
   * find_components} match shape. One writer so the two shapes never drift as fields evolve.
   */
  private Map<String, Object> nodeMap(ComponentTreeSnapshot n, boolean includeChildren) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", n.id());
    putIdentity(m, n.id(), n.name(), n.idKind());
    m.put("class", n.simpleClassName());
    if (!isStandardClass(n.className())) {
      m.put("className", n.className());
    }
    putIfNotNull(m, "text", n.text());
    m.put("bounds", boundsMap(n.bounds()));
    if (!n.enabled()) {
      m.put("enabled", false);
    }
    if (!n.visible()) {
      m.put("visible", false);
    }
    putIfTrue(m, "focused", n.focused());
    if (n.selection() != null) {
      m.put("selection", selectionMap(n.selection()));
    }
    if (n.childCount() > 0) {
      m.put("childCount", n.childCount());
    }
    if (includeChildren) {
      if (!n.children().isEmpty()) {
        List<Object> children = new ArrayList<>(n.children().size());
        for (ComponentTreeSnapshot child : n.children()) {
          children.add(nodeMap(child, true));
        }
        m.put("children", children);
      } else if (n.childCount() > 0) {
        m.put("childrenTruncated", true);
      }
    }
    return m;
  }

  /** Terse rendering of a {@code get_items} read: columns (tables only), rows, count, selection. */
  /**
   * Result shape for {@code get_text}: the requested slice plus the <i>full</i> {@code length}, so
   * a caller pages with {@code offset} until {@code truncated} stops coming back rather than
   * probing for the end. {@code resolvedId} appears only when the read auto-descended.
   */
  Map<String, Object> textReadMap(String id, Component addressed, Introspection.TextRead read) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    putIfNotNull(m, "resolvedId", descendedId(addressed, read.target()));
    m.put("text", read.text());
    m.put("length", read.length());
    m.put("offset", read.offset());
    if (read.truncated()) {
      m.put("truncated", true);
    }
    m.put("source", read.source());
    return m;
  }

  Map<String, Object> itemsMap(String id, Introspection.DataContents data) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("kind", lower(data.kind().name()));
    if (!data.columns().isEmpty()) {
      m.put("columns", data.columns());
    }
    m.put("rows", data.rows());
    m.put("count", data.total());
    putIfTrue(m, "truncated", data.truncated());
    if (data.selectedIndex() >= 0) {
      m.put("selectedIndex", data.selectedIndex());
    }
    return m;
  }

  private Map<String, Object> selectionMap(ComponentTreeSnapshot.Selection s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", lower(s.kind().name()));
    if (s.kind() == ComponentTreeSnapshot.SelectionKind.TOGGLE) {
      m.put("selected", s.selected());
    } else {
      if (s.selectedIndex() >= 0) {
        m.put("selectedIndex", s.selectedIndex());
      }
      if (!s.selectedIndices().isEmpty()) {
        m.put("selectedIndices", s.selectedIndices());
      }
      putIfNotNull(m, "selectedText", s.selectedText());
    }
    return m;
  }

  private Map<String, Object> boundsMap(Bounds b) {
    if (b == null) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("x", b.x());
    m.put("y", b.y());
    m.put("w", b.width());
    m.put("h", b.height());
    return m;
  }

  private static boolean isStandardClass(String className) {
    return className.startsWith("javax.swing.") || className.startsWith("java.awt.");
  }

  // ------------------------------------------------------------------ result helpers

  McpSchema.CallToolResult jsonText(Object value) {
    return McpSchema.CallToolResult.builder().addTextContent(writeJson(value)).build();
  }

  String writeJson(Object value) {
    return json.writeValueAsString(value); // Jackson 3: JacksonException is unchecked
  }

  /** A structured "no target" error (no window to act on) — see {@link #guarded} for exceptions. */
  McpSchema.CallToolResult noTargetError(String message) {
    return errorResult("no_target", message, null);
  }

  /**
   * Runs a tool body, mapping any thrown {@link RuntimeException} to a structured error result
   * ({@code {error, message, hint?}}, with {@code isError(true)}). Known exceptions get a stable
   * {@code error} code and a recovery {@code hint}; a failed file write/encode ({@link
   * UncheckedIOException} — tool bodies wrap checked {@link java.io.IOException}s in it) maps to
   * {@code io_error}; anything else falls back to {@code internal_error}. The structured shape lets
   * a client branch on the code instead of parsing a free-text {@code "SimpleName: message"}
   * string.
   */
  McpSchema.CallToolResult guarded(ToolBody body) {
    try {
      return body.run();
    } catch (RuntimeException e) {
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      if (e instanceof ComponentAddress.ComponentNotFoundException) {
        return errorResult(
            "component_not_found",
            message,
            "the id no longer resolves; call find_components (or get_component_tree) to relocate "
                + "the target");
      }
      if (e instanceof ComponentAddress.AmbiguousComponentException) {
        return errorResult(
            "ambiguous_component",
            message,
            "the name now matches more than one component; address it with a structural id from "
                + "get_component_tree");
      }
      if (e instanceof EdtOps.EdtTimeoutException) {
        return errorResult(
            "edt_timeout",
            message,
            "the EDT did not respond in time; call edt_stack to see where it is blocked");
      }
      if (e instanceof UncheckedIOException) {
        return errorResult("io_error", message, null);
      }
      if (e instanceof IllegalArgumentException) {
        return errorResult("invalid_argument", message, null);
      }
      return errorResult("internal_error", message, null);
    }
  }

  /** A structured {@code {error, message, hint?}} result with {@code isError(true)}. */
  McpSchema.CallToolResult errorResult(String code, String message, String hint) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("error", code);
    m.put("message", message);
    putIfNotNull(m, "hint", hint);
    return McpSchema.CallToolResult.builder().isError(true).addTextContent(writeJson(m)).build();
  }

  @FunctionalInterface
  interface ToolBody {
    McpSchema.CallToolResult run();
  }

  static McpServerFeatures.SyncToolSpecification spec(
      McpSchema.Tool tool,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
          handler) {
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(handler)
        .build();
  }

  // ------------------------------------------------------------------ argument reading

  String stringArg(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    return null;
  }

  /**
   * A non-blank string argument, or {@link IllegalArgumentException} — surfaced as a tool error.
   */
  String requiredStringArg(McpSchema.CallToolRequest request, String key) {
    String value = stringArg(request, key);
    if (value == null) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  /** A required string argument that permits the empty string (only its presence is enforced). */
  String requiredRawStringArg(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException(key + " is required");
  }

  Integer intArgOrNull(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  boolean boolArg(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (value instanceof Boolean b) {
      return b;
    }
    return value instanceof String s && Boolean.parseBoolean(s.trim());
  }

  /** A JSON string-array argument as a list, or {@code null} when absent/empty. */
  List<String> stringListArg(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      return null;
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object element : list) {
      if (element != null) {
        out.add(String.valueOf(element));
      }
    }
    return out.isEmpty() ? null : out;
  }

  int intArg(McpSchema.CallToolRequest request, String key, int fallback) {
    Object value = arguments(request).get(key);
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        // fall through to the default
      }
    }
    return fallback;
  }

  Double doubleArg(McpSchema.CallToolRequest request, String key) {
    Object value = arguments(request).get(key);
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /**
   * The optional {@code timeout_ms} EDT-wait override as a {@link Duration}, or {@code fallback}
   * when absent or non-positive. Lets a caller lengthen (or shorten) the bounded EDT wait for a
   * single call without changing the shared default.
   */
  Duration timeout(McpSchema.CallToolRequest request, Duration fallback) {
    Integer ms = intArgOrNull(request, "timeout_ms");
    return (ms != null && ms > 0) ? Duration.ofMillis(ms) : fallback;
  }

  private Map<String, Object> arguments(McpSchema.CallToolRequest request) {
    Map<String, Object> args = request.arguments();
    return args != null ? args : Map.of();
  }

  // ------------------------------------------------------------------ schema builders

  Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (required.length > 0) {
      schema.put("required", List.of(required));
    }
    schema.put("additionalProperties", false);
    return schema;
  }

  Map<String, Object> stringProp(String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "string");
    p.put("description", description);
    return p;
  }

  Map<String, Object> enumStringProp(String description, String... values) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "string");
    p.put("description", description);
    p.put("enum", List.of(values));
    return p;
  }

  Map<String, Object> booleanProp(String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "boolean");
    p.put("description", description);
    return p;
  }

  Map<String, Object> stringArrayProp(String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "array");
    p.put("items", Map.of("type", "string"));
    p.put("description", description);
    return p;
  }

  Map<String, Object> intProp(String description, int minimum) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "integer");
    p.put("description", description);
    p.put("minimum", minimum);
    return p;
  }

  Map<String, Object> numberProp(String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "number");
    p.put("description", description);
    p.put("exclusiveMinimum", 0);
    return p;
  }

  /** The optional {@code timeout_ms} EDT-wait override property, shared by the acting tools. */
  Map<String, Object> timeoutProp() {
    return intProp(
        "Optional EDT wait budget in milliseconds; overrides the default for this call. A blocked "
            + "EDT surfaces as an edt_timeout error after this long.",
        1);
  }

  // ------------------------------------------------------------------ misc helpers

  /**
   * Downscales {@code image} so its longest edge is at most {@code maxEdge} px; returns it
   * unchanged if already smaller.
   */
  static BufferedImage downscaleToMaxEdge(BufferedImage image, int maxEdge) {
    int longest = Math.max(image.getWidth(), image.getHeight());
    if (longest <= maxEdge) {
      return image;
    }
    double factor = (double) maxEdge / longest;
    int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
    int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = scaled.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(image, 0, 0, width, height, null);
    } finally {
      g.dispose();
    }
    return scaled;
  }

  /**
   * Result shape for a modal-safe dispatch that carries no window-delta observation (close_window),
   * keeping today's follow-up note.
   */
  Map<String, Object> dispatchMap(String action, String id, boolean edtSettled) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("action", action);
    m.put("id", id);
    m.put("dispatched", true);
    m.put("edtSettled", edtSettled);
    m.put("note", followUpNote(edtSettled));
    return m;
  }

  /**
   * Result shape for a modal-safe dispatch that observed the window/popup delta (click, press_key,
   * menu-path select): {@code opened[]}/{@code closed[]} replace the old "call list_windows to find
   * it" note. See {@link #applyDelta}.
   */
  Map<String, Object> dispatchMap(
      String action, String id, boolean edtSettled, Interactions.WindowDelta delta) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("action", action);
    m.put("id", id);
    m.put("dispatched", true);
    m.put("edtSettled", edtSettled);
    applyDelta(m, edtSettled, delta);
    return m;
  }

  /**
   * Renders a dispatch's window/popup delta into the result. A known delta speaks for itself:
   * {@code opened[]} ({@code {id, kind, title?, bounds}}) and {@code closed[]} (ids) are emitted
   * only when non-empty, and no follow-up note is needed — the delta already answers "what did this
   * open?". An unknown delta (the bounded after-snapshot timed out on a busy/blocked EDT) emits
   * {@code deltaUnknown: true} plus today's follow-up note.
   */
  void applyDelta(Map<String, Object> m, boolean edtSettled, Interactions.WindowDelta delta) {
    if (delta == null) {
      m.put("deltaUnknown", true);
      m.put("note", followUpNote(edtSettled));
      return;
    }
    if (!delta.opened().isEmpty()) {
      List<Object> opened = new ArrayList<>(delta.opened().size());
      for (Interactions.WindowRef ref : delta.opened()) {
        opened.add(windowRefMap(ref));
      }
      m.put("opened", opened);
    }
    if (!delta.closed().isEmpty()) {
      m.put("closed", delta.closed());
    }
  }

  /** Terse rendering of one opened window/popup: {@code {id, kind, title?, bounds}}. */
  private Map<String, Object> windowRefMap(Interactions.WindowRef ref) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", ref.id());
    m.put("kind", ref.kind());
    putIfNotNull(m, "title", ref.title());
    m.put("bounds", boundsMap(ref.bounds()));
    return m;
  }

  /**
   * The id of the component that actually received a text operation, when it differs from the
   * {@code addressed} one — i.e. the tool auto-descended from a named composite widget to its inner
   * field, so the caller learns the durable inner id to use next time; {@code null} when no descent
   * happened. Called on the EDT (inside a {@link ComponentAddress#withComponent} task), so the id
   * computation runs inline.
   */
  String descendedId(Component addressed, Component resolved) {
    return resolved == addressed ? null : ComponentAddress.idOf(resolved);
  }

  /** Result shape for a synchronous action that completed. */
  Map<String, Object> okMap(String action, String id, Map<String, Object> extra) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("action", action);
    m.put("id", id);
    m.put("ok", true);
    m.putAll(extra);
    return m;
  }

  /**
   * A warning for a click that will likely silently no-op — a disabled or not-showing target, or an
   * inert one (no button, no mouse listeners) — or {@code null} when the click has a plausible
   * effect. A disabled/not-showing target is reported over the inert-target message, since those
   * are the more specific cause.
   */
  String clickWarning(Interactions.DispatchResult result) {
    List<String> reasons = new ArrayList<>(2);
    if (!result.enabled()) {
      reasons.add("the target is disabled, so the click will have no effect");
    }
    if (!result.showing()) {
      reasons.add(
          "the target is not showing (its window is hidden or not laid out), so the click may "
              + "have no effect");
    }
    if (reasons.isEmpty() && !result.actionable()) {
      reasons.add(
          "the target is not a button and has no mouse listeners, so the click likely had no "
              + "effect. Recheck the id, or aim a child/overlay via find_components or an explicit "
              + "x/y");
    }
    return reasons.isEmpty() ? null : String.join("; ", reasons);
  }

  String followUpNote(boolean edtSettled) {
    return edtSettled
        ? "Dispatched; the EDT drained within the grace period. If it may have opened a modal "
            + "dialog, call list_windows to find it."
        : "Dispatched, but the EDT is still busy or blocked (a modal dialog or long task may be "
            + "running). Follow up with await_idle, list_windows, or edt_stack.";
  }

  String truncate(String text, int cap) {
    return text.length() <= cap ? text : text.substring(0, cap) + "…";
  }

  /**
   * Renders a scale factor compactly: {@code 2} rather than {@code 2.0}, {@code 1.5} rather than
   * {@code 1.50}.
   */
  String formatScale(double scale) {
    if (scale == Math.rint(scale)) {
      return Long.toString((long) scale);
    }
    return String.valueOf(Math.round(scale * 100.0) / 100.0);
  }

  String firstNonBlank(String a, String b) {
    return a != null ? a : b;
  }

  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  void putIfNotNull(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value);
    }
  }

  void putIfTrue(Map<String, Object> m, String key, boolean value) {
    if (value) {
      m.put(key, true);
    }
  }

  /**
   * Surfaces a component/window's identity beyond its bare {@code id}: the raw {@code name} when it
   * carries information the id does not (i.e. a real {@link java.awt.Component#getName()} that was
   * not itself used as the id — e.g. a duplicated name that fell back to a structural path), and an
   * {@code idKind} tag of {@code "name"} (a durable locator) or {@code "auto"} (an AWT-invented
   * default that is not stable across runs). A plain structural id emits neither, so an absent
   * {@code idKind} means "structural" — recognizable anyway from the id's {@code w#/…[k]} shape.
   */
  private void putIdentity(
      Map<String, Object> m, String id, String name, ComponentAddress.IdKind idKind) {
    if (name != null && !name.equals(id)) {
      m.put("name", name);
    }
    if (idKind == ComponentAddress.IdKind.NAME) {
      m.put("idKind", "name");
    } else if (idKind == ComponentAddress.IdKind.AUTO) {
      m.put("idKind", "auto");
    }
  }
}
