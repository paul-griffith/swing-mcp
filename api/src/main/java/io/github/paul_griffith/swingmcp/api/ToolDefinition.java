package io.github.paul_griffith.swingmcp.api;

import java.util.Map;
import java.util.Objects;

/**
 * An MCP tool an extension adds next to the built-in ones.
 *
 * <p>Prefix the name with something identifying the extension ({@code myapp_open_resource}), so it
 * cannot collide with a built-in tool added later and reads as yours in a client's tool list.
 *
 * @param name the tool name: letters, digits, {@code _}, {@code .}, or {@code -}, 1–128 characters
 * @param title a short human-readable title, or {@code null}
 * @param description what the tool does and returns, written for the model that will call it
 * @param inputSchema a JSON Schema for the arguments, as nested {@link Map}s / {@link
 *     java.util.List}s / strings / numbers / booleans, whose {@code type} is {@code "object"}; or
 *     {@code null} for a tool that takes no arguments
 * @param handler what runs when the tool is called
 */
public record ToolDefinition(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    ToolHandler handler) {

  public ToolDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(handler, "handler");
  }
}
