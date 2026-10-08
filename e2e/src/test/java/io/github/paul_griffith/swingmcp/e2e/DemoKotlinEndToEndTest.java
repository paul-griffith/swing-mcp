package io.github.paul_griffith.swingmcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.modelcontextprotocol.spec.McpSchema;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Smoke test proving a Kotlin-authored Swing UI needs nothing special: forks {@code demo-kotlin}
 * with the agent, then asserts its named components show up in the component tree and a screenshot
 * decodes. Kotlin compiles to the same {@code javax.swing} classes, so the agent drives it exactly
 * like the Java demo. Guarded/headed like the other e2e tests.
 */
@Tag("headed")
class DemoKotlinEndToEndTest {

  private static ForkedApp app;
  private static Mcp mcp;

  @BeforeAll
  static void startAndConnect() {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");
    app = ForkedApp.launchWithAgent(ForkedApp.DEMO_KOTLIN_MAIN, ForkedApp.demoKotlinClasspath());
    int port = app.awaitPort(Duration.ofSeconds(30));
    mcp = Mcp.connect(port);
    JsonNode awaited =
        mcp.call(
            "await_idle", Map.of("window_title", "swing-mcp kotlin demo", "timeout_ms", 10_000));
    assertTrue(
        awaited.get("met").asBoolean(), "kotlin demo frame did not appear:\n" + app.logSnapshot());
  }

  @AfterAll
  static void tearDown() {
    if (mcp != null) {
      mcp.close();
    }
    if (app != null) {
      app.close();
    }
  }

  @Test
  void componentTreeExposesKotlinNamedComponents() {
    JsonNode tree = mcp.call("get_component_tree", Map.of("window_id", "kotlinMainFrame"));
    assertEquals("kotlinMainFrame", tree.get("id").asString());
    assertNotNull(
        DemoJavaEndToEndTest.findById(tree, "kotlinNameField"), "kotlinNameField missing");
    assertNotNull(
        DemoJavaEndToEndTest.findById(tree, "kotlinStatusCombo"), "kotlinStatusCombo missing");
    assertNotNull(
        DemoJavaEndToEndTest.findById(tree, "kotlinResetButton"), "kotlinResetButton missing");
    assertNotNull(DemoJavaEndToEndTest.findById(tree, "kotlinTagList"), "kotlinTagList missing");
  }

  @Test
  void screenshotOfKotlinDemoDecodes() throws Exception {
    McpSchema.CallToolResult result =
        mcp.callRaw("screenshot", Map.of("window_id", "kotlinMainFrame"));
    assertFalse(
        Boolean.TRUE.equals(result.isError()), "screenshot errored: " + Mcp.firstText(result));
    McpSchema.ImageContent image = Mcp.firstImage(result);
    assertNotNull(image, "no image content");
    BufferedImage png =
        ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.data())));
    assertNotNull(png, "image was not decodable PNG");
    assertTrue(
        png.getWidth() > 100 && png.getHeight() > 100,
        "unexpected dimensions " + png.getWidth() + "x" + png.getHeight());
  }
}
