package io.github.paul_griffith.swingmcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.modelcontextprotocol.client.McpSyncClient;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end security checks against the running agent: the loopback filter rejects a spoofed
 * (non-loopback) {@code Origin} with 403 (DNS-rebinding guidance), and a token-configured agent
 * requires a valid {@code Authorization: Bearer} header — the MCP handshake fails without it and
 * succeeds with it. Headed/guarded like the other e2e tests (the demo apps need a display to
 * start).
 */
@Tag("headed")
class SecurityEndToEndTest {

  @Test
  void spoofedOriginIsRejectedWith403() throws Exception {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    try (ForkedApp app =
        ForkedApp.launchWithAgent(ForkedApp.DEMO_JAVA_MAIN, ForkedApp.demoJavaClasspath())) {
      int port = app.awaitPort(Duration.ofSeconds(30));

      HttpClient http = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("Origin", "http://evil.example")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
              .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(
          403,
          response.statusCode(),
          "a non-loopback Origin must be rejected; body=" + response.body());
      assertTrue(response.body().contains("swing-mcp"), "expected the filter's reason body");
    }
  }

  @Test
  void tokenConfiguredAgentRequiresBearerAuth() {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    String token = "s3cr3t-token";
    try (ForkedApp app =
        ForkedApp.launchWithAgent(ForkedApp.DEMO_JAVA_MAIN, ForkedApp.demoJavaClasspath(), token)) {
      int port = app.awaitPort(Duration.ofSeconds(30));

      // Without the token, the very first request (initialize) is rejected, so the handshake fails.
      McpSyncClient noAuth = Mcp.buildClient(port, null);
      assertThrows(
          Exception.class,
          noAuth::initialize,
          "initialize should fail without the required bearer token");
      noAuth.closeGracefully();

      // With the token, the session initializes and tools work.
      try (Mcp mcp = Mcp.connect(port, token)) {
        mcp.call("await_idle", Map.of("window_title", "swing-mcp demo", "timeout_ms", 10_000));
        JsonNode windows = mcp.call("list_windows", Map.of()).get("windows");
        assertTrue(
            windows != null && windows.isArray() && !windows.isEmpty(),
            "list_windows should work with the token");
      }
    }
  }
}
