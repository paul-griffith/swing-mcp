package io.github.paul_griffith.swingmcp.agent;

import java.util.Locale;

/**
 * Parsed {@code -javaagent} argument string.
 *
 * <p>The argument string is the text after the {@code =} in {@code
 * -javaagent:swing-mcp-agent.jar=port=8765,token=secret}: a comma-separated list of {@code
 * key=value} pairs. Every key is optional and falls back to a default:
 *
 * <ul>
 *   <li>{@code port} — TCP port for the embedded server (default {@value #DEFAULT_PORT}). {@code 0}
 *       is allowed and asks the OS for an ephemeral port.
 *   <li>{@code token} — optional bearer token; when set, requests must present {@code
 *       Authorization: Bearer <token>}. Absent/blank means no auth. Because the argument string is
 *       split on commas, <b>a token cannot contain a comma</b> (any character after a comma starts
 *       a new {@code key=value} pair); all other characters are fine.
 * </ul>
 *
 * <p>The server always binds {@value McpHttpServer#BIND_HOST}; there is deliberately no way to
 * expose it on another interface, so a former {@code bind} key is ignored like any unknown key.
 *
 * <p>Unknown keys are ignored (a warning is logged by the caller). Malformed values (e.g. a
 * non-numeric or out-of-range {@code port}) raise {@link IllegalArgumentException}.
 *
 * @param port the TCP port to bind (0–65535)
 * @param token the bearer token to require, or {@code null} for no authentication
 */
public record AgentConfig(int port, String token) {

  /** Default port when {@code port=} is not supplied. */
  public static final int DEFAULT_PORT = 8765;

  /** The default configuration: {@link #DEFAULT_PORT}, no token. */
  public static AgentConfig defaults() {
    return new AgentConfig(DEFAULT_PORT, null);
  }

  /**
   * Parses an agent argument string. {@code null} or blank yields {@link #defaults()}.
   *
   * @param agentArgs the raw argument string (text after {@code =}), possibly {@code null}
   * @return the parsed configuration
   * @throws IllegalArgumentException if a value is malformed (e.g. a bad {@code port})
   */
  public static AgentConfig parse(String agentArgs) {
    int port = DEFAULT_PORT;
    String token = null;

    if (agentArgs != null && !agentArgs.isBlank()) {
      for (String pair : agentArgs.split(",")) {
        if (pair.isBlank()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = (eq >= 0 ? pair.substring(0, eq) : pair).trim().toLowerCase(Locale.ROOT);
        String value = eq >= 0 ? pair.substring(eq + 1).trim() : "";
        switch (key) {
          case "port" -> port = parsePort(value);
          case "token" -> token = value.isBlank() ? null : value;
          default -> SwingMcpAgent.log("ignoring unknown agent argument '" + key + "'");
        }
      }
    }
    return new AgentConfig(port, token);
  }

  private static int parsePort(String value) {
    int port;
    try {
      port = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("port must be an integer, got '" + value + "'", e);
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be in 0..65535, got " + port);
    }
    return port;
  }

  /** {@code true} when a bearer token is configured and must be presented by clients. */
  public boolean hasToken() {
    return token != null && !token.isBlank();
  }
}
