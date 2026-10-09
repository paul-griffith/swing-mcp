package io.github.paul_griffith.swingmcp.api;

/**
 * A swing-mcp extension: a {@link java.util.ServiceLoader} provider the agent finds on the
 * classpath or in a jar named by its {@code extensions} argument, and asks once, at startup, to
 * {@link #register} its contributions.
 *
 * <p>Implementations need a public no-argument constructor (the {@link java.util.ServiceLoader}
 * contract). A failing extension never stops the agent: if {@link #name()} or {@link #register}
 * throws, nothing it contributed is used, the failure is logged to stderr with the {@code
 * [swing-mcp]} prefix, and the other extensions and the built-in tools carry on.
 */
public interface SwingMcpExtension {

  /**
   * A short, stable name for logs, the server instructions, and duplicate detection: letters,
   * digits, {@code _}, {@code .}, or {@code -}, at most 64 characters. When two extensions share a
   * name, only the first one found is loaded.
   */
  String name();

  /**
   * Contributes describers and tools through {@code context}. Called once, on the agent's startup
   * thread (not the Event Dispatch Thread), before the server accepts any request. The {@code
   * context} must not be used after this method returns.
   *
   * @param context the registration surface for this extension
   * @throws Exception to abort loading this extension; nothing it registered is kept
   */
  void register(ExtensionContext context) throws Exception;
}
