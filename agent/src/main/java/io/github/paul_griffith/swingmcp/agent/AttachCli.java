package io.github.paul_griffith.swingmcp.agent;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line launcher that dynamically attaches the swing-mcp agent to an <b>already-running</b>
 * JVM, for when you did not start the target with {@code -javaagent:} (the {@code premain} path).
 * It is the {@code Main-Class} of the shaded agent jar, so:
 *
 * <pre>{@code
 * # List attachable JVMs on this machine:
 * java -jar swing-mcp-agent.jar
 *
 * # Attach to a target by PID (optionally with the usual agent args):
 * java -jar swing-mcp-agent.jar 12345
 * java -jar swing-mcp-agent.jar 12345 port=0,token=secret
 * }</pre>
 *
 * <p>Attaching uses the JDK Attach API ({@link com.sun.tools.attach.VirtualMachine}, module {@code
 * jdk.attach}) to inject the agent via {@link VirtualMachine#loadAgent(String, String)}, which
 * invokes {@link SwingMcpAgent#agentmain}. That path <b>requires running on a JDK</b> (a plain JRE
 * lacks {@code jdk.attach}); the agent jar itself compiles/runs fine on a JRE for the {@code
 * -javaagent} mode, and only this CLI needs the JDK.
 *
 * <p>The agent it loads is idempotent (see {@link SwingMcpAgent}): attaching to a JVM that already
 * has the agent running logs and returns rather than starting a second server.
 *
 * <p>Exit codes: {@code 0} success; {@code 1} usage error (bad/unknown PID, agent jar not found);
 * {@code 2} attach refused or unsupported here (common in locked-down CI sandboxes — callers may
 * treat this as "skip"); {@code 3} the target JVM rejected the agent.
 */
public final class AttachCli {

  static final int EXIT_OK = 0;
  static final int EXIT_USAGE = 1;
  static final int EXIT_ATTACH_UNAVAILABLE = 2;
  static final int EXIT_ERROR = 3;

  private static final String LOG_PREFIX = "[swing-mcp] ";

  /**
   * System property that overrides where the agent jar is loaded from. Normally the CLI locates the
   * jar it is itself running from; this override is for unusual launch setups.
   */
  static final String AGENT_JAR_PROPERTY = "swingmcp.agentJar";

  private AttachCli() {}

  public static void main(String[] args) {
    int code = run(args);
    if (code != EXIT_OK) {
      System.exit(code);
    }
  }

  /** Runs the CLI and returns a process exit code (does not call {@link System#exit}). */
  static int run(String[] args) {
    if (args.length == 0) {
      return listJvms();
    }
    String pid = args[0].trim();
    String agentArgs = args.length > 1 ? args[1] : "";
    return attach(pid, agentArgs);
  }

  // ------------------------------------------------------------------ list

  private static int listJvms() {
    System.out.println("swing-mcp agent — dynamic attach");
    System.out.println();
    System.out.println("Usage:");
    System.out.println("  java -jar swing-mcp-agent.jar                 list attachable JVMs");
    System.out.println("  java -jar swing-mcp-agent.jar <pid> [args]    attach to a running JVM");
    System.out.println();
    System.out.println("Agent args (comma-separated key=value, all optional):");
    System.out.println("  port=<n>   TCP port (default 8765; 0 = ephemeral)");
    System.out.println("  token=<t>  require Authorization: Bearer <t>");
    System.out.println(
        "  extensions=<paths>  extension jars/directories, separated by " + File.pathSeparator);
    System.out.println();

    List<VirtualMachineDescriptor> vms;
    try {
      vms = VirtualMachine.list();
    } catch (Throwable t) {
      // e.g. jdk.attach missing (running on a JRE) — report cleanly, no stack trace.
      System.out.println(
          "Attachable JVMs: unavailable ("
              + t.getClass().getSimpleName()
              + ": "
              + t.getMessage()
              + "). The attach CLI requires running on a JDK.");
      return EXIT_OK;
    }

    String self = String.valueOf(ProcessHandle.current().pid());
    System.out.println("Attachable JVMs:");
    if (vms.isEmpty()) {
      System.out.println("  (none found)");
      return EXIT_OK;
    }
    for (VirtualMachineDescriptor vm : vms) {
      String marker = vm.id().equals(self) ? "   <- this launcher" : "";
      System.out.printf("  %-8s %s%s%n", vm.id(), displayName(vm), marker);
    }
    return EXIT_OK;
  }

  private static String displayName(VirtualMachineDescriptor vm) {
    String name = vm.displayName();
    return (name == null || name.isBlank()) ? "(unknown)" : name;
  }

  // ------------------------------------------------------------------ attach

  private static int attach(String pid, String agentArgs) {
    if (!pid.chars().allMatch(Character::isDigit) || pid.isEmpty()) {
      error("PID must be numeric; got '" + pid + "'. Run with no arguments to list JVMs.");
      return EXIT_USAGE;
    }
    if (!isAttachable(pid)) {
      error("no attachable JVM with PID " + pid + ". Run with no arguments to list JVMs.");
      return EXIT_USAGE;
    }
    Path agentJar = locateAgentJar();
    if (agentJar == null) {
      return EXIT_USAGE; // message already printed
    }

    VirtualMachine vm;
    try {
      vm = VirtualMachine.attach(pid);
    } catch (AttachNotSupportedException | IOException e) {
      // Refused/unsupported here (e.g. ptrace restrictions in a CI sandbox) — recoverable-skip.
      error(
          "attach to PID "
              + pid
              + " was refused or is unsupported in this environment: "
              + e.getMessage());
      return EXIT_ATTACH_UNAVAILABLE;
    }

    try {
      vm.loadAgent(agentJar.toString(), agentArgs);
      info(
          "loaded agent into PID "
              + pid
              + " from "
              + agentJar
              + "; the target logs '"
              + LOG_PREFIX
              + "listening on http://…/mcp' once the server is up.");
      return EXIT_OK;
    } catch (AgentLoadException | AgentInitializationException e) {
      error("the target JVM rejected the agent: " + e.getMessage());
      return EXIT_ERROR;
    } catch (IOException e) {
      error("failed to load the agent into PID " + pid + ": " + e.getMessage());
      return EXIT_ATTACH_UNAVAILABLE;
    } finally {
      try {
        vm.detach();
      } catch (IOException ignored) {
        // best-effort detach
      }
    }
  }

  /** True if a JVM with {@code pid} appears in {@link VirtualMachine#list()}. */
  private static boolean isAttachable(String pid) {
    try {
      for (VirtualMachineDescriptor vm : VirtualMachine.list()) {
        if (vm.id().equals(pid)) {
          return true;
        }
      }
    } catch (Throwable ignored) {
      // If enumeration itself fails, let the attach attempt surface the real error.
      return true;
    }
    return false;
  }

  /**
   * Locates the agent jar to load: the {@value #AGENT_JAR_PROPERTY} override if set, else the jar
   * this class is running from. Returns {@code null} (after printing a message) when it cannot be
   * resolved to a real jar file — e.g. when invoked from loose classes rather than {@code java
   * -jar}.
   */
  private static Path locateAgentJar() {
    String override = System.getProperty(AGENT_JAR_PROPERTY);
    if (override != null && !override.isBlank()) {
      Path p = Path.of(override.trim());
      if (Files.isRegularFile(p)) {
        return p;
      }
      error("agent jar override " + AGENT_JAR_PROPERTY + "='" + override + "' is not a file.");
      return null;
    }
    try {
      var codeSource = AttachCli.class.getProtectionDomain().getCodeSource();
      if (codeSource != null && codeSource.getLocation() != null) {
        Path p = Path.of(codeSource.getLocation().toURI());
        if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
          return p;
        }
        error(
            "cannot locate the agent jar (running from "
                + p
                + "). "
                + "Run this launcher as: java -jar swing-mcp-agent.jar <pid>");
        return null;
      }
    } catch (URISyntaxException e) {
      error("cannot resolve the agent jar location: " + e.getMessage());
      return null;
    }
    error("cannot locate the agent jar. Run this launcher as: java -jar swing-mcp-agent.jar <pid>");
    return null;
  }

  private static void info(String message) {
    System.out.println(LOG_PREFIX + message);
  }

  private static void error(String message) {
    System.err.println(LOG_PREFIX + message);
  }
}
