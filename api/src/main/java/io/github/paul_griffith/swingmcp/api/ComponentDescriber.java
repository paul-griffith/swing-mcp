package io.github.paul_griffith.swingmcp.api;

import java.awt.Component;

/**
 * Teaches the built-in tools about components they cannot read on their own: a custom-painted
 * widget with no text, or a container whose meaningful label lives in a field of its own.
 *
 * <p>Describers are consulted for <i>every</i> component the tools look at, in the order they were
 * registered, before the built-in rules. A describer should answer quickly and return {@code null}
 * for anything it does not recognize. It is called on the Event Dispatch Thread, so it may read the
 * component freely, but must not block.
 *
 * <p>A describer that throws is treated as having deferred for that call, and the first failure of
 * each describer is logged; it never breaks a tool result.
 *
 * <p>Application classes are usually loaded by a different class loader than the extension, so
 * reach them reflectively through the component you are handed (e.g. by {@link Class#getName()} and
 * {@link Class#getMethod}) rather than compiling against them.
 *
 * <p>A describer is usually a lambda: {@code c -> c instanceof MyGauge g ? g.reading() : null}.
 */
@FunctionalInterface
public interface ComponentDescriber {

  /**
   * The text the tools should report for {@code component} — its {@code text} in {@code
   * get_component_tree} / {@code find_components} results, what {@code find_components} and {@code
   * wait_for} match on, and what {@code get_text} reads for a non-text component — or {@code null}
   * to leave it to the next describer and then the built-in rules. A blank string counts as {@code
   * null}.
   */
  String displayText(Component component);
}
