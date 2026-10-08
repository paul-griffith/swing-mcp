package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.core.EdtDiagnostics;
import io.github.paul_griffith.swingmcp.core.Introspection;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The waiting/diagnostic tools: {@code await_idle} (flush / wait-for-condition), {@code wait_for}
 * (find-filtered waiting: poll until a component appears/disappears/enables/shows text), and {@code
 * edt_stack} (responsiveness probe + stack dump), over the shared {@link ToolSupport}. These are
 * the follow-up tools after a modal-safe {@code click} / {@code select} reports an unknown delta or
 * an async open has not landed yet.
 */
final class DiagnosticTools {

  private final ToolSupport s;

  DiagnosticTools(ToolSupport support) {
    this.s = support;
  }

  // ------------------------------------------------------------------ tool: await_idle

  McpServerFeatures.SyncToolSpecification awaitIdle() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "timeout_ms", s.intProp("Maximum time to wait, in milliseconds (default 5000).", 1));
    properties.put(
        "window_title",
        s.stringProp(
            "Wait until a visible window's title contains this substring (case-insensitive)."));
    properties.put(
        "component_id",
        s.stringProp(
            "Wait until this component reaches `state`, or (with `text`) shows that text. "
                + "Combine with `state` or `text`."));
    properties.put(
        "state",
        s.enumStringProp(
            "Component state to wait for (default showing). Used with component_id.",
            "visible",
            "showing",
            "enabled",
            "focused"));
    properties.put(
        "text",
        s.stringProp(
            "Wait until the component's display text contains this substring (case-insensitive) — "
                + "the natural way to await an async status update. Used with component_id; takes "
                + "precedence over state."));
    McpSchema.Tool tool =
        McpSchema.Tool.builder("await_idle", s.objectSchema(properties))
            .title("Wait for the EDT to settle or a condition")
            .description(
                """
                Wait for the EDT. With no condition, flush it (process everything already \
                queued) and return. With `window_title`, wait until a visible window's title \
                contains it. With `component_id`, wait until the component shows `text` (a \
                substring) or reaches `state` (visible/showing/enabled/focused). Reports whether \
                the condition was met and the elapsed time. This is the follow-up after a \
                modal-safe click.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  Duration timeout = Duration.ofMillis(s.intArg(request, "timeout_ms", 5000));
                  String windowTitle = s.stringArg(request, "window_title");
                  String componentId = s.stringArg(request, "component_id");

                  EdtDiagnostics.AwaitResult result;
                  String condition;
                  String awaitText = s.stringArg(request, "text");
                  if (windowTitle != null) {
                    result = EdtDiagnostics.awaitWindowTitle(windowTitle, timeout);
                    condition = "window title contains '" + windowTitle + "'";
                  } else if (componentId != null && awaitText != null) {
                    result = EdtDiagnostics.awaitComponentText(componentId, awaitText, timeout);
                    condition = componentId + " text contains '" + awaitText + "'";
                  } else if (componentId != null) {
                    String stateArg = s.stringArg(request, "state");
                    EdtDiagnostics.ComponentState state =
                        EdtDiagnostics.ComponentState.parse(
                            stateArg != null ? stateArg : "showing");
                    if (state == null) {
                      throw new IllegalArgumentException(
                          "unknown state '"
                              + stateArg
                              + "' (expected visible, showing, enabled, or focused)");
                    }
                    result = EdtDiagnostics.awaitComponentState(componentId, state, timeout);
                    condition = componentId + " is " + state.name().toLowerCase(Locale.ROOT);
                  } else {
                    result = EdtDiagnostics.flush(timeout);
                    condition = null;
                  }

                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("met", result.met());
                  m.put("elapsedMs", result.elapsedMs());
                  if (!result.met()) {
                    m.put("timedOut", true);
                  }
                  if (result.flushOnly()) {
                    m.put("flushed", true);
                  } else {
                    m.put("condition", condition);
                  }
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: wait_for

  McpServerFeatures.SyncToolSpecification waitFor() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "condition",
        s.enumStringProp(
            "What to wait for, evaluated against the components matching the filters: `appears` "
                + "(at least one match), `disappears` (zero matches), `enabled` (a match is "
                + "enabled), `text_contains` (a match's display text contains `expect_text`). "
                + "Required.",
            "appears",
            "disappears",
            "enabled",
            "text_contains"));
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
        s.booleanProp("When true, match only input widgets (same set as find_components)."));
    properties.put(
        "window_id",
        s.stringProp("Restrict the search to this window's subtree (id from list_windows)."));
    properties.put(
        "component_id",
        s.stringProp(
            "Restrict the search to this component's subtree; takes precedence over window_id. "
                + "When neither is given, all windows are searched."));
    properties.put(
        "expect_text",
        s.stringProp(
            "With condition=text_contains: the substring the match's display text must contain "
                + "(case-insensitive). Required for that condition."));
    properties.put(
        "timeout_ms",
        s.intProp(
            "Maximum time to wait, in milliseconds (default "
                + Introspection.DEFAULT_WAIT_TIMEOUT.toMillis()
                + ").",
            1));
    properties.put(
        "poll_ms",
        s.intProp(
            "Poll interval in milliseconds (default "
                + Introspection.DEFAULT_WAIT_POLL.toMillis()
                + ", minimum "
                + Introspection.MIN_WAIT_POLL.toMillis()
                + ").",
            1));
    McpSchema.Tool tool =
        McpSchema.Tool.builder("wait_for", s.objectSchema(properties, "condition"))
            .title("Wait for a component to appear/disappear/enable/show text")
            .description(
                """
                Poll until the components matching the find_components-style filters (`name`, \
                `class`, `text`, `interactable`, AND-combined; at least one required) satisfy \
                `condition`. The `window_id`/`component_id` scope is re-resolved on every poll \
                and a scope that does not resolve (yet) counts as zero matches — so you can wait \
                for a component inside a dialog that has not opened. Multi-match semantics: the \
                first match satisfying the condition wins and is returned as `match`; \
                `disappears` requires zero matches overall. Returns `{met: true, elapsedMs, \
                match?}` on success, `{met: false, elapsedMs, lastCount, timedOut}` on timeout — \
                a timeout is NOT an error; branch on `met` (matching await_idle). Use this for \
                slow/async UI the click's `opened[]` delta missed; use await_idle for EDT-settle \
                waits or state checks on a known component id.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  String conditionArg = s.requiredStringArg(request, "condition");
                  Introspection.WaitCondition condition =
                      Introspection.WaitCondition.parse(conditionArg);
                  if (condition == null) {
                    throw new IllegalArgumentException(
                        "unknown condition '"
                            + conditionArg
                            + "' (expected appears, disappears, enabled, or text_contains)");
                  }
                  Introspection.FindCriteria criteria =
                      new Introspection.FindCriteria(
                          s.stringArg(request, "name"),
                          s.stringArg(request, "class"),
                          s.stringArg(request, "text"),
                          s.boolArg(request, "interactable"));
                  String scopeId =
                      s.firstNonBlank(
                          s.stringArg(request, "component_id"), s.stringArg(request, "window_id"));
                  Duration timeout =
                      Duration.ofMillis(
                          s.intArg(
                              request,
                              "timeout_ms",
                              (int) Introspection.DEFAULT_WAIT_TIMEOUT.toMillis()));
                  Duration poll =
                      Duration.ofMillis(
                          s.intArg(
                              request,
                              "poll_ms",
                              (int) Introspection.DEFAULT_WAIT_POLL.toMillis()));
                  Introspection.WaitResult result =
                      Introspection.waitFor(
                          scopeId,
                          criteria,
                          condition,
                          s.stringArg(request, "expect_text"),
                          timeout,
                          poll);
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("met", result.met());
                  m.put("elapsedMs", result.elapsedMs());
                  if (result.match() != null) {
                    m.put("match", s.matchMap(result.match()));
                  }
                  if (!result.met()) {
                    m.put("timedOut", true);
                    m.put("lastCount", result.lastCount());
                  }
                  return s.jsonText(m);
                }));
  }

  // ------------------------------------------------------------------ tool: edt_stack

  McpServerFeatures.SyncToolSpecification edtStack() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "threshold_ms",
        s.intProp(
            "Latency threshold for the responsive/blocked verdict, in milliseconds (default 200).",
            1));
    McpSchema.Tool tool =
        McpSchema.Tool.builder("edt_stack", s.objectSchema(properties))
            .title("Diagnose EDT responsiveness + stack")
            .description(
                """
                Diagnose the Event Dispatch Thread: post a no-op and measure how long it takes \
                to run (responsiveness), then dump the AWT-EventQueue thread's current stack \
                trace — read from outside the EDT, so it works and is most useful when the EDT \
                is fully blocked. Returns a responsive/blocked verdict, the latency, and the \
                stack. Use it when a click reported the EDT busy/blocked or the UI seems \
                frozen.\
                """)
            .build();
    return ToolSupport.spec(
        tool,
        (exchange, request) ->
            s.guarded(
                () -> {
                  Duration threshold = Duration.ofMillis(s.intArg(request, "threshold_ms", 200));
                  EdtDiagnostics.EdtStatus status = EdtDiagnostics.probeStack(threshold);
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("verdict", status.responsive() ? "responsive" : "blocked");
                  m.put("responsive", status.responsive());
                  m.put("latencyMs", status.latencyMs());
                  m.put("thresholdMs", status.thresholdMs());
                  m.put("edtFound", status.edtFound());
                  s.putIfNotNull(m, "threadName", status.threadName());
                  m.put("stack", status.stack());
                  return s.jsonText(m);
                }));
  }
}
