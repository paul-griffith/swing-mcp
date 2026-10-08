package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented {@link ComponentAddress} stability contract so a future refactor cannot
 * silently change the id scheme. These build detached (never-shown) trees and resolve with {@link
 * ComponentAddress#resolveIn}, so they are headless-safe.
 *
 * <p>The contract, restated as executable expectations:
 *
 * <ul>
 *   <li><b>Name ids</b> survive re-layout, re-parenting, and sibling changes — they depend only on
 *       the name staying set and unique.
 *   <li><b>Structural paths</b> survive re-layout but shift predictably when earlier same-class
 *       siblings are inserted/removed, and become {@code ComponentNotFound} once the target is
 *       gone.
 *   <li>A duplicated name id becomes {@code Ambiguous}; a vanished one becomes {@code
 *       ComponentNotFound}.
 * </ul>
 */
class ComponentAddressStabilityTest {

  // ------------------------------------------------------------------ name ids are durable

  @Test
  void nameIdSurvivesReParenting() {
    JPanel root = new JPanel();
    JPanel left = new JPanel();
    JPanel right = new JPanel();
    root.add(left);
    root.add(right);
    JButton save = new JButton("Save");
    save.setName("saveButton");
    left.add(save);

    // Minted as a bare name id, and it resolves.
    assertEquals("saveButton", idOf(root, save));
    assertSame(save, ComponentAddress.resolveIn(root, "saveButton"));

    // Move it under a different parent: the name id is unaffected and still resolves.
    left.remove(save);
    right.add(save);
    assertEquals("saveButton", idOf(root, save));
    assertSame(save, ComponentAddress.resolveIn(root, "saveButton"));
  }

  @Test
  void nameIdSurvivesEarlierSiblingInsertion() {
    JPanel root = new JPanel();
    JButton ok = new JButton("OK");
    ok.setName("okButton");
    root.add(ok);
    assertSame(ok, ComponentAddress.resolveIn(root, "okButton"));

    // Insert same-class and other-class siblings ahead of it (a re-layout): id unchanged.
    root.add(new JButton("prepended"), 0);
    root.add(new JLabel("also prepended"), 0);
    assertEquals("okButton", idOf(root, ok));
    assertSame(ok, ComponentAddress.resolveIn(root, "okButton"));
  }

  // ------------------------------------------------------------------ structural paths shift
  // predictably

  @Test
  void structuralPathShiftsWhenEarlierSameClassSiblingIsInserted() {
    JPanel root = new JPanel();
    JButton first = new JButton("first");
    JButton target = new JButton("target");
    root.add(first);
    root.add(target);

    // Unnamed → per-class-sibling structural paths.
    assertEquals("$/JButton[0]", idOf(root, first));
    assertEquals("$/JButton[1]", idOf(root, target));
    assertSame(target, ComponentAddress.resolveIn(root, "$/JButton[1]"));

    // Insert a same-class sibling at the front: every later JButton index shifts up by one.
    JButton inserted = new JButton("inserted");
    root.add(inserted, 0);

    // The OLD id now resolves to a DIFFERENT component — the documented, predictable breakage.
    assertSame(first, ComponentAddress.resolveIn(root, "$/JButton[1]"));
    // And the target's fresh id has advanced accordingly.
    assertEquals("$/JButton[2]", idOf(root, target));
    assertSame(target, ComponentAddress.resolveIn(root, "$/JButton[2]"));
  }

  @Test
  void otherClassSiblingsDoNotShiftAStructuralPath() {
    JPanel root = new JPanel();
    JButton target = new JButton("target");
    root.add(target);
    assertEquals("$/JButton[0]", idOf(root, target));

    // A prepended JLabel does not participate in the JButton per-class index.
    root.add(new JLabel("label"), 0);
    assertEquals("$/JButton[0]", idOf(root, target));
    assertSame(target, ComponentAddress.resolveIn(root, "$/JButton[0]"));
  }

  // ------------------------------------------------------------------ resolver failure modes

  @Test
  void structuralPathBecomesNotFoundOnceTheTargetIsRemoved() {
    JPanel root = new JPanel();
    JButton button = new JButton("gone soon");
    root.add(button);
    assertSame(button, ComponentAddress.resolveIn(root, "$/JButton[0]"));

    root.remove(button);
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.resolveIn(root, "$/JButton[0]"));
  }

  @Test
  void nameIdBecomesAmbiguousOnceADuplicateAppears() {
    JPanel root = new JPanel();
    JButton first = new JButton();
    first.setName("row");
    root.add(first);
    // Unique for now.
    assertSame(first, ComponentAddress.resolveIn(root, "row"));

    // A second component with the same name appears since the id was minted.
    JButton second = new JButton();
    second.setName("row");
    root.add(second);
    assertThrows(
        ComponentAddress.AmbiguousComponentException.class,
        () -> ComponentAddress.resolveIn(root, "row"));
  }

  @Test
  void nameIdBecomesNotFoundOnceTheComponentIsRemoved() {
    JPanel root = new JPanel();
    JButton button = new JButton();
    button.setName("solo");
    root.add(button);
    assertSame(button, ComponentAddress.resolveIn(root, "solo"));

    root.remove(button);
    assertThrows(
        ComponentAddress.ComponentNotFoundException.class,
        () -> ComponentAddress.resolveIn(root, "solo"));
  }

  // ------------------------------------------------------------------ helper

  /**
   * The id {@link Introspection} currently mints for {@code component} within {@code root}'s tree.
   */
  private static String idOf(JPanel root, java.awt.Component component) {
    ComponentTreeSnapshot tree = Introspection.componentTree(root);
    String id = findId(tree, root, component);
    if (id == null) {
      throw new AssertionError("component not present in the snapshot of root");
    }
    return id;
  }

  private static String findId(
      ComponentTreeSnapshot node, java.awt.Component current, java.awt.Component target) {
    if (current == target) {
      return node.id();
    }
    if (current instanceof java.awt.Container container) {
      java.awt.Component[] children = container.getComponents();
      for (int i = 0; i < children.length && i < node.children().size(); i++) {
        String hit = findId(node.children().get(i), children[i], target);
        if (hit != null) {
          return hit;
        }
      }
    }
    return null;
  }
}
