package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headed integration tests for the window/focus driving primitives ({@link
 * Interactions#closeWindowById}, {@link Interactions#toFrontById}, {@link
 * Interactions#requestFocusById}) that need a real, shown {@link java.awt.Window}. Each builds its
 * own uniquely-named frame and disposes it, so it needs no display fixture shared with the modal
 * test. Self-guarded and tagged {@code headed}; runs under Xvfb on Linux CI.
 */
@Tag("headed")
class InteractionsWindowIntegrationTest {

  @Test
  void closeWindowByIdPostsWindowClosingToTheEnclosingWindow() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");
    AtomicInteger closingCount = new AtomicInteger();
    JFrame frame =
        onEdt(
            () -> {
              JFrame f = new JFrame("closeWindowByIdTest");
              f.setName("closeWindowByIdFrame");
              f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // count, don't auto-dispose
              f.addWindowListener(
                  new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                      closingCount.incrementAndGet();
                    }
                  });
              JButton inner = new JButton("x");
              inner.setName("closeWindowByIdButton");
              f.getContentPane().add(inner);
              f.pack();
              f.setVisible(true);
              return f;
            });
    try {
      // Address a component *inside* the window — close_window climbs to the enclosing window.
      boolean settled =
          Interactions.closeWindowById("closeWindowByIdButton", Interactions.DEFAULT_TIMEOUT);
      assertTrue(settled, "an idle EDT should settle within the grace period");
      boolean fired = waitUntilTrue(() -> closingCount.get() > 0, 3_000);
      assertTrue(fired, "close_window should deliver a WINDOW_CLOSING event");
      assertEquals(1, closingCount.get(), "exactly one WINDOW_CLOSING should be posted");
    } finally {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  @Test
  void toFrontAndFocusByIdResolveThroughTheEnclosingWindow() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");
    JFrame frame =
        onEdt(
            () -> {
              JFrame f = new JFrame("toFrontFocusTest");
              f.setName("toFrontFocusFrame");
              JButton inner = new JButton("Go");
              inner.setName("toFrontFocusButton");
              f.getContentPane().add(inner);
              f.pack();
              f.setVisible(true);
              return f;
            });
    try {
      Interactions.toFrontById("toFrontFocusFrame", Interactions.DEFAULT_TIMEOUT); // no throw
      Interactions.requestFocusById("toFrontFocusButton", Interactions.DEFAULT_TIMEOUT); // no throw

      // A component with no enclosing window is a clear error for the window-scoped drives.
      assertThrows(
          ComponentAddress.ComponentNotFoundException.class,
          () -> Interactions.toFrontById("noSuchComponentAtAll", Interactions.DEFAULT_TIMEOUT));
    } finally {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  private static boolean waitUntilTrue(
      java.util.function.BooleanSupplier condition, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }

  private static <T> T onEdt(java.util.concurrent.Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Exception> error = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(task.call());
          } catch (Exception e) {
            error.set(e);
          }
        });
    if (error.get() != null) {
      throw error.get();
    }
    return result.get();
  }
}
