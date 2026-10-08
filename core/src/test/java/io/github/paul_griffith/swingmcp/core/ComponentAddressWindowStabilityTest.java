package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headed counterpart to {@link ComponentAddressStabilityTest}: pins the part of the contract that
 * only manifests with real {@link java.awt.Window}s — that a <b>name id</b> resolves globally
 * regardless of how the window set changes (windows opening/closing renumber the {@code w{ordinal}}
 * anchor, but a uniquely-named component keeps resolving). Guarded/headed like the other
 * real-window tests: skips cleanly with no display, runs under Xvfb on Linux CI.
 */
@Tag("headed")
class ComponentAddressWindowStabilityTest {

  @Test
  void nameIdResolvesGloballyAcrossWindowSetChanges() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");

    JFrame alpha = onEdt(() -> namedFrame("stabilityAlphaFrame", "stabilityAlphaButton"));
    JFrame beta = onEdt(() -> namedFrame("stabilityBetaFrame", "stabilityBetaButton"));
    try {
      Component alphaButton = onEdt(() -> findByName(alpha, "stabilityAlphaButton"));
      Component betaButton = onEdt(() -> findByName(beta, "stabilityBetaButton"));

      // Both resolve globally by their (unique) name ids.
      assertSame(alphaButton, ComponentAddress.resolve("stabilityAlphaButton"));
      assertSame(betaButton, ComponentAddress.resolve("stabilityBetaButton"));

      // Close the first window: the window set changes and w-ordinals renumber, but beta's
      // NAME id keeps resolving to the same live component — name ids do not depend on ordinals.
      SwingUtilities.invokeAndWait(alpha::dispose);
      assertSame(
          betaButton,
          ComponentAddress.resolve("stabilityBetaButton"),
          "a name id must survive changes to the window set");

      // A structural path anchored to a now-missing window ordinal fails predictably.
      assertThrows(
          ComponentAddress.ComponentNotFoundException.class,
          () -> ComponentAddress.resolve("w9999/JRootPane[0]"));
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            alpha.dispose();
            beta.dispose();
          });
    }
  }

  @Test
  void structuralWindowHandleSurvivesWindowSetChanges() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");

    // An *unnamed* button anchors its structural id to its window's stable process-lifetime handle
    // (w{n}), rather than an index into Window.getWindows() that reorders as windows open/close.
    JFrame host = onEdt(WindowStability::unnamedFrameWithButton);
    JFrame other = null;
    try {
      Component button = onEdt(() -> findFirstButton(host));
      String id = ComponentAddress.idOf(button);
      assertTrue(
          id.startsWith("w"), "unnamed button should carry a w{handle} structural id: " + id);
      assertSame(button, ComponentAddress.resolve(id));

      // Opening another window must not renumber the handle: the id still resolves.
      other = onEdt(WindowStability::unnamedFrameWithButton);
      assertSame(
          button, ComponentAddress.resolve(id), "a window handle must not renumber when one opens");

      // Closing that other window leaves the host's id resolving to the same live button.
      JFrame toClose = other;
      SwingUtilities.invokeAndWait(toClose::dispose);
      other = null;
      assertSame(
          button,
          ComponentAddress.resolve(id),
          "a window handle must survive another window closing");
    } finally {
      JFrame stillOpen = other;
      SwingUtilities.invokeAndWait(
          () -> {
            host.dispose();
            if (stillOpen != null) {
              stillOpen.dispose();
            }
          });
    }
  }

  private static Component findFirstButton(Component component) {
    if (component instanceof JButton) {
      return component;
    }
    if (component instanceof java.awt.Container container) {
      for (Component child : container.getComponents()) {
        Component hit = findFirstButton(child);
        if (hit != null) {
          return hit;
        }
      }
    }
    return null;
  }

  /** Factory holder so a method reference (rather than a lambda throwing checked) reads cleanly. */
  private static final class WindowStability {
    static JFrame unnamedFrameWithButton() {
      JFrame frame = new JFrame(); // deliberately unnamed -> children get w{handle} structural ids
      frame.getContentPane().add(new JButton("Go"));
      frame.pack();
      frame.setVisible(true);
      return frame;
    }
  }

  private static JFrame namedFrame(String frameName, String buttonName) {
    JFrame frame = new JFrame(frameName);
    frame.setName(frameName);
    JButton button = new JButton("Go");
    button.setName(buttonName);
    frame.getContentPane().add(button);
    frame.pack();
    frame.setVisible(true);
    return frame;
  }

  private static Component findByName(Component component, String name) {
    if (name.equals(component.getName())) {
      return component;
    }
    if (component instanceof java.awt.Container container) {
      for (Component child : container.getComponents()) {
        Component hit = findByName(child, name);
        if (hit != null) {
          return hit;
        }
      }
    }
    return null;
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
    assertTrue(result.get() != null, "task returned null");
    return result.get();
  }
}
