package io.github.paul_griffith.swingmcp.demoextension;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import io.github.paul_griffith.swingmcp.api.ExtensionContext;
import io.github.paul_griffith.swingmcp.api.SwingMcpExtension;
import io.github.paul_griffith.swingmcp.api.ToolDefinition;
import io.github.paul_griffith.swingmcp.api.ToolException;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sample extension for the demo app.
 *
 * <ul>
 *   <li>A {@link ComponentDescriber} gives the demo's custom-painted {@code StatusIndicator} (which
 *       has no text of its own) the display text {@code "Status: online"} / {@code "Status:
 *       offline"}, so it shows up in {@code get_component_tree}, can be found by text, and can be
 *       waited on with {@code wait_for}.
 *   <li>A {@code demo_status} tool reads an indicator's state as structured JSON.
 * </ul>
 *
 * <p>The demo's classes are not on this module's compile path, as an application's usually are not
 * on an extension's: they are reached reflectively through the component the agent hands over.
 */
public final class DemoExtension implements SwingMcpExtension {

  /** The demo's indicator class, matched by name rather than by type. */
  static final String INDICATOR_CLASS = "io.github.paul_griffith.swingmcp.demo.StatusIndicator";

  @Override
  public String name() {
    return "demo";
  }

  @Override
  public void register(ExtensionContext context) {
    ComponentDescriber indicators =
        component -> {
          String state = stateOf(component);
          return state == null ? null : "Status: " + state;
        };
    context.addDescriber(indicators);

    Map<String, Object> idProperty = new LinkedHashMap<>();
    idProperty.put("type", "string");
    idProperty.put("description", "Id of a StatusIndicator (from find_components). Required.");
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of("component_id", idProperty));
    schema.put("required", List.of("component_id"));
    schema.put("additionalProperties", false);

    context.addTool(
        new ToolDefinition(
            "demo_status",
            "Read a status indicator",
            "Reads the state of one of the demo app's StatusIndicator lights. Returns {id, state},"
                + " where state is \"online\" or \"offline\". Errors with"
                + " not_a_status_indicator when the id names some other component.",
            schema,
            (arguments, ctx) -> {
              Object id = arguments.get("component_id");
              if (!(id instanceof String componentId) || componentId.isBlank()) {
                throw new IllegalArgumentException("component_id is required");
              }
              String state = ctx.withComponent(componentId, DemoExtension::stateOf);
              if (state == null) {
                throw new ToolException(
                    "not_a_status_indicator",
                    componentId + " is not a StatusIndicator",
                    "find one with find_components and class StatusIndicator");
              }
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("id", componentId);
              result.put("state", state);
              return result;
            }));
  }

  /**
   * The indicator's state, read reflectively, or {@code null} when {@code component} is not an
   * indicator. Runs on the Event Dispatch Thread (the agent calls describers and {@code
   * withComponent} actions there).
   */
  static String stateOf(Component component) {
    if (!INDICATOR_CLASS.equals(component.getClass().getName())) {
      return null;
    }
    try {
      Object state = component.getClass().getMethod("getState").invoke(component);
      return state == null ? null : state.toString();
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
