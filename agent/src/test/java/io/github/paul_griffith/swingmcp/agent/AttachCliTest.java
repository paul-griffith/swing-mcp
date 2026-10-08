package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for the {@link AttachCli} argument handling and its no-arg listing/usage
 * output. The success attach path (which requires a real target JVM and the shaded jar) is covered
 * end-to-end by the e2e module; here we pin the deterministic, environment-independent behavior.
 */
class AttachCliTest {

  @Test
  void noArgsPrintsUsageAndListsJvms() {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    int code;
    try {
      code = AttachCli.run(new String[0]);
    } finally {
      System.setOut(originalOut);
    }
    String out = captured.toString(StandardCharsets.UTF_8);
    assertEquals(AttachCli.EXIT_OK, code);
    assertTrue(out.contains("Usage:"), "expected usage text, got:\n" + out);
    assertTrue(out.contains("Attachable JVMs"), "expected a JVM listing, got:\n" + out);
  }

  @Test
  void nonNumericPidIsAUsageError() {
    assertEquals(AttachCli.EXIT_USAGE, AttachCli.run(new String[] {"not-a-pid"}));
  }

  @Test
  void emptyPidIsAUsageError() {
    assertEquals(AttachCli.EXIT_USAGE, AttachCli.run(new String[] {""}));
  }

  @Test
  void unknownPidDoesNotSucceed() {
    // A PID that is not an attachable JVM (and, in unit tests, no real agent jar to load) must
    // never report success.
    int code = AttachCli.run(new String[] {"2147483647"});
    assertNotEquals(AttachCli.EXIT_OK, code);
  }
}
