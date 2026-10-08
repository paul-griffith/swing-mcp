package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for the component-addressing id scheme and resolver. They build detached
 * component trees (never shown, no window), so they need no display.
 */
class ComponentAddressTest {

  /** Builds: root[ JButton(b0), JLabel, JButton("uniqueButton"), JButton(b2) ]. */
  private static JPanel sampleTree() {
    JPanel root = new JPanel();
    root.add(new JButton("Zero"));
    root.add(new JLabel("Label"));
    JButton named = new JButton("One");
    named.setName("uniqueButton");
    root.add(named);
    root.add(new JButton("Two"));
    return root;
  }

  @Test
  void detachedRootUsesDollarAnchor() {
    ComponentTreeSnapshot tree = Introspection.componentTree(sampleTree());
    assertEquals("$", tree.id());
  }

  @Test
  void namedUniqueComponentUsesItsName() {
    ComponentTreeSnapshot tree = Introspection.componentTree(sampleTree());
    assertEquals("uniqueButton", tree.children().get(2).id());
  }

  @Test
  void unnamedComponentsUseStructuralPathWithPerClassSiblingIndex() {
    ComponentTreeSnapshot tree = Introspection.componentTree(sampleTree());
    assertEquals("$/JButton[0]", tree.children().get(0).id());
    assertEquals("$/JLabel[0]", tree.children().get(1).id());
    // The label does not advance the JButton index: the last button is JButton[2], not [3].
    assertEquals("$/JButton[2]", tree.children().get(3).id());
  }

  @Test
  void duplicateNamesFallBackToStructuralPathsDeterministically() {
    JPanel root = new JPanel();
    JButton d0 = new JButton();
    d0.setName("dup");
    JButton d1 = new JButton();
    d1.setName("dup");
    root.add(d0);
    root.add(d1);

    ComponentTreeSnapshot tree = Introspection.componentTree(root);
    assertEquals("$/JButton[0]", tree.children().get(0).id());
    assertEquals("$/JButton[1]", tree.children().get(1).id());
  }

  @Test
  void resolveInRoundTripsEveryNodeToTheSameLiveInstance() {
    JPanel root = sampleTree();
    ComponentTreeSnapshot tree = Introspection.componentTree(root);
    assertResolvesToSame(root, root, tree);
  }

  private static void assertResolvesToSame(
      Component root, Component live, ComponentTreeSnapshot node) {
    assertSame(
        live,
        ComponentAddress.resolveIn(root, node.id()),
        "id '" + node.id() + "' should resolve back to the same live component");
    if (live instanceof Container container) {
      Component[] children = container.getComponents();
      List<ComponentTreeSnapshot> snapshotChildren = node.children();
      assertEquals(children.length, snapshotChildren.size());
      for (int i = 0; i < children.length; i++) {
        assertResolvesToSame(root, children[i], snapshotChildren.get(i));
      }
    }
  }

  @Test
  void resolveInReturnsRootForBareAnchor() {
    JPanel root = sampleTree();
    assertSame(root, ComponentAddress.resolveIn(root, "$"));
  }

  @Test
  void unknownStructuralPathThrowsNotFound() {
    JPanel root = sampleTree();
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.resolveIn(root, "$/JButton[9]"));
  }

  @Test
  void unknownNameThrowsNotFound() {
    JPanel root = sampleTree();
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.resolveIn(root, "noSuchName"));
  }

  @Test
  void ambiguousNameThrowsAmbiguous() {
    JPanel root = new JPanel();
    JButton d0 = new JButton();
    d0.setName("dup");
    JButton d1 = new JButton();
    d1.setName("dup");
    root.add(d0);
    root.add(d1);

    assertThrows(
        ComponentAddress.AmbiguousComponentException.class,
        () -> ComponentAddress.resolveIn(root, "dup"));
  }

  @Test
  void malformedSegmentThrowsNotFound() {
    JPanel root = sampleTree();
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.resolveIn(root, "$/JButton"));
  }

  // ------------------------------------------------------------------ withComponent combinator

  @Test
  void withComponentInResolvesAndActsInOneStep() {
    JPanel root = sampleTree();
    String text =
        ComponentAddress.withComponentIn(
            root, "uniqueButton", Duration.ofSeconds(5), c -> ((JButton) c).getText());
    assertEquals("One", text);
  }

  @Test
  void withComponentInPropagatesNotFound() {
    JPanel root = sampleTree();
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.withComponentIn(root, "nope", Duration.ofSeconds(5), c -> c));
  }

  // ------------------------------------------------------------------ idKind classification

  @Test
  void idKindIsNameForADeliberatelyNamedComponent() {
    JButton named = new JButton();
    named.setName("okButton");
    assertEquals(ComponentAddress.IdKind.NAME, ComponentAddress.idKindOf(named, "okButton"));
  }

  @Test
  void idKindIsStructuralWhenTheIdIsAPath() {
    JButton unnamed = new JButton();
    assertEquals(
        ComponentAddress.IdKind.STRUCTURAL, ComponentAddress.idKindOf(unnamed, "$/JButton[0]"));
  }

  @Test
  void idKindIsAutoForAnAwtGeneratedDefaultName() {
    // java.awt.Panel fabricates a "panel<n>" name lazily when none was set — unique this run, but
    // not stable across runs, so it must be flagged rather than trusted as a locator.
    java.awt.Panel panel = new java.awt.Panel();
    String autoName = panel.getName();
    assertEquals(ComponentAddress.IdKind.AUTO, ComponentAddress.idKindOf(panel, autoName));
  }

  @Test
  void idKindIsNameForALightweightComponentThatMerelyLooksAutoGenerated() {
    // A Swing JPanel is not a java.awt.Panel, so a deliberate name shaped like the AWT default
    // ("panel1") is still a real, stable identifier — not an auto name.
    JPanel swing = new JPanel();
    swing.setName("panel1");
    assertEquals(ComponentAddress.IdKind.NAME, ComponentAddress.idKindOf(swing, "panel1"));
  }
}
