package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.paul_griffith.swingmcp.demo.DemoApp;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headed integration test for the modal-dialog rule: clicking a button that opens an
 * <b>application-modal</b> {@link javax.swing.JDialog} must not deadlock the caller, and the dialog
 * must become visible afterward. Skips cleanly with no display (self-guarded) and is tagged {@code
 * headed}; on Linux CI it runs under Xvfb.
 */
@Tag("headed")
class InteractionsModalIntegrationTest {

  private static JFrame frame;

  @BeforeAll
  static void showDemo() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");
    frame =
        onEdt(
            () -> {
              JFrame f = DemoApp.createFrame();
              f.setVisible(true);
              return f;
            });
    waitUntil(() -> onEdtQuietly(frame::isShowing), 5_000);
  }

  @AfterEach
  void closeAnyDialogs() throws Exception {
    disposeDialogs();
  }

  @AfterAll
  static void disposeDemo() throws Exception {
    disposeDialogs();
    if (frame != null) {
      SwingUtilities.invokeAndWait(frame::dispose);
      frame = null;
    }
  }

  /**
   * The modal-safe proof, as a single round trip so at most one {@code detailsDialog} ever exists
   * (disposed windows linger in {@link Window#getWindows()} until GC, so opening two would make
   * {@code detailsCloseButton} a non-unique name and break resolution).
   */
  @Test
  void clickingModalOpenerReturnsPromptlyThenDialogIsVisibleAndClosable() {
    Component openButton = ComponentAddress.resolve("openDialogButton");

    long start = System.currentTimeMillis();
    Interactions.DispatchResult result =
        Interactions.click(openButton, Interactions.MouseButton.LEFT, 1);
    long elapsed = System.currentTimeMillis() - start;

    // The whole point: the call returns despite opening a modal dialog (only the bounded grace
    // period is spent, never the modal loop).
    assertTrue(elapsed < 3_000, "click must not block on the modal loop; took " + elapsed + " ms");
    assertNotNull(result);

    Window dialog = waitUntil(() -> findShowingDialog("Details"), 5_000);
    assertNotNull(dialog, "the modal dialog should have become visible after the click");

    // Driving a component inside the modal dialog works because the EDT pumps a nested loop.
    Interactions.click(
        ComponentAddress.resolve("detailsCloseButton"), Interactions.MouseButton.LEFT, 1);
    boolean dismissed = waitUntilTrue(() -> findShowingDialog("Details") == null, 5_000);
    assertTrue(dismissed, "the dialog should be dismissed after clicking its close button");
  }

  // --------------------------------------------------------------------------- helpers

  private static Window findShowingDialog(String titleSubstring) {
    return onEdtQuietly(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog
                && dialog.isShowing()
                && dialog.getTitle() != null
                && dialog.getTitle().contains(titleSubstring)) {
              return window;
            }
          }
          return null;
        });
  }

  private static void disposeDialogs() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isDisplayable()) {
              dialog.dispose();
            }
          }
        });
  }

  private static <T> T waitUntil(java.util.function.Supplier<T> condition, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      T value = condition.get();
      if (value != null) {
        return value;
      }
      sleep();
    }
    return null;
  }

  private static boolean waitUntilTrue(
      java.util.function.BooleanSupplier condition, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      sleep();
    }
    return condition.getAsBoolean();
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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

  private static <T> T onEdtQuietly(java.util.concurrent.Callable<T> task) {
    try {
      return onEdt(task);
    } catch (Exception e) {
      return null;
    }
  }
}
