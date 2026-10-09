package io.github.paul_griffith.swingmcp.api;

import java.util.Map;

/**
 * What runs when an extension's tool is called.
 *
 * <p>The handler runs on one of the agent's request threads, <i>not</i> the Event Dispatch Thread:
 * do Swing work through {@link ToolContext#withComponent} or {@link ToolContext#onEdt}, which run
 * it on the EDT with a timeout.
 *
 * <p>The return value becomes the tool result: a {@link CharSequence} is sent as text verbatim, and
 * anything else (typically a {@link Map} or {@link java.util.List} of strings, numbers, booleans,
 * and nested maps/lists, or {@code null}) is encoded as JSON, like the built-in tools' results.
 *
 * <p>Errors become the same structured {@code {error, message, hint?}} results the built-in tools
 * return: a {@link ToolException} with its own code and hint; an unknown or ambiguous component id,
 * or an EDT timeout, from {@link ToolContext} with the built-in codes ({@code component_not_found},
 * {@code ambiguous_component}, {@code edt_timeout}); an {@link IllegalArgumentException} as {@code
 * invalid_argument}; anything else as {@code internal_error}.
 */
@FunctionalInterface
public interface ToolHandler {

  /**
   * Handles one call.
   *
   * @param arguments the call's arguments, as decoded JSON (never {@code null}; empty when the call
   *     had none)
   * @param context Swing access and component addressing for this call
   * @return the result (see the class documentation)
   * @throws Exception to fail the call (see the class documentation for how errors are reported)
   */
  Object handle(Map<String, Object> arguments, ToolContext context) throws Exception;
}
