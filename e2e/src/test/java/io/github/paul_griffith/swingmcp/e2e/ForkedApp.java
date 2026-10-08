package io.github.paul_griffith.swingmcp.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A forked demo JVM under test. Launches one of the demo apps in a child process — optionally with
 * the shaded {@code -javaagent} — captures its merged stdout/stderr on a reader thread, and parses
 * the agent's {@code [swing-mcp] listening on http://…:<port>/mcp} startup line to learn the
 * ephemeral port the agent bound. {@link #close()} kills the child reliably, so tests can wrap this
 * in try-with-resources (or {@code @AfterAll}) and never leak a JVM even on failure.
 *
 * <p>The port is negotiated, never hardcoded: the agent is launched with {@code port=0} so the OS
 * assigns a free port, and the harness reads the actual value back from the startup line. That also
 * makes the same mechanism work for dynamic attach, where the agent only starts (and logs) after an
 * external attach.
 */
final class ForkedApp implements AutoCloseable {

  static final String DEMO_JAVA_MAIN = "io.github.paul_griffith.swingmcp.demo.DemoApp";
  static final String DEMO_KOTLIN_MAIN =
      "io.github.paul_griffith.swingmcp.demokotlin.DemoKotlinApp";

  /** Matches the agent's startup line, capturing the bound port. */
  private static final Pattern LISTENING =
      Pattern.compile("listening on https?://[^:/]+:(\\d+)/mcp");

  private final Process process;
  private final Thread reader;
  private final StringBuilder log = new StringBuilder();
  private final CompletableFuture<Integer> port = new CompletableFuture<>();

  private ForkedApp(Process process) {
    this.process = process;
    this.reader = new Thread(this::pumpOutput, "forked-app-reader");
    this.reader.setDaemon(true);
    this.reader.start();
  }

  /**
   * Forks the given main class with the shaded agent attached ({@code port=0}), no bearer token.
   */
  static ForkedApp launchWithAgent(String mainClass, String classpath) {
    return launchWithAgent(mainClass, classpath, null);
  }

  /**
   * Forks the given main class with the shaded agent attached at an ephemeral port. When {@code
   * token} is non-null the agent is configured to require {@code Authorization: Bearer token}.
   */
  static ForkedApp launchWithAgent(String mainClass, String classpath, String token) {
    String agentArgs = "port=0" + (token != null ? ",token=" + token : "");
    List<String> command = new ArrayList<>();
    command.add(javaBin());
    command.add("-javaagent:" + agentJar() + "=" + agentArgs);
    command.add("-cp");
    command.add(classpath);
    command.add(mainClass);
    return launch(command);
  }

  /** Forks the given main class WITHOUT any agent (used by the dynamic-attach test). */
  static ForkedApp launchWithoutAgent(String mainClass, String classpath) {
    List<String> command = List.of(javaBin(), "-cp", classpath, mainClass);
    return launch(command);
  }

  private static ForkedApp launch(List<String> command) {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    try {
      return new ForkedApp(builder.start());
    } catch (IOException e) {
      throw new IllegalStateException("failed to launch " + command, e);
    }
  }

  /** The child's OS process id (the handle the attach CLI attaches to). */
  long pid() {
    return process.pid();
  }

  /** Whether the child process is still running. */
  boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Waits until the agent logs its startup line and returns the port it bound. Fails fast if the
   * child process dies first.
   *
   * @throws IllegalStateException on timeout or if the process exits before the agent starts
   */
  int awaitPort(Duration timeout) {
    long deadlineMs = System.currentTimeMillis() + timeout.toMillis();
    while (true) {
      try {
        return port.get(200, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        if (!process.isAlive() && !port.isDone()) {
          throw new IllegalStateException(
              "forked JVM exited (code "
                  + process.exitValue()
                  + ") before the agent started.\n"
                  + logSnapshot());
        }
        if (System.currentTimeMillis() > deadlineMs) {
          throw new IllegalStateException(
              "agent did not report a port within "
                  + timeout.toMillis()
                  + " ms.\n"
                  + logSnapshot());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while awaiting the agent port", e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("failed to obtain the agent port", e);
      }
    }
  }

  /** A snapshot of everything the child has printed so far (for diagnostics on failure). */
  String logSnapshot() {
    synchronized (log) {
      return "----- forked JVM output -----\n" + log + "\n-----------------------------";
    }
  }

  private void pumpOutput() {
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        synchronized (log) {
          log.append(line).append('\n');
        }
        Matcher m = LISTENING.matcher(line);
        if (m.find() && !port.isDone()) {
          port.complete(Integer.parseInt(m.group(1)));
        }
      }
    } catch (IOException ignored) {
      // stream closed on teardown
    }
  }

  @Override
  public void close() {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  // ------------------------------------------------------------------ launch inputs

  /** The {@code java} launcher of the JVM running the tests (a JDK, as the attach CLI needs). */
  static String javaBin() {
    String javaHome = System.getProperty("java.home");
    String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return javaHome + "/bin/" + exe;
  }

  static String agentJar() {
    return requireProperty("swingmcp.agentJar");
  }

  static String demoJavaClasspath() {
    return requireProperty("swingmcp.demoJavaClasspath");
  }

  static String demoKotlinClasspath() {
    return requireProperty("swingmcp.demoKotlinClasspath");
  }

  private static String requireProperty(String key) {
    String value = System.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "missing system property '"
              + key
              + "' (set by e2e/build.gradle.kts; run the e2e tests via Gradle)");
    }
    return value;
  }
}
