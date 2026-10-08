package io.github.paul_griffith.swingmcp.agent;

import java.net.URI;
import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * The loopback-only security policy for the MCP endpoint, expressed as a pure function of the
 * relevant request headers so it is trivially unit-testable without a servlet container.
 *
 * <p>Three checks, applied in order (per the MCP spec's DNS-rebinding guidance plus an optional
 * bearer token):
 *
 * <ol>
 *   <li><b>Host</b> — must be present and name a loopback host on the configured port. A remote
 *       {@code Host} (or a wrong port) is rejected with {@code 403}.
 *   <li><b>Origin</b> — if present, its host must be a loopback name; anything else (e.g. a
 *       malicious page at {@code http://evil.example}) is rejected with {@code 403}. A missing
 *       {@code Origin} is fine (non-browser MCP clients omit it).
 *   <li><b>Authorization</b> — only when a token is configured: a missing/blank or non-Bearer
 *       header is {@code 401}; a Bearer token that does not match is {@code 403}.
 * </ol>
 *
 * <p>{@link LoopbackSecurityFilter} adapts this to a {@link jakarta.servlet.Filter}.
 */
final class SecurityPolicy {

  private final IntSupplier port;
  private final String token;

  /**
   * @param port supplies the expected {@code Host} port; a supplier (not a constant) so an
   *     ephemeral {@code port=0} can be validated against the actually-bound port
   * @param token the required bearer token, or {@code null} for no authentication
   */
  SecurityPolicy(IntSupplier port, String token) {
    this.port = port;
    this.token = token;
  }

  /** Convenience for a fixed expected port (used in tests). */
  SecurityPolicy(int port, String token) {
    this(() -> port, token);
  }

  /**
   * Outcome of evaluating a request: {@link #allowed()} or an HTTP {@link #status()} with a reason.
   */
  record Decision(boolean allowed, int status, String reason) {
    static final Decision ALLOW = new Decision(true, 200, "ok");

    static Decision deny(int status, String reason) {
      return new Decision(false, status, reason);
    }
  }

  /**
   * Evaluates a request from its {@code Host}, {@code Origin}, and {@code Authorization} header
   * values (any of which may be {@code null}).
   */
  Decision evaluate(String host, String origin, String authorization) {
    Decision hostDecision = checkHost(host);
    if (!hostDecision.allowed()) {
      return hostDecision;
    }
    Decision originDecision = checkOrigin(origin);
    if (!originDecision.allowed()) {
      return originDecision;
    }
    return checkAuthorization(authorization);
  }

  private Decision checkHost(String host) {
    if (host == null || host.isBlank()) {
      return Decision.deny(403, "missing Host header");
    }
    URI authority;
    try {
      authority = URI.create("//" + host.trim());
    } catch (IllegalArgumentException e) {
      return Decision.deny(403, "malformed Host header '" + host + "'");
    }
    String hostName = authority.getHost();
    if (hostName == null || !isLoopback(hostName)) {
      return Decision.deny(403, "non-loopback Host '" + host + "'");
    }
    // A missing port in the header parses as -1; require it to match the configured port so a
    // request forged for a different service on this loopback interface is still rejected.
    int expectedPort = port.getAsInt();
    if (authority.getPort() != expectedPort) {
      return Decision.deny(
          403, "Host port mismatch in '" + host + "' (expected " + expectedPort + ")");
    }
    return Decision.ALLOW;
  }

  private Decision checkOrigin(String origin) {
    if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
      return Decision.ALLOW; // non-browser clients omit Origin (or send the opaque "null")
    }
    String hostName;
    try {
      hostName = URI.create(origin.trim()).getHost();
    } catch (IllegalArgumentException e) {
      return Decision.deny(403, "malformed Origin '" + origin + "'");
    }
    if (hostName == null || !isLoopback(hostName)) {
      return Decision.deny(403, "non-loopback Origin '" + origin + "'");
    }
    return Decision.ALLOW;
  }

  private Decision checkAuthorization(String authorization) {
    if (token == null) {
      return Decision.ALLOW;
    }
    if (authorization == null || authorization.isBlank()) {
      return Decision.deny(401, "missing Authorization header");
    }
    String header = authorization.trim();
    String prefix = "bearer ";
    if (header.length() <= prefix.length()
        || !header.substring(0, prefix.length()).toLowerCase(Locale.ROOT).equals(prefix)) {
      return Decision.deny(401, "Authorization header is not a Bearer token");
    }
    String presented = header.substring(prefix.length()).trim();
    if (!constantTimeEquals(presented, token)) {
      return Decision.deny(403, "invalid bearer token");
    }
    return Decision.ALLOW;
  }

  private static boolean isLoopback(String hostName) {
    String h = stripBrackets(hostName);
    if (h == null) {
      return false;
    }
    return h.equalsIgnoreCase("localhost")
        || isIpv4Loopback(h)
        || h.equals("::1")
        || h.equals("0:0:0:0:0:0:0:1");
  }

  /**
   * Whether {@code h} is a dotted-quad IPv4 literal in the {@code 127.0.0.0/8} loopback block — the
   * only {@code 127.*} strings that are genuinely loopback.
   *
   * <p>Deliberately does <b>not</b> match a resolvable hostname like {@code 127.evil.com} or {@code
   * 127.0.0.1.evil.com}: a bare {@code startsWith("127.")} would admit those, and an attacker who
   * controls such a name can rebind its DNS to {@code 127.0.0.1} and then reach this endpoint with
   * a matching {@code Host}/{@code Origin} — the exact DNS-rebinding attack this filter exists to
   * stop. Only the four-decimal-octet literal form (first octet {@code 127}, each octet 0–255) is
   * accepted, and no name resolution is ever performed.
   */
  private static boolean isIpv4Loopback(String h) {
    String[] octets = h.split("\\.", -1);
    if (octets.length != 4 || !octets[0].equals("127")) {
      return false;
    }
    for (String octet : octets) {
      if (octet.isEmpty() || octet.length() > 3) {
        return false;
      }
      int value = 0;
      for (int i = 0; i < octet.length(); i++) {
        char c = octet.charAt(i);
        if (c < '0' || c > '9') {
          return false;
        }
        value = value * 10 + (c - '0');
      }
      if (value > 255) {
        return false;
      }
    }
    return true;
  }

  private static String stripBrackets(String h) {
    if (h != null && h.startsWith("[") && h.endsWith("]")) {
      return h.substring(1, h.length() - 1);
    }
    return h;
  }

  /** Length-aware constant-time compare, to avoid leaking the token via response timing. */
  private static boolean constantTimeEquals(String a, String b) {
    byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return java.security.MessageDigest.isEqual(x, y);
  }
}
