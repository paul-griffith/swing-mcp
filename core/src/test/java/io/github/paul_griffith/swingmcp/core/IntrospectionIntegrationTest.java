package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.paul_griffith.swingmcp.demo.DemoApp;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headed integration tests: launch the real {@code demo-java} frame in-JVM, then exercise {@link
 * Introspection} and {@link Screenshots} against it. Guarded by {@code
 * assumeFalse(GraphicsEnvironment.isHeadless())} so they skip cleanly with no display, and tagged
 * {@code headed} for optional filtering. On Linux CI they run under Xvfb.
 *
 * <p>A single frame is shared across the tests (created in {@link #showDemo()} / disposed in {@link
 * #disposeDemo()}). This is deliberate: {@link java.awt.Window#getWindows()} keeps returning
 * disposed windows until they are garbage-collected, so recreating a {@code "mainFrame"} per test
 * would make its name non-unique across the accumulated windows and defeat name-based addressing.
 * The tests are read-only apart from the one-time selection state set at startup.
 */
@Tag("headed")
class IntrospectionIntegrationTest {

  private static JFrame frame;

  @BeforeAll
  static void showDemo() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "no display available; skipping headed tests");
    frame =
        onEdt(
            () -> {
              JFrame f = DemoApp.createFrame();
              // Establish deterministic selection state for the assertions below.
              ((JComboBox<?>) findByName(f, "roleCombo")).setSelectedIndex(2); // "Manager"
              ((JTable) findByName(f, "peopleTable"))
                  .setRowSelectionInterval(1, 1); // "Grace Hopper"
              f.setVisible(true);
              return f;
            });
    waitUntilShowing(frame);
  }

  @AfterAll
  static void disposeDemo() throws Exception {
    if (frame != null) {
      SwingUtilities.invokeAndWait(frame::dispose);
      frame = null;
    }
  }

  @Test
  void listWindowsReportsTheDemoFrame() {
    WindowSnapshot demo =
        Introspection.listWindows().stream()
            .filter(w -> "swing-mcp demo".equals(w.title()))
            .findFirst()
            .orElseThrow();

    assertEquals(WindowSnapshot.Kind.FRAME, demo.kind());
    assertEquals("mainFrame", demo.id());
    assertEquals("mainFrame", demo.name());
    assertTrue(demo.showing());
    assertEquals(Boolean.FALSE, demo.iconified());
    assertEquals(Boolean.FALSE, demo.maximized());
    assertTrue(demo.bounds().width() > 0 && demo.bounds().height() > 0);
  }

  @Test
  void componentTreeExposesNamedComponentsWithExpectedIdsTextAndSelection() {
    ComponentTreeSnapshot tree = Introspection.componentTree(frame);

    assertEquals("mainFrame", tree.id());

    ComponentTreeSnapshot button = find(tree, "openDialogButton").orElseThrow();
    assertEquals("JButton", button.simpleClassName());
    assertEquals("Details...", button.text());
    assertEquals(ComponentTreeSnapshot.TextSource.BUTTON_TEXT, button.textSource());
    assertTrue(button.showing());
    assertNotNull(button.screenBounds());

    ComponentTreeSnapshot label = find(tree, "nameLabel").orElseThrow();
    assertEquals("Name:", label.text());
    assertEquals(ComponentTreeSnapshot.TextSource.LABEL_TEXT, label.textSource());

    ComponentTreeSnapshot form = find(tree, "formPanel").orElseThrow();
    assertEquals("Person", form.text());
    assertEquals(ComponentTreeSnapshot.TextSource.TITLED_BORDER, form.textSource());

    ComponentTreeSnapshot combo = find(tree, "roleCombo").orElseThrow();
    assertEquals(ComponentTreeSnapshot.SelectionKind.COMBO_BOX, combo.selection().kind());
    assertEquals(2, combo.selection().selectedIndex());
    assertEquals("Manager", combo.selection().selectedText());

    ComponentTreeSnapshot table = find(tree, "peopleTable").orElseThrow();
    assertEquals(ComponentTreeSnapshot.SelectionKind.TABLE, table.selection().kind());
    assertEquals(List.of(1), table.selection().selectedIndices());
  }

  @Test
  void unnamedComponentsGetWindowAnchoredStructuralIds() {
    ComponentTreeSnapshot tree = Introspection.componentTree(frame);
    // The frame's sole child is its (unnamed) JRootPane, so its id is a w{ordinal}-anchored path.
    ComponentTreeSnapshot rootPane = tree.children().get(0);
    assertEquals("JRootPane", rootPane.simpleClassName());
    assertTrue(
        Pattern.matches("^w\\d+/JRootPane\\[0]$", rootPane.id()),
        "expected a window-anchored structural id, got: " + rootPane.id());
  }

  @Test
  void resolverRoundTripsAnIdBackToTheSameLiveComponent() throws Exception {
    ComponentTreeSnapshot tree = Introspection.componentTree(frame);
    String buttonId = find(tree, "openDialogButton").orElseThrow().id();

    Component resolved = ComponentAddress.resolve(buttonId);
    Component live = onEdt(() -> findByName(frame, "openDialogButton"));
    assertSame(live, resolved, "the id should resolve to the very same live component instance");
  }

  @Test
  void screenshotRendersTheDemoUi() throws Exception {
    BufferedImage image = Screenshots.capture(frame);
    byte[] png = Screenshots.toPngBytes(image);

    Path dir = Path.of(System.getProperty("swingmcp.itScreenshotDir", "build/it-screenshots"));
    Files.createDirectories(dir);
    Path out = dir.resolve("demo-frame.png");
    Files.write(out, png);

    // Non-trivial dimensions (>= logical size; larger under HiDPI).
    assertTrue(image.getWidth() >= 400, "width was " + image.getWidth());
    assertTrue(image.getHeight() >= 300, "height was " + image.getHeight());

    // Pixel variance: a real UI (text, borders, table grid, selection) has many colors; a
    // blank/failed capture would have one or two.
    int colors = distinctColors(image);
    assertTrue(
        colors > 25,
        "screenshot looks blank; distinct colors="
            + colors
            + " (wrote "
            + out.toAbsolutePath()
            + ")");

    assertFalse(png.length == 0);
  }

  // --------------------------------------------------------------------------- helpers

  private static int distinctColors(BufferedImage image) {
    Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < image.getHeight(); y += 2) {
      for (int x = 0; x < image.getWidth(); x += 2) {
        colors.add(image.getRGB(x, y));
        if (colors.size() > 1000) {
          return colors.size();
        }
      }
    }
    return colors.size();
  }

  private static Optional<ComponentTreeSnapshot> find(ComponentTreeSnapshot node, String id) {
    if (id.equals(node.id())) {
      return Optional.of(node);
    }
    for (ComponentTreeSnapshot child : node.children()) {
      Optional<ComponentTreeSnapshot> hit = find(child, id);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }

  private static Component findByName(Component component, String name) {
    if (name.equals(component.getName())) {
      return component;
    }
    if (component instanceof Container container) {
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
    return result.get();
  }

  private static void waitUntilShowing(JFrame frame) throws Exception {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      AtomicReference<Boolean> showing = new AtomicReference<>(false);
      SwingUtilities.invokeAndWait(() -> showing.set(frame.isShowing()));
      if (showing.get()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("demo frame did not become showing within 5s");
  }
}
