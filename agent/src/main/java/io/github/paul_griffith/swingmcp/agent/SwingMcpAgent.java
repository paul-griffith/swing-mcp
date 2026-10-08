package io.github.paul_griffith.swingmcp.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java agent entry point for swing-mcp.
 *
 * <p>The agent runs inside the target Swing application's JVM and starts an embedded Jetty server
 * exposing an MCP Streamable HTTP endpoint bound to a loopback address (default {@code
 * 127.0.0.1:8765}). Coding agents connect with, e.g.:
 *
 * <pre>{@code claude mcp add --transport http swing http://localhost:8765/mcp}</pre>
 *
 * <p>Startup is deliberately defensive: it runs on a background <b>daemon</b> thread so {@code
 * premain} returns immediately and the host application's {@code main} is never delayed, and any
 * startup failure is logged to stderr (with a {@code [swing-mcp]} prefix) and swallowed so the
 * agent can never crash or hang the application it is observing.
 *
 * <p>Two attach modes share the same startup path:
 *
 * <ul>
 *   <li>{@link #premain(String, Instrumentation)} — {@code
 *       -javaagent:swing-mcp-agent.jar=port=8765} supplied at JVM launch (the primary mode).
 *   <li>{@link #agentmain(String, Instrumentation)} — dynamic attach via the Attach API to an
 *       already-running JVM.
 * </ul>
 */
public final class SwingMcpAgent {

  private static final String LOG_PREFIX = "[swing-mcp] ";

  /**
   * Guards against starting the server twice. Both attach modes funnel through {@link #start}, so a
   * second {@code premain}/{@code agentmain} (e.g. dynamically attaching to a JVM that was already
   * launched with {@code -javaagent}, or attaching twice) is a no-op rather than a second bind
   * attempt. Reset to {@code false} if startup fails, so a later attach can retry.
   */
  private static final AtomicBoolean STARTED = new AtomicBoolean(false);

  private SwingMcpAgent() {}

  /**
   * Invoked before the application's {@code main} when launched with {@code -javaagent:}.
   *
   * @param agentArgs argument string after {@code =} (e.g. {@code port=8765,token=...})
   * @param inst instrumentation handle provided by the JVM (unused for now)
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    start(agentArgs);
  }

  /**
   * Invoked when the agent is dynamically attached to a running JVM via the Attach API. Shares the
   * {@code premain} startup path.
   *
   * @param agentArgs argument string passed by the attaching process
   * @param inst instrumentation handle provided by the JVM (unused for now)
   */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    start(agentArgs);
  }

  /**
   * Starts the MCP server on a background daemon thread. Returns immediately; any failure is logged
   * and swallowed so the host application is never affected. Idempotent: a second call (e.g.
   * attaching to a JVM that already has the agent) logs and returns without starting a second
   * server.
   */
  private static void start(String agentArgs) {
    if (!STARTED.compareAndSet(false, true)) {
      log("agent already running in this JVM; ignoring duplicate attach");
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              try {
                AgentConfig config = AgentConfig.parse(agentArgs);
                McpHttpServer server = McpHttpServer.start(config);
                log(
                    "listening on http://"
                        + McpHttpServer.BIND_HOST
                        + ":"
                        + server.port()
                        + McpHttpServer.MCP_PATH
                        + (config.hasToken() ? " (bearer token required)" : ""));
              } catch (Throwable t) {
                STARTED.set(false); // allow a later attach to retry after a failed start
                logError("failed to start MCP server: " + t, t);
              }
            },
            "swing-mcp-agent-bootstrap");
    thread.setDaemon(true);
    thread.start();
  }

  /** Logs an informational message to stderr with the {@code [swing-mcp]} prefix. */
  static void log(String message) {
    System.err.println(LOG_PREFIX + message);
  }

  /** Logs an error to stderr with the {@code [swing-mcp]} prefix and prints the stack trace. */
  static void logError(String message, Throwable cause) {
    System.err.println(LOG_PREFIX + message);
    if (cause != null) {
      cause.printStackTrace();
    }
  }
}
