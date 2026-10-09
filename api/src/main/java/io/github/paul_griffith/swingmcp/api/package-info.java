/**
 * The swing-mcp extension API.
 *
 * <p>An extension teaches swing-mcp about an application's own components and adds tools of its
 * own, without forking the agent. It is a class implementing {@link
 * io.github.paul_griffith.swingmcp.api.SwingMcpExtension}, registered as a {@link
 * java.util.ServiceLoader} provider ({@code
 * META-INF/services/io.github.paul_griffith.swingmcp.api.SwingMcpExtension}) and loaded either from
 * the classpath of the JVM the agent runs in or from the jars named by the agent's {@code
 * extensions} argument.
 *
 * <p>At startup the agent calls {@link
 * io.github.paul_griffith.swingmcp.api.SwingMcpExtension#register} once, and the extension
 * contributes through the {@link io.github.paul_griffith.swingmcp.api.ExtensionContext}:
 *
 * <ul>
 *   <li>{@link io.github.paul_griffith.swingmcp.api.ComponentDescriber}s, consulted by the built-in
 *       tools, give a custom component display text (so it shows up meaningfully in {@code
 *       get_component_tree}, {@code find_components}, and {@code wait_for});
 *   <li>{@link io.github.paul_griffith.swingmcp.api.ToolDefinition}s become MCP tools next to the
 *       built-in ones, taking the same component ids and returning the same structured errors.
 * </ul>
 *
 * <p>This package depends on nothing but the JDK and is never relocated in the shaded agent jar.
 * Compile an extension against the agent jar (or this module) as a compile-only dependency.
 */
package io.github.paul_griffith.swingmcp.api;
