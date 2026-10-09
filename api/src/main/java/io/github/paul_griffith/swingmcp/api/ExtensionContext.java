package io.github.paul_griffith.swingmcp.api;

/**
 * What an extension registers its contributions through, during {@link SwingMcpExtension#register}.
 */
public interface ExtensionContext {

  /**
   * The version of this API the agent implements. It is {@code 1} for the API as it is today and
   * increases when the API gains something an extension may want to check for.
   */
  int apiVersion();

  /** The loading agent's version (its jar's {@code Implementation-Version}), or {@code "dev"}. */
  String agentVersion();

  /**
   * Adds a describer the built-in tools consult for every component, before their own rules. See
   * {@link ComponentDescriber}.
   */
  void addDescriber(ComponentDescriber describer);

  /**
   * Adds a tool. Its name must be unique across the built-in tools and every extension's tools; a
   * tool whose name is taken or malformed, or whose schema is not an object schema, is skipped with
   * a logged reason (the rest of the extension still loads).
   */
  void addTool(ToolDefinition tool);

  /** Logs a line to the host's stderr, prefixed with {@code [swing-mcp]} and this extension. */
  void log(String message);
}
