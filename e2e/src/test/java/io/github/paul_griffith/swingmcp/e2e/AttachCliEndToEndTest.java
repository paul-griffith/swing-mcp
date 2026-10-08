package io.github.paul_griffith.swingmcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Exercises the dynamic-attach path end to end: fork a demo JVM <b>without</b> any agent, run the
 * shaded jar's attach CLI ({@code java -jar swing-mcp-agent.jar <pid> port=0,...}) against its PID,
 * then connect the MCP client to the now-attached agent and confirm a tool works.
 *
 * <p>Headed/guarded like the rest. Additionally, dynamic attach can be refused in locked-down CI
 * sandboxes (e.g. ptrace restrictions); the CLI signals that with exit code 2, and this test skips
 * (rather than fails) in that case.
 */
@Tag("headed")
class AttachCliEndToEndTest {

  /**
   * Mirrors {@code AttachCli.EXIT_ATTACH_UNAVAILABLE} (that constant lives in the agent module).
   */
  private static final int EXIT_ATTACH_UNAVAILABLE = 2;

  @Test
  void attachesToARunningJvmThenDrivesItOverMcp() throws Exception {
    assumeFalse(
        GraphicsEnvironment.isHeadless(), "no display available; skipping headed e2e tests");

    try (ForkedApp app =
        ForkedApp.launchWithoutAgent(ForkedApp.DEMO_JAVA_MAIN, ForkedApp.demoJavaClasspath())) {
      long pid = app.pid();
      // Give the target a moment to boot so its attach listener is registered.
      Thread.sleep(2000);
      assertTrue(app.isAlive(), "demo JVM died before attach:\n" + app.logSnapshot());

      AttachResult attach = runAttachCli(pid);
      assumeTrue(
          attach.exitCode != EXIT_ATTACH_UNAVAILABLE,
          "dynamic attach unavailable in this environment: " + attach.output);
      assertEquals(
          0,
          attach.exitCode,
          "attach CLI failed (exit "
              + attach.exitCode
              + "):\n"
              + attach.output
              + "\n--- target output ---\n"
              + app.logSnapshot());

      // The agent, loaded by the attach, now logs its port into the target's output.
      int port = app.awaitPort(Duration.ofSeconds(20));
      try (Mcp mcp = Mcp.connect(port)) {
        mcp.call("await_idle", Map.of("window_title", "swing-mcp demo", "timeout_ms", 10_000));
        JsonNode windows = mcp.call("list_windows", Map.of()).get("windows");
        JsonNode frame = null;
        for (JsonNode w : windows) {
          if ("mainFrame".equals(w.get("id").asString())) {
            frame = w;
          }
        }
        assertNotNull(
            frame, "list_windows should report mainFrame after dynamic attach: " + windows);
        assertTrue(frame.get("showing").asBoolean());

        // Attaching a second time is idempotent: the CLI succeeds and the already-running
        // agent logs and returns rather than starting a second server.
        AttachResult second = runAttachCli(pid);
        assertEquals(0, second.exitCode, "re-attach should succeed:\n" + second.output);
        boolean idempotent =
            pollUntil(
                () -> app.logSnapshot().contains("already running in this JVM"),
                Duration.ofSeconds(5));
        assertTrue(idempotent, "duplicate attach should be a no-op:\n" + app.logSnapshot());
      }
    }
  }

  private static boolean pollUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }

  /** Runs {@code java -jar <agentJar> <pid> port=0} and captures its exit + output. */
  private static AttachResult runAttachCli(long pid) throws IOException, InterruptedException {
    List<String> command =
        List.of(ForkedApp.javaBin(), "-jar", ForkedApp.agentJar(), String.valueOf(pid), "port=0");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("attach CLI did not finish within 30s; output:\n" + output);
    }
    return new AttachResult(process.exitValue(), output);
  }

  private record AttachResult(int exitCode, String output) {}
}
