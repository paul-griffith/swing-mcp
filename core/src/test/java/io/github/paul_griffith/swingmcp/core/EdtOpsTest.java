package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests proving the test infrastructure and the EDT timeout primitive. These run
 * under {@code -Djava.awt.headless=true} and need no display.
 */
class EdtOpsTest {

  @Test
  void invokeAndWaitRunsTaskOnEdtAndReturnsResult() throws Exception {
    AtomicBoolean ranOnEdt = new AtomicBoolean(false);
    int result =
        EdtOps.invokeAndWait(
            () -> {
              ranOnEdt.set(SwingUtilities.isEventDispatchThread());
              return 21 * 2;
            },
            Duration.ofSeconds(5));

    assertEquals(42, result);
    assertTrue(ranOnEdt.get(), "task should have executed on the Event Dispatch Thread");
  }

  @Test
  void invokeAndWaitTimesOutWhenEdtIsBlocked() throws Exception {
    // Occupy the EDT longer than the timeout so the waiting call must give up.
    SwingUtilities.invokeLater(
        () -> {
          try {
            Thread.sleep(1_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    assertThrows(
        EdtOps.EdtTimeoutException.class,
        () -> EdtOps.invokeAndWait(() -> "never", Duration.ofMillis(50)));
  }

  @Test
  void invokeAndWaitPropagatesTaskException() {
    Exception thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                EdtOps.invokeAndWait(
                    () -> {
                      throw new IllegalStateException("boom");
                    },
                    Duration.ofSeconds(5)));
    assertEquals("boom", thrown.getMessage());
  }

  @Test
  void snapshotRecordHoldsPlainData() {
    // Sanity check the snapshot vocabulary compiles and behaves as a value type.
    ComponentTreeSnapshot leaf =
        new ComponentTreeSnapshot(
            "okButton",
            "okButton",
            ComponentAddress.IdKind.NAME,
            "JButton",
            "javax.swing.JButton",
            "OK",
            ComponentTreeSnapshot.TextSource.BUTTON_TEXT,
            new Bounds(0, 0, 80, 24),
            null,
            true,
            false,
            true,
            true,
            false,
            null,
            0,
            java.util.List.of());
    assertEquals("okButton", leaf.name());
    assertTrue(leaf.children().isEmpty());
    assertFalse(leaf.simpleClassName().isEmpty());
  }
}
