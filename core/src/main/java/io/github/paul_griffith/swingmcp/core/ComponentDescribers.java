package io.github.paul_griffith.swingmcp.core;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The registered extension {@link ComponentDescriber}s, consulted by {@link Introspection}'s
 * display-text rules.
 *
 * <p>Describers run in registration order and the first non-blank answer wins. A describer that
 * throws is isolated: that call counts as deferring, and its first failure is reported (once per
 * describer) through the failure reporter, so one broken extension can never break a tool result.
 *
 * <p>Process-wide, since core is a set of static utilities serving one agent per JVM: the agent
 * installs the list once at startup. Call {@link #displayText} on the Event Dispatch Thread.
 */
public final class ComponentDescribers {

  /**
   * A describer together with the name of the extension that registered it, for failure reports.
   *
   * @param owner the registering extension's name
   * @param describer the describer
   */
  public record Registration(String owner, ComponentDescriber describer) {
    public Registration {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(describer, "describer");
    }
  }

  private static volatile List<Registration> registrations = List.of();

  private static volatile Consumer<String> failureReporter = System.err::println;

  /** Describers that have already had a failure reported (identity), so each is reported once. */
  private static final Set<ComponentDescriber> REPORTED = ConcurrentHashMap.newKeySet();

  private ComponentDescribers() {}

  /** Replaces the registered describers (an empty list removes them all). */
  public static void install(List<Registration> describers) {
    registrations = List.copyOf(describers);
    REPORTED.clear();
  }

  /** The registered describers, in consultation order. */
  public static List<Registration> installed() {
    return registrations;
  }

  /** Where describer failures are reported (default: stderr). */
  public static void setFailureReporter(Consumer<String> reporter) {
    failureReporter = Objects.requireNonNull(reporter, "reporter");
  }

  /**
   * The first non-blank display text a describer gives {@code component}, or {@code null} when none
   * does. Call on the EDT.
   */
  static String displayText(Component component) {
    for (Registration r : registrations) {
      try {
        String text = r.describer().displayText(component);
        if (text != null && !text.isBlank()) {
          return text;
        }
      } catch (RuntimeException | LinkageError e) {
        reportOnce(r, e);
      }
    }
    return null;
  }

  private static void reportOnce(Registration r, Throwable failure) {
    if (REPORTED.add(r.describer())) {
      failureReporter.accept(
          "extension '"
              + r.owner()
              + "': describer "
              + r.describer().getClass().getName()
              + " threw "
              + failure
              + " (treated as no answer; further failures of this describer are not reported)");
    }
  }
}
