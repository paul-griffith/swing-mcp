package io.github.paul_griffith.swingmcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.modelcontextprotocol.spec.McpSchema;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end check of extensions: the demo app runs with the sample {@code demo-extension} jar
 * passed via {@code extensions=}, so its describer gives the custom-painted {@code StatusIndicator}
 * text the built-in tools can find and wait on, and its {@code demo_status} tool works next to the
 * built-in ones with the same ids and structured errors. A missing extension path is reported and
 * skipped without stopping the agent.
 */
@Tag("headed")
class ExtensionEndToEndTest {

  @Test
  void anExtensionDescribesComponentsAndAddsATool() {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    try (ForkedApp app =
            ForkedApp.launchWithAgentArgs(
                ForkedApp.DEMO_JAVA_MAIN,
                ForkedApp.demoJavaClasspath(),
                "extensions=" + ForkedApp.demoExtensionJar());
        Mcp mcp = Mcp.connect(app.awaitPort(Duration.ofSeconds(30)))) {
      mcp.call("await_idle", Map.of("window_title", "swing-mcp demo", "timeout_ms", 10_000));

      assertTrue(app.logSnapshot().contains("extension 'demo' loaded"), app.logSnapshot());
      List<String> tools = mcp.toolNames();
      assertTrue(tools.contains("demo_status"), "extension tool missing: " + tools);
      assertTrue(tools.contains("click"), "built-in tools are still there: " + tools);

      // The describer: a component with no text of its own now has some, and find matches it.
      JsonNode found = mcp.call("find_components", Map.of("text", "Status: online"));
      assertEquals(1, found.get("count").asInt(), "" + found);
      JsonNode indicator = found.get("matches").get(0);
      assertEquals("statusIndicator", indicator.get("id").asString());
      assertEquals("StatusIndicator", indicator.get("class").asString());

      // The tool, with the same ids the built-in tools use.
      JsonNode status = mcp.call("demo_status", Map.of("component_id", "statusIndicator"));
      assertEquals("online", status.get("state").asString());

      // Describers are live: after a click toggles the light, wait_for sees the new text.
      mcp.call("click", Map.of("component_id", "statusIndicator"));
      JsonNode waited =
          mcp.call(
              "wait_for",
              Map.of(
                  "condition",
                  "text_contains",
                  "name",
                  "statusIndicator",
                  "expect_text",
                  "offline",
                  "timeout_ms",
                  5_000));
      assertTrue(waited.get("met").asBoolean(), "" + waited);
      assertEquals(
          "offline",
          mcp.call("demo_status", Map.of("component_id", "statusIndicator"))
              .get("state")
              .asString());

      // The extension's own error code, and the built-in one for an id that does not resolve.
      JsonNode wrongKind = error(mcp.callRaw("demo_status", Map.of("component_id", "nameField")));
      assertEquals("not_a_status_indicator", wrongKind.get("error").asString());
      assertNotNull(wrongKind.get("hint"));
      JsonNode unknown = error(mcp.callRaw("demo_status", Map.of("component_id", "noSuchThing")));
      assertEquals("component_not_found", unknown.get("error").asString());
    }
  }

  @Test
  void aMissingExtensionPathIsLoggedAndSkipped() {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    try (ForkedApp app =
            ForkedApp.launchWithAgentArgs(
                ForkedApp.DEMO_JAVA_MAIN,
                ForkedApp.demoJavaClasspath(),
                "extensions=/definitely/not/here.jar");
        Mcp mcp = Mcp.connect(app.awaitPort(Duration.ofSeconds(30)))) {
      JsonNode windows =
          mcp.call("await_idle", Map.of("window_title", "swing-mcp demo", "timeout_ms", 10_000));
      assertTrue(windows.get("met").asBoolean(), "the agent works without the extension");
      assertTrue(
          app.logSnapshot().contains("extension path /definitely/not/here.jar does not exist"),
          app.logSnapshot());
    }
  }

  private static JsonNode error(McpSchema.CallToolResult result) {
    assertTrue(Boolean.TRUE.equals(result.isError()), "expected an error: " + result);
    return Mcp.parse(Mcp.firstText(result));
  }
}
