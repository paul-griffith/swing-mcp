package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.core.ComponentAddress;
import io.github.paul_griffith.swingmcp.core.Interactions;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The UI-driving tools: {@code click}, {@code set_text}, {@code type_text}, {@code press_key}, and
 * {@code select}. Each folds resolve + act into a single EDT task via {@link
 * ComponentAddress#withComponent} / the {@link Interactions} {@code *ById} entry points, over the
 * shared {@link ToolSupport}. Actions that can open a modal dialog ({@code click}, {@code
 * press_key}, and menu-path {@code select}) are dispatched modal-safely — they return promptly with
 * a {@code dispatched}/{@code edtSettled} status.
 */
final class InteractionTools {

  private final ToolSupport s;

  InteractionTools(ToolSupport support) {
    this.s = support;
  }

  // ------------------------------------------------------------------ tool: click

  McpServerFeatures.SyncToolSpecification click() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "component_id",
        s.stringProp("Component handle (id from get_component_tree) to click. Required."));
    properties.put(
        "button",
        s.enumStringProp(
            "Mouse button to click with. A left single-click on a button/menu-item uses "
                + "doClick(); other cases synthesize mouse events at the target point.",
            "left",
            "middle",
            "right"));
    properties.put("count", s.intProp("Number of clicks (default 1; 2 = double-click).", 1));
    properties.put(
        "x",
        s.intProp(
            "Optional x within the component (logical px, 0 = left edge) to click; defaults to "
                + "the horizontal center. Use to hit a painted sub-region — e.g. an overlay "
                + "clear button at a field's right edge — that is not a separate child.",
            0));
    properties.put(
        "y",
        s.intProp(
            "Optional y within the component (logical px, 0 = top edge) to click; defaults to the "
                + "vertical center.",
            0));
    properties.put(
        "row",
        s.intProp(
            "For a JTable: the view row to click, paired with `column`. Computes the cell's "
                + "centre itself — no rowHeight arithmetic. Cannot be combined with x/y.",
            0));
    properties.put(
        "column", s.intProp("For a JTable: the view column to click, paired with `row`.", 0));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("click", s.objectSchema(properties, "component_id"))
            .title("Click a component")
            .description(
                """
                Click a component (programmatic-first: doClick() for buttons/menu items, \
                synthesized MouseEvents otherwise — with rollover enter/move first so \
                hover-armed overlay buttons trigger). Pass `x`/`y` to aim a painted \
                sub-region the tree doesn't expose as a child. Modal-safe: dispatched without \
                blocking, so it returns promptly even when it opens a modal dialog. Reports the \
                UI delta the click caused: `opened[]` lists any windows/popups that appeared \
                ({id, kind, title?, bounds} — the id is ready for get_component_tree/screenshot) \
                and `closed[]` any that went away; both omitted when nothing changed. \
                `deltaUnknown: true` means the EDT stayed busy past the grace period (also \
                `edtSettled: false`) — follow up with await_idle, list_windows, or edt_stack. \
                For a JTable, pass `row`/`column` (view coordinates, as in get_items) instead \
                of x/y to click a cell — it computes the cell rectangle's centre, which stays \
                correct with varying row heights and a scrolled table. Combine with `count: 2` \
                to start a cell editor. \
                When the click will likely no-op (disabled, not showing, or not a button and no \
                mouse listeners), a `warning` is included.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  Interactions.MouseButton button = parseButton(s.stringArg(request, "button"));
                  int count = s.intArg(request, "count", 1);
                  Interactions.ClickPoint point =
                      new Interactions.ClickPoint(
                          s.intArgOrNull(request, "x"),
                          s.intArgOrNull(request, "y"),
                          s.intArgOrNull(request, "row"),
                          s.intArgOrNull(request, "column"));
                  // Resolve + probe + dispatch fold into one EDT task; then measure settling.
                  Interactions.DispatchResult result =
                      Interactions.clickById(
                          id,
                          button,
                          count,
                          point,
                          s.timeout(request, Interactions.DEFAULT_TIMEOUT));
                  Map<String, Object> m =
                      s.dispatchMap("click", id, result.edtSettled(), result.delta());
                  if (result.mouseListeners() >= 0) {
                    m.put("mouseListeners", result.mouseListeners());
                  }
                  String warning = s.clickWarning(result);
                  if (warning != null) {
                    m.put("warning", warning);
                  }
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: set_text

  McpServerFeatures.SyncToolSpecification setText() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Text-component handle to set. Required."));
    properties.put("text", s.stringProp("The text to set (may be empty to clear). Required."));
    properties.put(
        "mode",
        s.enumStringProp(
            "'replace' (default) overwrites the content; 'append' adds to the end through the "
                + "document-edit path, as typing would.",
            "replace",
            "append"));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("set_text", s.objectSchema(properties, "component_id", "text"))
            .title("Set a text component's text")
            .description(
                """
                Set a text component's content in one shot via setText — a fast state set. \
                It fires the document's own change events but bypasses per-keystroke key \
                bindings and input validation; use type_text when you need those to run. \
                `mode: "append"` adds to the end of the document instead of replacing (caret to \
                end + replaceSelection), replacing the click + press_key end + type_text dance; \
                it is rejected on a read-only target, where it would silently do nothing. \
                If the target is a container of exactly one text field (a named composite \
                widget), it auto-descends to that field and reports the field's `resolvedId`. \
                Errors if the target is neither a text component nor the container of one.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  String text = s.requiredRawStringArg(request, "text");
                  boolean append = parseAppendMode(s.stringArg(request, "mode"));
                  // Resolve + set + compute the auto-descend resolvedId in one EDT task.
                  String resolvedId =
                      ComponentAddress.withComponent(
                          id,
                          s.timeout(request, Interactions.DEFAULT_TIMEOUT),
                          target ->
                              s.descendedId(target, Interactions.setText(target, text, append)));
                  Map<String, Object> extra = new LinkedHashMap<>();
                  s.putIfNotNull(extra, "resolvedId", resolvedId);
                  if (append) {
                    extra.put("mode", "append");
                  }
                  extra.put("length", text.length());
                  extra.put("text", s.truncate(text, 200));
                  return s.jsonText(s.okMap("set_text", id, extra));
                }));
  }

  // ------------------------------------------------------------------ tool: type_text

  McpServerFeatures.SyncToolSpecification typeText() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to type into. Required."));
    properties.put("text", s.stringProp("The text to type, character by character. Required."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("type_text", s.objectSchema(properties, "component_id", "text"))
            .title("Type text key-by-key into a component")
            .description(
                """
                Focus the component and type text one character at a time, dispatching real \
                KeyEvents (pressed/typed/released per char) through the component's event \
                pipeline — so DocumentListeners, key bindings, and input validation all fire, \
                exactly as a user typing would. Contrast set_text, which sets the content \
                directly and skips per-keystroke handling. Requires a text target: if the \
                component is a container of exactly one text field it auto-descends (and \
                reports the field's `resolvedId`); it errors on a target with no text field \
                rather than silently doing nothing.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  String text = s.requiredRawStringArg(request, "text");
                  // Resolve + type + compute resolvedId in one EDT task, sized for the string.
                  String resolvedId =
                      ComponentAddress.withComponent(
                          id,
                          s.timeout(request, Interactions.typeTextTimeout(text.length())),
                          target -> s.descendedId(target, Interactions.typeText(target, text)));
                  Map<String, Object> extra = new LinkedHashMap<>();
                  s.putIfNotNull(extra, "resolvedId", resolvedId);
                  extra.put("typed", text.length());
                  return s.jsonText(s.okMap("type_text", id, extra));
                }));
  }

  // ------------------------------------------------------------------ tool: press_key

  McpServerFeatures.SyncToolSpecification pressKey() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "component_id",
        s.stringProp(
            "Component handle to deliver the key to. Optional: when omitted, the key goes to the "
                + "current focus owner, falling back to the default/focused window."));
    properties.put(
        "key",
        s.stringProp(
            "The key to press. A named key (escape, enter, tab, space, backspace, delete, insert, "
                + "home, end, pageup, pagedown, up, down, left, right, f1–f24), a single character "
                + "(e.g. \"a\", \"7\"), or a chord joining modifiers with '+' (ctrl, shift, alt, "
                + "meta) such as \"ctrl+space\", \"shift+tab\", or \"ctrl+shift+f5\"."));
    properties.put("count", s.intProp("Number of times to press the key (default 1).", 1));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("press_key", s.objectSchema(properties, "key"))
            .title("Press a key or chord")
            .description(
                """
                Deliver a single named key or chord (Escape, Enter, Tab, arrows, function keys, \
                Ctrl+Space, …) to a component — driving its key listeners and key bindings as a \
                keystroke would. Unlike type_text this needs no text target: use it to dismiss a \
                popup (escape), submit a dialog (enter), navigate a tree/list/menu (arrows), or \
                fire an accelerator. With no component_id the key goes to the focus owner (pass \
                component_id for reliability when driving a background app). Modal-safe: reports \
                `edtSettled` plus the UI delta the key caused — `opened[]`/`closed[]` list any \
                windows/popups that appeared or went away (omitted when none); `deltaUnknown: \
                true` = the EDT stayed busy past the grace period, follow up with await_idle / \
                list_windows / edt_stack.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  KeySpec key = parseKey(s.requiredStringArg(request, "key"));
                  int count = s.intArg(request, "count", 1);
                  String id = s.stringArg(request, "component_id");
                  java.time.Duration timeout = s.timeout(request, Interactions.DEFAULT_TIMEOUT);
                  Interactions.Settle settle;
                  if (id != null) {
                    // Explicit target: resolve + dispatch fold into one EDT task.
                    settle =
                        Interactions.pressKeyById(
                            id, key.keyCode(), key.keyChar(), key.modifiers(), count, timeout);
                  } else {
                    Component target = Interactions.currentFocusOwner();
                    if (target != null) {
                      id = ComponentAddress.idOf(target);
                      settle =
                          Interactions.pressKey(
                              target, key.keyCode(), key.keyChar(), key.modifiers(), count);
                    } else {
                      String windowId = s.defaultWindowId();
                      if (windowId == null) {
                        return s.noTargetError(
                            "no target: pass component_id, or open a window to receive the key");
                      }
                      id = windowId;
                      settle =
                          Interactions.pressKeyById(
                              windowId,
                              key.keyCode(),
                              key.keyChar(),
                              key.modifiers(),
                              count,
                              timeout);
                    }
                  }
                  Map<String, Object> m =
                      s.dispatchMap("press_key", id, settle.edtSettled(), settle.delta());
                  m.put("key", request.arguments().get("key"));
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: select

  McpServerFeatures.SyncToolSpecification select() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to select within. Required."));
    properties.put(
        "index",
        s.intProp("Zero-based index to select: combo item, list item, tab, or table row.", 0));
    properties.put(
        "value",
        s.stringProp(
            "Value to select by matching item text: combo/list item, tab title, table cell, or "
                + "spinner value. Exact match first, then case-insensitive."));
    properties.put(
        "path",
        s.stringArrayProp(
            "Path of texts. For a JTree: node texts from the root down. For a "
                + "JMenuBar/JMenu/JPopupMenu: menu then item texts — the terminal item is "
                + "activated (modal-safe)."));
    properties.put(
        "row",
        s.intProp(
            "For a JTable: the view row to select, paired with `column`, selecting that single "
                + "cell and scrolling it visible. Use `index` instead to select a whole row.",
            0));
    properties.put(
        "column", s.intProp("For a JTable: the view column to select, paired with `row`.", 0));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("select", s.objectSchema(properties, "component_id"))
            .title("Select an item / activate a menu path")
            .description(
                """
                Select within a component by exactly one of `index`, `value`, `path`, or a \
                `row`+`column` pair. Supports JComboBox, JList, JTabbedPane, JTable (whole row \
                via `index`, a single cell via `row`+`column`), JSpinner (value), JTree (path), \
                and menu-path activation on a JMenuBar/JMenu/JPopupMenu. Menu activation \
                may open a modal dialog, so it is dispatched modal-safely (returns promptly with \
                `dispatched`/`edtSettled`, plus the `opened[]`/`closed[]` window delta it caused, \
                or `deltaUnknown: true` when the EDT stayed busy); the other selections complete \
                synchronously.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  Integer index = s.intArgOrNull(request, "index");
                  String value = s.stringArg(request, "value");
                  List<String> path = s.stringListArg(request, "path");
                  Integer row = s.intArgOrNull(request, "row");
                  Integer column = s.intArgOrNull(request, "column");
                  boolean cell = row != null || column != null;
                  int provided =
                      (index != null ? 1 : 0)
                          + (value != null ? 1 : 0)
                          + (path != null ? 1 : 0)
                          + (cell ? 1 : 0);
                  if (provided != 1) {
                    throw new IllegalArgumentException(
                        "select requires exactly one of index, value, path, or row+column (got "
                            + provided
                            + ")");
                  }
                  if (cell && (row == null || column == null)) {
                    throw new IllegalArgumentException(
                        "a cell selection needs both row and column");
                  }
                  java.time.Duration timeout = s.timeout(request, Interactions.DEFAULT_TIMEOUT);
                  Interactions.SelectionResult result;
                  if (index != null) {
                    result = Interactions.selectIndexById(id, index, timeout);
                  } else if (value != null) {
                    result = Interactions.selectValueById(id, value, timeout);
                  } else if (cell) {
                    result = Interactions.selectCellById(id, row, column, timeout);
                  } else {
                    result = Interactions.selectPathById(id, path, timeout);
                  }
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("action", "select");
                  m.put("id", id);
                  m.put("description", result.description());
                  if (result.modalSafeDispatch()) {
                    m.put("dispatched", true);
                    m.put("edtSettled", result.edtSettled());
                    s.applyDelta(m, result.edtSettled(), result.delta());
                  } else {
                    m.put("ok", true);
                  }
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: scroll_to

  McpServerFeatures.SyncToolSpecification scrollTo() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to scroll into view. Required."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("scroll_to", s.objectSchema(properties, "component_id"))
            .title("Scroll a component into view")
            .description(
                """
                Scroll the component's enclosing scroll pane so the component is visible \
                (scrollRectToVisible), e.g. to bring an off-screen row/field on screen before a \
                screenshot or click. Requires a JComponent. (Selecting a table row/list item \
                already scrolls it into view; use this for anything else.)\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  Interactions.scrollToVisibleById(
                      id, s.timeout(request, Interactions.DEFAULT_TIMEOUT));
                  return s.jsonText(s.okMap("scroll_to", id, new LinkedHashMap<>()));
                }));
  }

  // ------------------------------------------------------------------ tool: focus

  McpServerFeatures.SyncToolSpecification focus() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("component_id", s.stringProp("Component handle to focus. Required."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("focus", s.objectSchema(properties, "component_id"))
            .title("Request keyboard focus for a component")
            .description(
                """
                Request keyboard focus for a component (requestFocusInWindow) — useful before a \
                press_key sequence in an app that fights over focus. Focus transfer is \
                asynchronous, so `accepted` reports only whether the request is likely to succeed; \
                confirm with await_idle component_id+state=focused if it matters.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  boolean accepted =
                      Interactions.requestFocusById(
                          id, s.timeout(request, Interactions.DEFAULT_TIMEOUT));
                  Map<String, Object> extra = new LinkedHashMap<>();
                  extra.put("accepted", accepted);
                  return s.jsonText(s.okMap("focus", id, extra));
                }));
  }

  // ------------------------------------------------------------------ tool: close_window

  McpServerFeatures.SyncToolSpecification closeWindow() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "component_id",
        s.stringProp(
            "Handle of the window (or any component within it) to close. Required. A window id "
                + "from list_windows, or a component id whose enclosing window is closed."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("close_window", s.objectSchema(properties, "component_id"))
            .title("Close a window")
            .description(
                """
                Close a window by posting a WINDOW_CLOSING event — the same event the OS \
                close button sends, so the app's own close handling runs (confirm dialogs, \
                WindowListeners, the default close operation). Use it to dismiss dialogs/frames \
                that press_key escape can't. Modal-safe: a close can open a "save changes?" \
                dialog, so it is dispatched without blocking and reports `edtSettled`; follow up \
                with list_windows to confirm the window is gone.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  boolean settled =
                      Interactions.closeWindowById(
                          id, s.timeout(request, Interactions.DEFAULT_TIMEOUT));
                  return s.jsonText(s.dispatchMap("close_window", id, settled));
                }));
  }

  // ------------------------------------------------------------------ tool: to_front

  McpServerFeatures.SyncToolSpecification toFront() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "component_id",
        s.stringProp(
            "Handle of the window (or any component within it) to bring forward. Required."));
    properties.put("timeout_ms", s.timeoutProp());
    McpSchema.Tool tool =
        McpSchema.Tool.builder("to_front", s.objectSchema(properties, "component_id"))
            .title("Bring a window to the front")
            .description(
                """
                Bring a window to the front (Window.toFront), so a subsequent screenshot or \
                focus-sensitive interaction targets the intended window when several overlap. \
                Accepts a window id or any component within the window.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String id = s.requiredStringArg(request, "component_id");
                  Interactions.toFrontById(id, s.timeout(request, Interactions.DEFAULT_TIMEOUT));
                  return s.jsonText(s.okMap("to_front", id, new LinkedHashMap<>()));
                }));
  }

  /** Parses {@code set_text}'s {@code mode}: {@code replace} (default) or {@code append}. */
  private static boolean parseAppendMode(String mode) {
    if (mode == null || mode.equalsIgnoreCase("replace")) {
      return false;
    }
    if (mode.equalsIgnoreCase("append")) {
      return true;
    }
    throw new IllegalArgumentException("unknown mode '" + mode + "' (expected replace or append)");
  }

  // ------------------------------------------------------------------ key / button parsing

  private static Interactions.MouseButton parseButton(String raw) {
    if (raw == null) {
      return Interactions.MouseButton.LEFT;
    }
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "left" -> Interactions.MouseButton.LEFT;
      case "middle" -> Interactions.MouseButton.MIDDLE;
      case "right" -> Interactions.MouseButton.RIGHT;
      default ->
          throw new IllegalArgumentException(
              "unknown button '" + raw + "' (expected left, middle, or right)");
    };
  }

  /**
   * A parsed key press: the {@code VK_*} code, the KEY_TYPED char (or CHAR_UNDEFINED), modifiers.
   */
  private record KeySpec(int keyCode, char keyChar, int modifiers) {}

  /**
   * Parses a {@code key} argument — {@code "mod+mod+key"} — into a {@link KeySpec}. Named keys and
   * function keys carry no typed char; a lone printable character does (so a plain letter still
   * types); any modifier suppresses the typed char so only the KEY_PRESSED drives the binding.
   */
  private static KeySpec parseKey(String raw) {
    String[] tokens = raw.trim().split("\\+", -1);
    int modifiers = 0;
    for (int i = 0; i < tokens.length - 1; i++) {
      modifiers |= parseModifier(tokens[i].trim());
    }
    String key = tokens[tokens.length - 1].trim();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("no key in '" + raw + "'");
    }
    int keyCode;
    char keyChar = KeyEvent.CHAR_UNDEFINED;
    switch (key.toLowerCase(Locale.ROOT)) {
      case "escape", "esc" -> keyCode = KeyEvent.VK_ESCAPE;
      case "enter", "return" -> keyCode = KeyEvent.VK_ENTER;
      case "tab" -> keyCode = KeyEvent.VK_TAB;
      case "space", "spacebar" -> {
        keyCode = KeyEvent.VK_SPACE;
        keyChar = ' ';
      }
      case "backspace" -> keyCode = KeyEvent.VK_BACK_SPACE;
      case "delete", "del" -> keyCode = KeyEvent.VK_DELETE;
      case "insert", "ins" -> keyCode = KeyEvent.VK_INSERT;
      case "home" -> keyCode = KeyEvent.VK_HOME;
      case "end" -> keyCode = KeyEvent.VK_END;
      case "pageup", "page_up", "pgup" -> keyCode = KeyEvent.VK_PAGE_UP;
      case "pagedown", "page_down", "pgdn" -> keyCode = KeyEvent.VK_PAGE_DOWN;
      case "up" -> keyCode = KeyEvent.VK_UP;
      case "down" -> keyCode = KeyEvent.VK_DOWN;
      case "left" -> keyCode = KeyEvent.VK_LEFT;
      case "right" -> keyCode = KeyEvent.VK_RIGHT;
      default -> {
        Integer functionKey = functionKey(key.toLowerCase(Locale.ROOT));
        if (functionKey != null) {
          keyCode = functionKey;
        } else if (key.length() == 1) {
          keyChar = key.charAt(0);
          keyCode = KeyEvent.getExtendedKeyCodeForChar(keyChar);
          if (keyCode == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("no key code for character '" + keyChar + "'");
          }
        } else {
          throw new IllegalArgumentException(
              "unknown key '"
                  + key
                  + "' (use a name like escape/enter/tab/up/f5, a single character, or a chord "
                  + "like ctrl+space)");
        }
      }
    }
    if (modifiers != 0) {
      keyChar = KeyEvent.CHAR_UNDEFINED;
    }
    return new KeySpec(keyCode, keyChar, modifiers);
  }

  private static int parseModifier(String token) {
    return switch (token.toLowerCase(Locale.ROOT)) {
      case "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK;
      case "shift" -> InputEvent.SHIFT_DOWN_MASK;
      case "alt", "option" -> InputEvent.ALT_DOWN_MASK;
      case "meta", "cmd", "command", "super", "win" -> InputEvent.META_DOWN_MASK;
      default ->
          throw new IllegalArgumentException(
              "unknown modifier '" + token + "' (expected ctrl, shift, alt, or meta)");
    };
  }

  /** {@code VK_F1..VK_F24} for a {@code "f<n>"} token, else {@code null}. */
  private static Integer functionKey(String token) {
    if (token.length() < 2 || token.charAt(0) != 'f') {
      return null;
    }
    int n;
    try {
      n = Integer.parseInt(token.substring(1));
    } catch (NumberFormatException e) {
      return null;
    }
    if (n >= 1 && n <= 12) {
      return KeyEvent.VK_F1 + (n - 1);
    }
    if (n >= 13 && n <= 24) {
      return KeyEvent.VK_F13 + (n - 13);
    }
    return null;
  }
}
