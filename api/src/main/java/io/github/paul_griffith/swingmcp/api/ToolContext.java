package io.github.paul_griffith.swingmcp.api;

import java.awt.Component;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Swing access for a {@link ToolHandler}: run work on the Event Dispatch Thread with a timeout, and
 * translate between live components and the ids every other tool uses.
 *
 * <p>A blocked EDT fails the call with the {@code edt_timeout} error rather than hanging it, and an
 * id that does not resolve fails it with {@code component_not_found} (or {@code
 * ambiguous_component}), exactly as for the built-in tools.
 */
public interface ToolContext {

  /** The EDT timeout the single-argument forms use (the built-in tools' default). */
  Duration defaultTimeout();

  /**
   * Resolves {@code id} (as returned by {@code get_component_tree} / {@code find_components}) and
   * applies {@code action} to the component on the EDT, returning its result. Resolution and action
   * run in one EDT task, so the component cannot change in between. An unchecked exception from
   * {@code action} (a {@link ToolException} included) is rethrown as is.
   */
  <T> T withComponent(String id, Function<Component, T> action);

  /** {@link #withComponent(String, Function)} with an explicit EDT timeout. */
  <T> T withComponent(String id, Duration timeout, Function<Component, T> action);

  /**
   * Runs {@code task} on the EDT and returns its result. Whatever the task throws (a {@link
   * ToolException} included) is rethrown as is.
   */
  <T> T onEdt(Callable<T> task) throws Exception;

  /** {@link #onEdt(Callable)} with an explicit EDT timeout. */
  <T> T onEdt(Callable<T> task, Duration timeout) throws Exception;

  /**
   * The id other tools accept for {@code component} (for results that point at components). Call on
   * the EDT, i.e. inside {@link #withComponent} or {@link #onEdt}.
   */
  String idOf(Component component);
}
