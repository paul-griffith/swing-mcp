package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for {@link ComponentDescribers}: describers run before the built-in text
 * rules, defer with {@code null}, and are isolated when they throw; their text reaches find and
 * {@code get_text} like any other.
 */
class ComponentDescribersTest {

  private final List<String> reports = new ArrayList<>();

  @AfterEach
  void reset() {
    ComponentDescribers.install(List.of());
    ComponentDescribers.setFailureReporter(System.err::println);
  }

  private void install(ComponentDescriber... describers) {
    List<ComponentDescribers.Registration> registrations = new ArrayList<>();
    for (ComponentDescriber d : describers) {
      registrations.add(new ComponentDescribers.Registration("test", d));
    }
    ComponentDescribers.install(registrations);
    ComponentDescribers.setFailureReporter(reports::add);
  }

  private static ComponentTreeSnapshot snapshot(Component component) {
    return Introspection.componentTree(component, 0, Introspection.DEFAULT_TIMEOUT);
  }

  /** A custom widget with no text of its own: the motivating case for describers. */
  private static final class Gauge extends JComponent {
    private final int reading;

    Gauge(int reading) {
      this.reading = reading;
    }
  }

  private static final ComponentDescriber GAUGES =
      c -> c instanceof Gauge gauge ? "Gauge: " + gauge.reading : null;

  @Test
  void aDescriberGivesACustomComponentText() {
    ComponentTreeSnapshot before = snapshot(new Gauge(42));
    assertEquals(ComponentTreeSnapshot.TextSource.NONE, before.textSource(), "baseline: no text");

    install(GAUGES);
    ComponentTreeSnapshot node = snapshot(new Gauge(42));
    assertEquals("Gauge: 42", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.EXTENSION, node.textSource());
  }

  @Test
  void describersRunBeforeTheBuiltInRules() {
    install(c -> c instanceof JLabel ? "described" : null);
    assertEquals("described", snapshot(new JLabel("label text")).text());
  }

  @Test
  void nullOrBlankDefersToTheNextDescriberThenTheBuiltIns() {
    install(c -> null, c -> "  ", c -> c instanceof JLabel ? "second" : null);
    assertEquals("second", snapshot(new JLabel("label text")).text());

    install(c -> null);
    ComponentTreeSnapshot node = snapshot(new JLabel("label text"));
    assertEquals("label text", node.text());
    assertEquals(ComponentTreeSnapshot.TextSource.LABEL_TEXT, node.textSource());
  }

  @Test
  void aThrowingDescriberIsIsolatedAndReportedOnce() {
    ComponentDescriber broken =
        c -> {
          throw new IllegalStateException("boom");
        };
    install(broken, c -> c instanceof JLabel ? "fallback describer" : null);

    assertEquals("fallback describer", snapshot(new JLabel("x")).text());
    assertEquals("fallback describer", snapshot(new JLabel("y")).text());
    assertEquals(1, reports.size(), "reported once: " + reports);
    assertTrue(reports.get(0).contains("extension 'test'"), reports.get(0));
    assertTrue(reports.get(0).contains("boom"), reports.get(0));
  }

  @Test
  void aLinkageErrorInADescriberIsIsolatedToo() {
    install(
        c -> {
          throw new NoClassDefFoundError("com/example/Missing");
        });
    assertEquals("still read", snapshot(new JLabel("still read")).text());
    assertEquals(1, reports.size());
  }

  @Test
  void findMatchesDescriberText() {
    install(GAUGES);
    JPanel panel = new JPanel();
    panel.add(new Gauge(7));
    Introspection.FindResult result =
        Introspection.find(
            List.of(panel),
            new Introspection.FindCriteria(null, null, "Gauge: 7", false),
            0,
            Introspection.DEFAULT_TIMEOUT);
    assertEquals(1, result.total());
  }

  @Test
  void getTextReadsDescriberTextForANonTextComponent() {
    install(GAUGES);
    Introspection.TextRead read =
        Introspection.readText(new Gauge(99), 0, 100, false, Introspection.DEFAULT_TIMEOUT);
    assertEquals("Gauge: 99", read.text());
    assertEquals("extension", read.source());
  }
}
