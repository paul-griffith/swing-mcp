package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for {@link EdtDiagnostics}. The AWT Event Dispatch Thread exists even in
 * headless mode (it starts as soon as an event is posted), so both {@code await_idle} and the
 * {@code edt_stack} responsiveness/stack probe are exercisable without a display.
 */
class EdtDiagnosticsTest {

  // ------------------------------------------------------------------ await_idle

  @Test
  void flushReturnsMetWhenEdtIsIdle() {
    EdtDiagnostics.AwaitResult result = EdtDiagnostics.flush(Duration.ofSeconds(2));
    assertTrue(result.met(), "an idle EDT should flush within the timeout");
    assertTrue(result.flushOnly(), "no condition means flush-only");
    assertTrue(result.elapsedMs() >= 0);
  }

  @Test
  void awaitComponentStateTimesOutWhenConditionNeverHolds() {
    // No such component exists, so the SHOWING condition can never be met → timeout.
    EdtDiagnostics.AwaitResult result =
        EdtDiagnostics.awaitComponentState(
            "noSuchComponentId", EdtDiagnostics.ComponentState.SHOWING, Duration.ofMillis(200));
    assertFalse(result.met(), "an unsatisfiable condition should time out");
    assertFalse(result.flushOnly());
    assertTrue(result.elapsedMs() >= 150, "should have waited roughly the full timeout");
  }

  @Test
  void awaitWindowTitleTimesOutHeadlessWithNoWindows() {
    EdtDiagnostics.AwaitResult result =
        EdtDiagnostics.awaitWindowTitle("nonexistent", Duration.ofMillis(150));
    assertFalse(result.met());
  }

  @Test
  void componentStateParseIsCaseInsensitiveAndNullForUnknown() {
    org.junit.jupiter.api.Assertions.assertEquals(
        EdtDiagnostics.ComponentState.FOCUSED, EdtDiagnostics.ComponentState.parse("Focused"));
    org.junit.jupiter.api.Assertions.assertNull(EdtDiagnostics.ComponentState.parse("bogus"));
  }

  // ------------------------------------------------------------------ edt_stack

  @Test
  void probeReportsResponsiveAndAStackWhenEdtIsIdle() {
    EdtDiagnostics.EdtStatus status = EdtDiagnostics.probeStack(Duration.ofMillis(500));
    assertTrue(status.edtFound(), "the AWT-EventQueue thread should be located");
    assertTrue(status.responsive(), "an idle EDT should answer within the threshold");
    assertNotNull(status.threadName());
    assertTrue(status.threadName().startsWith("AWT-EventQueue"));
    assertFalse(status.stack().isEmpty(), "a live thread always has stack frames");
  }

  @Test
  void probeReportsBlockedWithAStackWhenEdtIsWedged() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    // Wedge the EDT until we release it.
    SwingUtilities.invokeLater(
        () -> {
          blocking.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(blocking.await(2, TimeUnit.SECONDS), "the blocking task should have started");

    try {
      EdtDiagnostics.EdtStatus status = EdtDiagnostics.probeStack(Duration.ofMillis(100));
      assertTrue(status.edtFound());
      assertFalse(status.responsive(), "a wedged EDT is not responsive");
      assertTrue(status.latencyMs() >= 100, "latency should be at least the wait cap");
      assertFalse(
          status.stack().isEmpty(),
          "the stack must be captured from outside the EDT even while it is blocked");
    } finally {
      release.countDown(); // free the EDT for later tests
    }
    // Ensure the EDT has drained before other tests run.
    EdtDiagnostics.flush(Duration.ofSeconds(2));
  }
}
