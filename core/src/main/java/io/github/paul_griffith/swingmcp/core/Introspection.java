package io.github.paul_griffith.swingmcp.core;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.LayoutManager2;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.accessibility.AccessibleContext;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListModel;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreeModel;

/**
 * Read-only Swing introspection: enumerate windows and snapshot component trees.
 *
 * <p>Every method reads the AWT/Swing hierarchy on the Event Dispatch Thread through {@link
 * EdtOps#invokeAndWait}, so all entry points are safe to call from any thread — including the EDT
 * itself, where the read runs inline without deadlocking. A blocked EDT surfaces as an {@link
 * EdtOps.EdtTimeoutException} rather than a hung call.
 *
 * <p>Results are plain {@link WindowSnapshot} / {@link ComponentTreeSnapshot} data (no live
 * component references), ready to be handed to a transport thread for encoding.
 */
public final class Introspection {

  /** Default EDT timeout for introspection reads (the shared core default). */
  public static final Duration DEFAULT_TIMEOUT = EdtOps.DEFAULT_TIMEOUT;

  /** Sentinel for {@code maxDepth}: include the entire subtree. */
  public static final int UNLIMITED_DEPTH = -1;

  private Introspection() {}

  /** {@link #listWindows(Duration)} with the {@link #DEFAULT_TIMEOUT}. */
  public static List<WindowSnapshot> listWindows() {
    return listWindows(DEFAULT_TIMEOUT);
  }

  /**
   * Snapshots every window in {@link Window#getWindows()} in that array's order.
   *
   * @param timeout maximum time to wait for the EDT
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static List<WindowSnapshot> listWindows(Duration timeout) {
    return onEdt(
        () -> {
          Window[] windows = Window.getWindows();
          Map<String, Integer> nameFrequency = ComponentAddress.nameFrequency(List.of(windows));
          List<WindowSnapshot> out = new ArrayList<>(windows.length);
          for (Window window : windows) {
            out.add(windowSnapshot(window, nameFrequency));
          }
          return out;
        },
        timeout);
  }

  /**
   * A filtered/sorted window list for the {@code list_windows} tool: the windows that passed the
   * filters, focused-first, plus how many non-showing windows the filter suppressed.
   *
   * @param windows the retained snapshots, sorted focused-first (then original z-order)
   * @param hiddenCount how many non-showing windows were suppressed because {@code includeHidden}
   *     was false (windows that also failed the title filter are not counted)
   */
  public record WindowFilter(List<WindowSnapshot> windows, int hiddenCount) {}

  /**
   * Filters and sorts window snapshots for presentation: keeps only windows whose title contains
   * {@code title} (case-insensitive; {@code null}/blank = no title filter) and — unless {@code
   * includeHidden} — only {@link WindowSnapshot#showing() showing} ones, then sorts focused windows
   * first while otherwise preserving the input (z-)order. Pure data transformation: safe on any
   * thread, no EDT access.
   */
  public static WindowFilter filterWindows(
      List<WindowSnapshot> windows, boolean includeHidden, String title) {
    String needle = blankToNull(title);
    List<WindowSnapshot> kept = new ArrayList<>(windows.size());
    int hidden = 0;
    for (WindowSnapshot w : windows) {
      if (needle != null && (w.title() == null || !containsIgnoreCase(w.title(), needle))) {
        continue;
      }
      if (!includeHidden && !w.showing()) {
        hidden++;
        continue;
      }
      kept.add(w);
    }
    kept.sort(Comparator.comparing(w -> !w.focused())); // stable: focused first, z-order otherwise
    return new WindowFilter(List.copyOf(kept), hidden);
  }

  /**
   * {@link #componentTree(Component, int, Duration)} for the whole subtree with the default
   * timeout.
   */
  public static ComponentTreeSnapshot componentTree(Component root) {
    return componentTree(root, UNLIMITED_DEPTH, DEFAULT_TIMEOUT);
  }

  /**
   * Snapshots the component subtree rooted at {@code root}.
   *
   * <p>{@code maxDepth} bounds how many levels of descendants are included: {@code 0} yields the
   * root alone, {@code 1} adds its direct children, and so on; {@link #UNLIMITED_DEPTH} (or any
   * negative value) includes everything. Even where children are omitted by the limit, each node's
   * {@link ComponentTreeSnapshot#childCount()} still reports its true child count.
   *
   * @param root the subtree root (often a {@link Window})
   * @param maxDepth descendant depth limit, or {@link #UNLIMITED_DEPTH}
   * @param timeout maximum time to wait for the EDT
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static ComponentTreeSnapshot componentTree(
      Component root, int maxDepth, Duration timeout) {
    return onEdt(
        () -> {
          ComponentAddress.PathBase base = ComponentAddress.pathBaseFor(root);
          // Uniqueness scope matches the resolver's search scope: all windows for a window-anchored
          // snapshot (global resolve), or just this subtree for a detached one (rooted resolve).
          List<? extends Component> nameScope =
              base.anchor().startsWith("w") ? List.of(Window.getWindows()) : List.of(root);
          Map<String, Integer> nameFrequency = ComponentAddress.nameFrequency(nameScope);
          return buildNode(root, base, nameFrequency, maxDepth);
        },
        timeout);
  }

  // ---------------------------------------------------------------------------------------
  // Component search (find_components)
  // ---------------------------------------------------------------------------------------

  /** Default cap on {@link #find} matches when the caller does not specify one. */
  public static final int DEFAULT_FIND_LIMIT = 50;

  /**
   * Filters for {@link #find}. Each string filter is a case-insensitive substring; a {@code null}
   * or blank one is ignored, and the non-blank filters are AND-combined. {@code interactableOnly}
   * additionally restricts matches to input widgets (buttons, text components, combos, lists,
   * tables, tabbed panes, spinners, trees, sliders, menu items).
   *
   * @param namePattern substring matched against {@link Component#getName()}
   * @param className substring matched against the simple or fully-qualified class name
   * @param text substring matched against the best-effort display text
   * @param interactableOnly restrict to input widgets
   */
  public record FindCriteria(
      String namePattern, String className, String text, boolean interactableOnly) {
    boolean isBlank() {
      return blankToNull(namePattern) == null
          && blankToNull(className) == null
          && blankToNull(text) == null
          && !interactableOnly;
    }
  }

  /**
   * Result of a {@link #find}: the (depth-0, childless) match snapshots up to the limit, the total
   * number of components that matched, and whether the returned list was capped.
   *
   * @param matches matched components as depth-0 snapshots (real {@code childCount}, no children)
   * @param total total matches found, even beyond the returned {@code matches}
   * @param truncated {@code true} if {@code total} exceeded the limit and {@code matches} was
   *     capped
   * @param scanned how many components the search visited (matched or not)
   * @param hint zero-result diagnostics — the scope searched plus near-miss names/classes seen
   *     during the scan — or {@code null} when there were matches
   */
  public record FindResult(
      List<ComponentTreeSnapshot> matches,
      int total,
      boolean truncated,
      int scanned,
      String hint) {}

  /**
   * Searches the subtrees rooted at each of {@code roots} for components matching {@code criteria},
   * assigning each match the same stable {@code id} {@link #componentTree} would. Ids remain
   * globally resolvable ({@link ComponentAddress#resolve}) as long as any live window exists.
   *
   * <p>A {@code null} or empty {@code roots} means <b>all live windows</b>: they are resolved from
   * {@link Window#getWindows()} inside the single EDT task, so the caller need not (and should not)
   * pre-resolve each window on a separate hop just to hand them back.
   *
   * @param roots the subtree roots to search, or {@code null}/empty for all live windows
   * @param criteria the match filters (at least one must be set)
   * @param limit maximum matches to return ({@code ≤ 0} uses {@link #DEFAULT_FIND_LIMIT})
   * @param timeout maximum time to wait for the EDT
   * @throws IllegalArgumentException if {@code criteria} has no filter set
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static FindResult find(
      List<? extends Component> roots, FindCriteria criteria, int limit, Duration timeout) {
    if (criteria == null || criteria.isBlank()) {
      throw new IllegalArgumentException(
          "find requires at least one filter: name, class, text, or interactable");
    }
    int cap = limit > 0 ? limit : DEFAULT_FIND_LIMIT;
    return onEdt(
        () -> {
          Window[] windows = Window.getWindows();
          boolean allWindows = roots == null || roots.isEmpty();
          List<? extends Component> effectiveRoots = allWindows ? List.of(windows) : roots;
          List<? extends Component> nameScope =
              windows.length > 0 ? List.of(windows) : effectiveRoots;
          Map<String, Integer> nameFrequency = ComponentAddress.nameFrequency(nameScope);
          List<ComponentTreeSnapshot> matches = new ArrayList<>();
          Scan scan = new Scan(criteria);
          for (Component root : effectiveRoots) {
            ComponentAddress.PathBase base = ComponentAddress.pathBaseFor(root);
            collectMatches(root, base, nameFrequency, cap, matches, scan);
          }
          String hint =
              scan.total == 0
                  ? zeroResultHint(effectiveRoots, allWindows, scan, nameFrequency)
                  : null;
          return new FindResult(
              List.copyOf(matches), scan.total, scan.total > matches.size(), scan.scanned, hint);
        },
        timeout);
  }

  /** Cap on the near-miss name/class candidates collected for a zero-result hint. */
  private static final int NEAR_MISS_CAP = 5;

  /** Minimum shared (case-insensitive) prefix for a getName() to count as a near miss. */
  private static final int NEAR_MISS_MIN_PREFIX = 3;

  /**
   * Mutable per-search state carried through the walk: the criteria, visit/match counters, and the
   * near-miss candidates gathered opportunistically so a zero-result hint costs no second pass.
   */
  private static final class Scan {
    final FindCriteria criteria;
    final String nameQuery;
    final String classQuery;
    int scanned;
    int total;
    final Set<String> nameNearMisses = new LinkedHashSet<>();
    final Set<String> classNearMisses = new LinkedHashSet<>();

    Scan(FindCriteria criteria) {
      this.criteria = criteria;
      this.nameQuery = blankToNull(criteria.namePattern());
      this.classQuery = blankToNull(criteria.className());
    }
  }

  private static void collectMatches(
      Component component,
      ComponentAddress.PathBase base,
      Map<String, Integer> nameFrequency,
      int limit,
      List<ComponentTreeSnapshot> out,
      Scan scan) {
    scan.scanned++;
    collectNearMisses(component, scan);
    if (matchesCriteria(component, scan.criteria)) {
      scan.total++;
      if (out.size() < limit) {
        out.add(buildNode(component, base, nameFrequency, 0));
      }
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectMatches(child, base, nameFrequency, limit, out, scan);
      }
    }
  }

  private static void collectNearMisses(Component component, Scan scan) {
    if (scan.nameQuery != null && scan.nameNearMisses.size() < NEAR_MISS_CAP) {
      String name = blankToNull(component.getName());
      if (name != null && isNameNearMiss(name, scan.nameQuery)) {
        scan.nameNearMisses.add(name);
      }
    }
    if (scan.classQuery != null && scan.classNearMisses.size() < NEAR_MISS_CAP) {
      String simple = ComponentAddress.simpleClassName(component.getClass());
      if (isClassNearMiss(simple, scan.classQuery)) {
        scan.classNearMisses.add(simple);
      }
    }
  }

  /**
   * Whether a component's {@code getName()} is close enough to a name query to be worth suggesting:
   * it contains the query (so a different filter excluded the component), or it shares a
   * case-insensitive prefix of at least {@link #NEAR_MISS_MIN_PREFIX} chars with it.
   */
  static boolean isNameNearMiss(String name, String query) {
    return containsIgnoreCase(name, query)
        || sharedPrefixLength(name, query) >= NEAR_MISS_MIN_PREFIX;
  }

  /**
   * Whether a simple class name is a near miss for a class query: one is a case-insensitive
   * substring of the other (comparing against the query's last {@code .}-segment, so an FQCN query
   * still finds simple-name neighbours) — e.g. query {@code TabbedPane} vs {@code JideTabbedPane}.
   */
  static boolean isClassNearMiss(String simpleClassName, String query) {
    String tail = query.substring(query.lastIndexOf('.') + 1);
    if (tail.isEmpty()) {
      return false;
    }
    return containsIgnoreCase(simpleClassName, tail) || containsIgnoreCase(tail, simpleClassName);
  }

  private static int sharedPrefixLength(String a, String b) {
    int max = Math.min(a.length(), b.length());
    int i = 0;
    while (i < max && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * The diagnostics string for a search that matched nothing: the scope that was searched (windows
   * or a subtree — calling out an empty, possibly lazily-built subtree), any near-miss names or
   * classes seen during the scan, and a reminder about FQCN matching when a dotted class query
   * found nothing. Runs on the EDT within {@link #find}'s single task.
   */
  private static String zeroResultHint(
      List<? extends Component> roots,
      boolean allWindows,
      Scan scan,
      Map<String, Integer> nameFrequency) {
    List<String> parts = new ArrayList<>(4);
    if (allWindows) {
      parts.add(
          "searched "
              + roots.size()
              + (roots.size() == 1 ? " window" : " windows")
              + " ("
              + scan.scanned
              + " components)");
    } else if (scan.scanned <= roots.size()) {
      parts.add(
          scopeIds(roots, nameFrequency)
              + " has no descendants — lazily-built content may not exist until it is shown");
    } else {
      parts.add("searched " + scan.scanned + " components under " + scopeIds(roots, nameFrequency));
    }
    if (!scan.nameNearMisses.isEmpty()) {
      parts.add("similar names: " + scan.nameNearMisses);
    }
    if (!scan.classNearMisses.isEmpty()) {
      parts.add("similar classes: " + scan.classNearMisses);
    }
    if (scan.classQuery != null
        && scan.classQuery.indexOf('.') >= 0
        && scan.classNearMisses.isEmpty()) {
      parts.add(
          "class matches simple and fully-qualified names; no scanned class contained \""
              + scan.classQuery
              + "\"");
    }
    return String.join("; ", parts);
  }

  /** The scoped roots' ids for a hint (normally a single subtree root). Call on the EDT. */
  private static String scopeIds(
      List<? extends Component> roots, Map<String, Integer> nameFrequency) {
    List<String> ids = new ArrayList<>(roots.size());
    for (Component root : roots) {
      ids.add(ComponentAddress.idFor(root, ComponentAddress.pathBaseFor(root), nameFrequency));
    }
    return String.join(", ", ids);
  }

  private static boolean matchesCriteria(Component component, FindCriteria criteria) {
    if (criteria.interactableOnly() && !isInteractable(component)) {
      return false;
    }
    String namePattern = blankToNull(criteria.namePattern());
    if (namePattern != null) {
      String name = component.getName();
      if (name == null || !containsIgnoreCase(name, namePattern)) {
        return false;
      }
    }
    String classPattern = blankToNull(criteria.className());
    if (classPattern != null) {
      String simple = ComponentAddress.simpleClassName(component.getClass());
      String full = component.getClass().getName();
      if (!containsIgnoreCase(simple, classPattern) && !containsIgnoreCase(full, classPattern)) {
        return false;
      }
    }
    String textPattern = blankToNull(criteria.text());
    if (textPattern != null) {
      String text = displayText(component).raw();
      if (text == null || !containsIgnoreCase(text, textPattern)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isInteractable(Component component) {
    return component instanceof AbstractButton
        || component instanceof JTextComponent
        || component instanceof JComboBox<?>
        || component instanceof JList<?>
        || component instanceof JTable
        || component instanceof JTabbedPane
        || component instanceof JSpinner
        || component instanceof JTree
        || component instanceof JSlider
        || component instanceof JMenuItem;
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  // ---------------------------------------------------------------------------------------
  // Find-filtered waiting (wait_for)
  // ---------------------------------------------------------------------------------------

  /** Default overall timeout for {@link #waitFor}. */
  public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMillis(10_000);

  /** Default poll interval for {@link #waitFor}. */
  public static final Duration DEFAULT_WAIT_POLL = Duration.ofMillis(250);

  /** Minimum poll interval for {@link #waitFor}; shorter requests are clamped up to this. */
  public static final Duration MIN_WAIT_POLL = Duration.ofMillis(100);

  /** The condition {@link #waitFor} polls for, evaluated against the {@link #find} matches. */
  public enum WaitCondition {
    /** At least one component matches the filters. */
    APPEARS,
    /** No component matches the filters. */
    DISAPPEARS,
    /** At least one matching component is enabled. */
    ENABLED,
    /** At least one matching component's display text contains the expected substring. */
    TEXT_CONTAINS;

    /** Parses a wire value ({@code "appears"}, …) case-insensitively, or {@code null}. */
    public static WaitCondition parse(String raw) {
      if (raw == null) {
        return null;
      }
      return switch (raw.trim().toLowerCase(Locale.ROOT)) {
        case "appears" -> APPEARS;
        case "disappears" -> DISAPPEARS;
        case "enabled" -> ENABLED;
        case "text_contains" -> TEXT_CONTAINS;
        default -> null;
      };
    }
  }

  /**
   * Outcome of a {@link #waitFor}.
   *
   * @param met whether the condition held before the timeout (a timeout is <b>not</b> an error —
   *     callers branch on this, matching the await-idle convention)
   * @param elapsedMs how long the wait ran
   * @param lastCount the match count seen by the final probe
   * @param match the (first) match that satisfied the condition, or {@code null} (always {@code
   *     null} for {@link WaitCondition#DISAPPEARS} and on timeout)
   */
  public record WaitResult(
      boolean met, long elapsedMs, int lastCount, ComponentTreeSnapshot match) {}

  /**
   * Polls until components matching {@code criteria} satisfy {@code condition}, or {@code timeout}
   * elapses. The poll loop runs on the <b>calling</b> (transport) thread — the EDT is never
   * blocked; each probe is one bounded EDT task that re-resolves the scope and runs {@link #find}
   * inline.
   *
   * <p>The scope ({@code scopeId}, a window/component id, or {@code null} for all windows) is
   * re-resolved on <i>every</i> poll, so the wait survives a window swap — and a scope that does
   * not resolve (yet, or anymore) counts as <b>zero matches</b> rather than an error, since the
   * dialog being awaited may simply not exist on the first polls.
   *
   * <p>Multi-match semantics: the first match satisfying the condition wins and is returned; {@link
   * WaitCondition#DISAPPEARS} requires zero matches overall.
   *
   * @param scopeId window/component id to scope the search to, or {@code null} for all windows
   * @param criteria the match filters (at least one must be set)
   * @param condition the condition to poll for
   * @param expectText for {@link WaitCondition#TEXT_CONTAINS}: the substring the match's display
   *     text must contain (case-insensitive); ignored otherwise
   * @param timeout overall wait budget
   * @param poll interval between probes (clamped to at least {@link #MIN_WAIT_POLL})
   * @throws IllegalArgumentException if {@code criteria} has no filter, {@code condition} is {@code
   *     null}, or {@code expectText} is missing for {@code TEXT_CONTAINS}
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer a probe within its bounded budget
   *     (a blocked EDT)
   */
  public static WaitResult waitFor(
      String scopeId,
      FindCriteria criteria,
      WaitCondition condition,
      String expectText,
      Duration timeout,
      Duration poll) {
    if (criteria == null || criteria.isBlank()) {
      throw new IllegalArgumentException(
          "wait_for requires at least one filter: name, class, text, or interactable");
    }
    if (condition == null) {
      throw new IllegalArgumentException(
          "condition is required (appears, disappears, enabled, or text_contains)");
    }
    if (condition == WaitCondition.TEXT_CONTAINS && blankToNull(expectText) == null) {
      throw new IllegalArgumentException("condition text_contains requires expect_text");
    }
    long pollMs = Math.max(MIN_WAIT_POLL.toMillis(), poll != null ? poll.toMillis() : 0);
    long start = System.nanoTime();
    long deadline = start + timeout.toNanos();
    while (true) {
      WaitProbe probe = waitProbe(scopeId, criteria, condition, expectText);
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      if (probe.met()) {
        return new WaitResult(true, elapsedMs, probe.count(), probe.match());
      }
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        return new WaitResult(false, elapsedMs, probe.count(), null);
      }
      long sleepMs = Math.min(pollMs, Math.max(1, remainingNanos / 1_000_000));
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new WaitResult(false, (System.nanoTime() - start) / 1_000_000, probe.count(), null);
      }
    }
  }

  private record WaitProbe(boolean met, int count, ComponentTreeSnapshot match) {}

  /**
   * One bounded EDT probe: re-resolve the scope (an unresolvable scope is zero matches, not an
   * error), run {@link #find} inline, and evaluate the condition on the match snapshots.
   */
  private static WaitProbe waitProbe(
      String scopeId, FindCriteria criteria, WaitCondition condition, String expectText) {
    return onEdt(
        () -> {
          List<Component> roots = null;
          if (scopeId != null) {
            Component root;
            try {
              root = ComponentAddress.resolve(scopeId); // on the EDT: resolves inline, no 2nd hop
            } catch (ComponentAddress.ComponentNotFoundException e) {
              // The scoped dialog/panel may not exist yet (or anymore): zero matches this poll.
              return new WaitProbe(condition == WaitCondition.DISAPPEARS, 0, null);
            }
            roots = List.of(root);
          }
          FindResult result = find(roots, criteria, DEFAULT_FIND_LIMIT, DEFAULT_TIMEOUT);
          ConditionProbe outcome =
              evaluateCondition(condition, result.matches(), result.total(), expectText);
          return new WaitProbe(outcome.met(), result.total(), outcome.match());
        },
        DEFAULT_TIMEOUT);
  }

  /** A condition evaluation: whether it held, and the satisfying match when there is one. */
  record ConditionProbe(boolean met, ComponentTreeSnapshot match) {}

  /**
   * Evaluates a {@link WaitCondition} against one probe's find results. Pure snapshot logic
   * (package-private for headless unit tests): first satisfying match wins; {@code DISAPPEARS}
   * looks at the total (which counts past the find limit).
   */
  static ConditionProbe evaluateCondition(
      WaitCondition condition, List<ComponentTreeSnapshot> matches, int total, String expectText) {
    switch (condition) {
      case APPEARS -> {
        return new ConditionProbe(!matches.isEmpty(), matches.isEmpty() ? null : matches.get(0));
      }
      case DISAPPEARS -> {
        return new ConditionProbe(total == 0, null);
      }
      case ENABLED -> {
        for (ComponentTreeSnapshot match : matches) {
          if (match.enabled()) {
            return new ConditionProbe(true, match);
          }
        }
        return new ConditionProbe(false, null);
      }
      case TEXT_CONTAINS -> {
        for (ComponentTreeSnapshot match : matches) {
          if (match.text() != null && containsIgnoreCase(match.text(), expectText)) {
            return new ConditionProbe(true, match);
          }
        }
        return new ConditionProbe(false, null);
      }
    }
    throw new IllegalStateException("unreachable");
  }

  // ---------------------------------------------------------------------------------------
  // Full text read (get_text)
  // ---------------------------------------------------------------------------------------

  /** Default number of characters returned by one {@link #readText} call. */
  public static final int TEXT_READ_DEFAULT = 4000;

  /** Hard cap on {@code maxChars} for one {@link #readText} call. */
  public static final int TEXT_READ_MAX = 32_000;

  /**
   * One page of a component's text.
   *
   * @param target the component the text was actually read from — a descendant when {@code
   *     get_text} auto-descended into a named composite
   * @param text the requested slice, {@code [offset, offset + maxChars)}
   * @param length the <i>full</i> length, so a caller can page without probing for the end
   * @param offset the offset the slice starts at (clamped into range)
   * @param truncated {@code true} when text remains past the returned slice
   * @param source where the text came from: {@code document}, {@code selection}, or the best-effort
   *     display-text source (a label's text, a tooltip, …)
   */
  public record TextRead(
      Component target, String text, int length, int offset, boolean truncated, String source) {}

  /**
   * Reads a component's text in full, paged — the escape hatch from the {@link
   * ComponentAddress#TEXT_CAP}-char cap that every snapshot field carries. A {@link JTextComponent}
   * is read from its document (or its selection when {@code selectedOnly}); anything else falls
   * back to the same best-effort display text the component tree reports, uncapped.
   *
   * <p>Auto-descends into a named composite exactly like {@code set_text} does, so the id that
   * types into a field also reads from it.
   *
   * @param component the component to read
   * @param offset first character to return (clamped to {@code [0, length]})
   * @param maxChars how many characters to return, capped at {@link #TEXT_READ_MAX}
   * @param selectedOnly read only the current selection rather than the whole document
   * @param timeout maximum time to wait for the EDT
   * @throws IllegalArgumentException if {@code component} holds several text fields (address one),
   *     or {@code selectedOnly} is asked of a non-text component
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static TextRead readText(
      Component component, int offset, int maxChars, boolean selectedOnly, Duration timeout) {
    Objects.requireNonNull(component, "component");
    int limit = Math.min(Math.max(maxChars, 1), TEXT_READ_MAX);
    int from = Math.max(offset, 0);
    return onEdt(() -> readTextOnEdt(component, from, limit, selectedOnly), timeout);
  }

  private static TextRead readTextOnEdt(
      Component component, int offset, int maxChars, boolean selectedOnly) {
    JTextComponent target = Interactions.textTargetOrNull(component);
    String full;
    String source;
    if (target != null) {
      if (selectedOnly) {
        String selected = target.getSelectedText();
        full = selected != null ? selected : "";
        source = "selection";
      } else {
        String document = target.getText();
        full = document != null ? document : "";
        source = "document";
      }
    } else {
      if (selectedOnly) {
        throw new IllegalArgumentException(
            ComponentAddress.simpleClassName(component.getClass())
                + " is not a text component, so it has no selection to read");
      }
      TextResult display = displayText(component);
      full = display.raw() != null ? display.raw() : "";
      source = display.source().name().toLowerCase(Locale.ROOT);
    }
    int length = full.length();
    int start = Math.min(offset, length);
    int end = Math.min(start + maxChars, length);
    return new TextRead(
        target != null ? target : component,
        full.substring(start, end),
        length,
        start,
        end < length,
        source);
  }

  // ---------------------------------------------------------------------------------------
  // Bean-property read (get_properties)
  // ---------------------------------------------------------------------------------------

  /** Cap on the rendered length of any single property value. */
  static final int PROPERTY_VALUE_CAP = 200;

  /** Cap on the number of elements listed for a collection/array-ish value. */
  private static final int PROPERTY_COLLECTION_CAP = 20;

  /**
   * Property names (lower-cased) whose getters are side-effecting or wasteful and so are never
   * read. {@code getGraphics()} allocates a {@link java.awt.Graphics} that is never disposed; there
   * is no useful string to show for it anyway. Kept intentionally small — most getters are pure
   * reads.
   */
  private static final Set<String> DENIED_PROPERTIES = Set.of("graphics");

  /**
   * Reads {@code component}'s bean-style properties (public no-arg {@code getX()}/{@code isX()}) on
   * the EDT, rendering each value to a string. Getters that throw are skipped; collection- and
   * array-valued results are element-capped, and every value is length-capped. When {@code names}
   * is non-empty only those properties (matched case-insensitively) are returned.
   *
   * @param component the component to read
   * @param names property names to filter to, or empty/{@code null} for all readable properties
   * @param timeout maximum time to wait for the EDT
   * @return an ordered (alphabetical) map of property name → rendered value
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Map<String, String> properties(
      Component component, List<String> names, Duration timeout) {
    Set<String> requested =
        (names == null || names.isEmpty())
            ? Set.of()
            : names.stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    boolean layout = requested.contains(LAYOUT_PRESET);
    Set<String> filter = requested.isEmpty() ? null : requested;
    return onEdt(
        () -> {
          if (!layout) {
            return readProperties(component, filter);
          }
          Map<String, String> out = new LinkedHashMap<>(readLayout(component));
          if (filter.size() > 1) {
            // "@layout" alongside explicit names: the preset first, then the extras.
            out.putAll(readProperties(component, filter));
          }
          return out;
        },
        timeout);
  }

  // ---------------------------------------------------------------------------------------
  // The @layout preset
  // ---------------------------------------------------------------------------------------

  /** The {@code properties} token that expands to the curated layout read. */
  public static final String LAYOUT_PRESET = "@layout";

  /**
   * One curated read answering "why is this component positioned/sized the way it is" — the
   * question that otherwise gets answered with high-scale screenshots and manual pixel arithmetic.
   *
   * <p>The load-bearing entry is {@code edgeDistances}: the gap on each side between this
   * component's bounds and its parent's <i>inner</i> (border- and insets-adjusted) bounds. That is
   * the number a caller actually wants when asking "is this exactly 8px from the edge", and it is
   * not derivable from a screenshot without knowing the parent's border insets.
   *
   * <p>Call on the EDT.
   */
  private static Map<String, String> readLayout(Component component) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("bounds", render(component.getBounds(), component, new IdentityHashMap<>()));
    if (component instanceof Container container) {
      out.put("insets", renderInsets(container.getInsets()));
    }
    if (component instanceof JComponent jComponent) {
      Border border = jComponent.getBorder();
      out.put("border", border == null ? "null" : renderBorder(border, component));
    }
    out.put("minimumSize", renderSize(component.getMinimumSize()));
    out.put("preferredSize", renderSize(component.getPreferredSize()));
    out.put("maximumSize", renderSize(component.getMaximumSize()));

    Container parent = component.getParent();
    if (parent == null) {
      out.put("parent", "null");
      return out;
    }
    out.put("parent", ComponentAddress.simpleClassName(parent.getClass()));
    LayoutManager layout = parent.getLayout();
    out.put(
        "parentLayout",
        layout == null ? "null" : ComponentAddress.simpleClassName(layout.getClass()));
    String constraints = layoutConstraints(layout, component);
    if (constraints != null) {
      out.put("constraints", constraints);
    }
    out.put("edgeDistances", renderEdgeDistances(component, parent));
    return out;
  }

  private static String renderSize(Dimension size) {
    return size == null ? "null" : size.width + "x" + size.height;
  }

  /**
   * Gaps between {@code component}'s bounds and the parent's inner bounds (its size less its own
   * insets, which for a {@link JComponent} include its border). Negative means the component
   * overhangs that edge — usually a layout bug, and worth seeing.
   */
  private static String renderEdgeDistances(Component component, Container parent) {
    Insets parentInsets = parent.getInsets();
    Rectangle bounds = component.getBounds();
    int left = bounds.x - parentInsets.left;
    int top = bounds.y - parentInsets.top;
    int right = (parent.getWidth() - parentInsets.right) - (bounds.x + bounds.width);
    int bottom = (parent.getHeight() - parentInsets.bottom) - (bounds.y + bounds.height);
    return "{left:" + left + ", right:" + right + ", top:" + top + ", bottom:" + bottom + "}";
  }

  /**
   * This component's constraints in its parent's layout, or {@code null} when the layout does not
   * expose them. {@link LayoutManager2} has no general getter, so only the recoverable cases are
   * handled: {@link BorderLayout} and {@link GridBagLayout} directly, and MigLayout reflectively
   * (so {@code core} takes no MigLayout dependency).
   */
  private static String layoutConstraints(LayoutManager layout, Component component) {
    if (layout instanceof BorderLayout borderLayout) {
      Object constraint = borderLayout.getConstraints(component);
      return constraint == null ? null : String.valueOf(constraint);
    }
    if (layout instanceof GridBagLayout gridBag) {
      GridBagConstraints c = gridBag.getConstraints(component);
      return c == null ? null : renderGridBag(c);
    }
    return migConstraints(layout, component);
  }

  private static String renderGridBag(GridBagConstraints c) {
    StringBuilder sb = new StringBuilder();
    sb.append("gridx=").append(gridPosition(c.gridx));
    sb.append(", gridy=").append(gridPosition(c.gridy));
    sb.append(", gridwidth=").append(gridSpan(c.gridwidth));
    sb.append(", gridheight=").append(gridSpan(c.gridheight));
    sb.append(", weightx=").append(c.weightx);
    sb.append(", weighty=").append(c.weighty);
    sb.append(", fill=").append(gridBagFill(c.fill));
    sb.append(", anchor=").append(c.anchor);
    sb.append(", insets=").append(renderInsets(c.insets));
    if (c.ipadx != 0 || c.ipady != 0) {
      sb.append(", ipadx=").append(c.ipadx).append(", ipady=").append(c.ipady);
    }
    return sb.toString();
  }

  private static String gridPosition(int value) {
    return value == GridBagConstraints.RELATIVE ? "RELATIVE" : String.valueOf(value);
  }

  private static String gridSpan(int value) {
    if (value == GridBagConstraints.REMAINDER) {
      return "REMAINDER";
    }
    return value == GridBagConstraints.RELATIVE ? "RELATIVE" : String.valueOf(value);
  }

  private static String gridBagFill(int fill) {
    return switch (fill) {
      case GridBagConstraints.NONE -> "NONE";
      case GridBagConstraints.HORIZONTAL -> "HORIZONTAL";
      case GridBagConstraints.VERTICAL -> "VERTICAL";
      case GridBagConstraints.BOTH -> "BOTH";
      default -> String.valueOf(fill);
    };
  }

  /**
   * MigLayout's per-component constraints, read reflectively — MigLayout is ubiquitous in real
   * Swing apps but {@code core} must not depend on it. Any reflective failure (absent class,
   * signature change) is simply "no constraints available".
   */
  private static String migConstraints(LayoutManager layout, Component component) {
    if (layout == null || !layout.getClass().getName().startsWith("net.miginfocom.")) {
      return null;
    }
    try {
      Method getter = layout.getClass().getMethod("getComponentConstraints", Component.class);
      Object constraints = getter.invoke(layout, component);
      return constraints == null ? null : cap(String.valueOf(constraints));
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return null;
    }
  }

  private static Map<String, String> readProperties(Component component, Set<String> filter) {
    Map<String, String> out = new TreeMap<>();
    Map<Component, String> idCache = new IdentityHashMap<>();
    for (Method method : component.getClass().getMethods()) {
      if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      Class<?> returnType = method.getReturnType();
      if (returnType == void.class) {
        continue;
      }
      String property = propertyName(method, returnType);
      if (property == null) {
        continue;
      }
      if (DENIED_PROPERTIES.contains(property.toLowerCase(Locale.ROOT))) {
        continue; // side-effecting/wasteful getter (e.g. getGraphics) — never invoked
      }
      if (filter != null && !filter.contains(property.toLowerCase(Locale.ROOT))) {
        continue;
      }
      try {
        out.put(property, render(method.invoke(component), component, idCache));
      } catch (Throwable ignored) {
        // Skip getters that throw (e.g. getLocationOnScreen on a hidden component).
      }
    }
    return new LinkedHashMap<>(out);
  }

  private static String propertyName(Method method, Class<?> returnType) {
    String name = method.getName();
    if (name.equals("getClass")) {
      return null;
    }
    if (name.startsWith("get") && name.length() > 3) {
      return decapitalize(name.substring(3));
    }
    if (name.startsWith("is")
        && name.length() > 2
        && (returnType == boolean.class || returnType == Boolean.class)) {
      return decapitalize(name.substring(2));
    }
    return null;
  }

  private static String decapitalize(String name) {
    if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
      return name; // e.g. "UI" stays "UI"
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static String render(Object value, Component owner, Map<Component, String> idCache) {
    if (value == null) {
      return "null";
    }
    if (value instanceof CharSequence
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>) {
      return cap(String.valueOf(value));
    }
    String structured = renderStructured(value, owner, idCache);
    if (structured != null) {
      return structured;
    }
    Class<?> type = value.getClass();
    if (type.isArray()) {
      return renderArray(value);
    }
    if (value instanceof Collection<?> collection) {
      return renderIterable(collection, collection.size());
    }
    if (value instanceof Map<?, ?> map) {
      return "{" + map.size() + (map.size() == 1 ? " entry}" : " entries}");
    }
    return cap(String.valueOf(value));
  }

  /**
   * Renders the AWT/Swing value types whose {@code toString()} is noise — {@code
   * java.awt.Color[r=0,g=120,b=215]}, {@code SynthBorder@2f3ae32a}, a model's identity hash, a
   * parent component's 250-character dump — as something short and readable, or {@code null} when
   * {@code value} is not one of them (the caller then falls back to generic rendering).
   *
   * <p>{@code owner} is the component the property was read from: a {@link Border} has no insets of
   * its own, only insets <i>for a component</i>, so rendering one needs it. It may be {@code null}
   * for a border read outside a component context, in which case only the class name shows.
   */
  private static String renderStructured(
      Object value, Component owner, Map<Component, String> idCache) {
    if (value instanceof Color color) {
      String hex =
          String.format(
              Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
      return color.getAlpha() < 255
          ? hex + "/" + String.format(Locale.ROOT, "%02x", color.getAlpha())
          : hex;
    }
    if (value instanceof Insets insets) {
      return renderInsets(insets);
    }
    if (value instanceof Dimension size) {
      return size.width + "x" + size.height;
    }
    if (value instanceof Rectangle bounds) {
      return "(" + bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height + ")";
    }
    if (value instanceof Point point) {
      return "(" + point.x + "," + point.y + ")";
    }
    if (value instanceof Border border) {
      return renderBorder(border, owner);
    }
    if (value instanceof Component component) {
      return ComponentAddress.simpleClassName(component.getClass())
          + "#"
          + cachedId(component, idCache);
    }
    if (value instanceof ListModel<?> model) {
      return ComponentAddress.simpleClassName(model.getClass()) + "(size=" + model.getSize() + ")";
    }
    if (value instanceof TableModel model) {
      return ComponentAddress.simpleClassName(model.getClass())
          + "(rows="
          + model.getRowCount()
          + ", cols="
          + model.getColumnCount()
          + ")";
    }
    if (value instanceof TreeModel model) {
      Object root = model.getRoot();
      return ComponentAddress.simpleClassName(model.getClass())
          + (root == null ? "(empty)" : "(root=" + truncate(String.valueOf(root)) + ")");
    }
    return null;
  }

  /** {@code {top:0, left:8, bottom:0, right:8}} — labelled so the field order is unambiguous. */
  private static String renderInsets(Insets insets) {
    return "{top:"
        + insets.top
        + ", left:"
        + insets.left
        + ", bottom:"
        + insets.bottom
        + ", right:"
        + insets.right
        + "}";
  }

  /**
   * {@code EmptyBorder{top:0, left:8, bottom:0, right:8}} — the class name plus the insets the
   * border reserves on {@code owner}. A border that throws from {@code getBorderInsets} (some
   * look-and-feel borders demand a specific component type) degrades to the bare class name.
   */
  private static String renderBorder(Border border, Component owner) {
    String name = ComponentAddress.simpleClassName(border.getClass());
    if (owner == null) {
      return name;
    }
    try {
      Insets insets = border.getBorderInsets(owner);
      return insets == null ? name : name + renderInsets(insets);
    } catch (RuntimeException ignored) {
      return name;
    }
  }

  /**
   * The addressable id of a component-valued property, memoized per {@code get_properties} call —
   * {@code parent}, {@code rootPane}, and {@code topLevelAncestor} often resolve to the same few
   * objects, and each {@link ComponentAddress#idOf} costs a hierarchy walk to compute name
   * uniqueness. Runs inline: {@link #readProperties} is already on the EDT.
   */
  private static String cachedId(Component component, Map<Component, String> idCache) {
    String cached = idCache.get(component);
    if (cached != null) {
      return cached;
    }
    String id;
    try {
      id = ComponentAddress.idOf(component);
    } catch (RuntimeException e) {
      id = "?"; // detached or mid-reparent — the class name still tells the caller what it is
    }
    idCache.put(component, id);
    return id;
  }

  private static String renderArray(Object array) {
    int length = Array.getLength(array);
    List<String> elements = new ArrayList<>(Math.min(length, PROPERTY_COLLECTION_CAP));
    for (int i = 0; i < length && i < PROPERTY_COLLECTION_CAP; i++) {
      elements.add(String.valueOf(Array.get(array, i)));
    }
    return joinCapped(elements, length);
  }

  private static String renderIterable(Iterable<?> iterable, int size) {
    List<String> elements = new ArrayList<>(Math.min(size, PROPERTY_COLLECTION_CAP));
    int i = 0;
    for (Object element : iterable) {
      if (i++ >= PROPERTY_COLLECTION_CAP) {
        break;
      }
      elements.add(String.valueOf(element));
    }
    return joinCapped(elements, size);
  }

  private static String joinCapped(List<String> elements, int total) {
    String joined = String.join(", ", elements);
    String body = total > elements.size() ? joined + ", … (" + total + ")" : joined;
    return cap("[" + body + "]");
  }

  private static String cap(String text) {
    return text.length() <= PROPERTY_VALUE_CAP ? text : text.substring(0, PROPERTY_VALUE_CAP) + "…";
  }

  // ---------------------------------------------------------------------------------------
  // Data-component contents (get_items)
  // ---------------------------------------------------------------------------------------

  /** Default cap on rows/items/nodes returned by {@link #readItems}. */
  public static final int DEFAULT_ITEM_LIMIT = 100;

  /** The data-component kind {@link #readItems} read. */
  public enum DataKind {
    TABLE,
    LIST,
    COMBO_BOX,
    TREE
  }

  /**
   * The (renderer-aware) contents of a data component.
   *
   * @param kind which data component was read
   * @param columns column names for a {@link DataKind#TABLE}; empty for the other kinds
   * @param rows the returned rows (capped at the limit): a table row's cell texts, or a single-cell
   *     row per list item / combo option / tree node (tree nodes are indented by depth)
   * @param total the total number of rows/items/nodes available (before the cap)
   * @param truncated {@code true} if {@code total} exceeded the limit and {@code rows} was capped
   * @param selectedIndex the lead selected row/item index, or {@code -1} (trees report {@code -1})
   */
  public record DataContents(
      DataKind kind,
      List<String> columns,
      List<List<String>> rows,
      int total,
      boolean truncated,
      int selectedIndex) {}

  /**
   * Reads the contents of a {@link JTable} (columns + cell text), {@link JList} / {@link JComboBox}
   * (items/options), or {@link JTree} (nodes, depth-indented) — the data an agent otherwise has no
   * way to see short of a screenshot. Text is <b>renderer-aware</b> (see {@link RenderedText}), so
   * it matches what the user sees rather than a model object's {@code toString()}. Output is capped
   * at {@code limit} rows/items/nodes.
   *
   * @param component the data component to read
   * @param limit maximum rows/items/nodes to return ({@code ≤ 0} uses {@link #DEFAULT_ITEM_LIMIT})
   * @param timeout maximum time to wait for the EDT
   * @throws IllegalArgumentException if {@code component} is not a table, list, combo box, or tree
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static DataContents readItems(Component component, int limit, Duration timeout) {
    int cap = limit > 0 ? limit : DEFAULT_ITEM_LIMIT;
    return onEdt(() -> readItemsOnEdt(component, cap), timeout);
  }

  private static DataContents readItemsOnEdt(Component component, int cap) {
    if (component instanceof JTable table) {
      int columnCount = table.getColumnCount();
      List<String> columns = new ArrayList<>(columnCount);
      for (int col = 0; col < columnCount; col++) {
        columns.add(truncate(table.getColumnName(col)));
      }
      int rowCount = table.getRowCount();
      List<List<String>> rows = new ArrayList<>(Math.min(rowCount, cap));
      for (int row = 0; row < rowCount && rows.size() < cap; row++) {
        List<String> cells = new ArrayList<>(columnCount);
        for (int col = 0; col < columnCount; col++) {
          cells.add(truncate(RenderedText.tableCell(table, row, col)));
        }
        rows.add(List.copyOf(cells));
      }
      return new DataContents(
          DataKind.TABLE,
          List.copyOf(columns),
          List.copyOf(rows),
          rowCount,
          rowCount > rows.size(),
          table.getSelectedRow());
    }
    if (component instanceof JList<?> list) {
      int size = list.getModel().getSize();
      List<List<String>> rows = new ArrayList<>(Math.min(size, cap));
      for (int i = 0; i < size && rows.size() < cap; i++) {
        rows.add(List.of(truncate(RenderedText.listItem(list, i))));
      }
      return new DataContents(
          DataKind.LIST,
          List.of(),
          List.copyOf(rows),
          size,
          size > rows.size(),
          list.getSelectedIndex());
    }
    if (component instanceof JComboBox<?> combo) {
      int size = combo.getItemCount();
      List<List<String>> rows = new ArrayList<>(Math.min(size, cap));
      for (int i = 0; i < size && rows.size() < cap; i++) {
        rows.add(List.of(truncate(RenderedText.comboItem(combo, i))));
      }
      return new DataContents(
          DataKind.COMBO_BOX,
          List.of(),
          List.copyOf(rows),
          size,
          size > rows.size(),
          combo.getSelectedIndex());
    }
    if (component instanceof JTree tree) {
      Object root = tree.getModel().getRoot();
      List<List<String>> rows = new ArrayList<>();
      int[] total = {0};
      if (root != null) {
        collectTreeRows(tree, root, 0, cap, rows, total);
      }
      return new DataContents(
          DataKind.TREE, List.of(), List.copyOf(rows), total[0], total[0] > rows.size(), -1);
    }
    throw new IllegalArgumentException(
        "get_items is not supported for "
            + ComponentAddress.simpleClassName(component.getClass())
            + " (expected a JTable, JList, JComboBox, or JTree)");
  }

  private static void collectTreeRows(
      JTree tree, Object node, int depth, int cap, List<List<String>> rows, int[] total) {
    total[0]++;
    if (rows.size() < cap) {
      rows.add(List.of("  ".repeat(depth) + truncate(RenderedText.treeNode(tree, node))));
    }
    var model = tree.getModel();
    int count = model.getChildCount(node);
    for (int i = 0; i < count; i++) {
      collectTreeRows(tree, model.getChild(node, i), depth + 1, cap, rows, total);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Window snapshotting
  // ---------------------------------------------------------------------------------------

  private static WindowSnapshot windowSnapshot(Window window, Map<String, Integer> nameFrequency) {
    int handle = ComponentAddress.windowHandle(window);
    String name = ComponentAddress.usableName(window);
    String id = (name != null && nameFrequency.getOrDefault(name, 0) == 1) ? name : "w" + handle;

    WindowSnapshot.Kind kind;
    String title = null;
    WindowSnapshot.Modality modality = null;
    Boolean iconified = null;
    Boolean maximized = null;

    if (window instanceof Frame frame) {
      kind = WindowSnapshot.Kind.FRAME;
      title = frame.getTitle();
      int state = frame.getExtendedState();
      iconified = (state & Frame.ICONIFIED) != 0;
      maximized = (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    } else if (window instanceof Dialog dialog) {
      kind = WindowSnapshot.Kind.DIALOG;
      title = dialog.getTitle();
      modality = modalityOf(dialog.getModalityType());
    } else {
      kind = WindowSnapshot.Kind.WINDOW;
    }

    boolean modal = modality != null && modality != WindowSnapshot.Modality.MODELESS;

    return new WindowSnapshot(
        id,
        handle,
        kind,
        title,
        window.getName(),
        ComponentAddress.idKindOf(window, id),
        Bounds.of(window.getBounds()),
        window.isVisible(),
        window.isShowing(),
        window.isFocused(),
        window.isActive(),
        modality,
        modal,
        iconified,
        maximized);
  }

  private static WindowSnapshot.Modality modalityOf(Dialog.ModalityType type) {
    return switch (type) {
      case MODELESS -> WindowSnapshot.Modality.MODELESS;
      case DOCUMENT_MODAL -> WindowSnapshot.Modality.DOCUMENT_MODAL;
      case APPLICATION_MODAL -> WindowSnapshot.Modality.APPLICATION_MODAL;
      case TOOLKIT_MODAL -> WindowSnapshot.Modality.TOOLKIT_MODAL;
    };
  }

  // ---------------------------------------------------------------------------------------
  // Component-tree snapshotting
  // ---------------------------------------------------------------------------------------

  private static ComponentTreeSnapshot buildNode(
      Component component,
      ComponentAddress.PathBase base,
      Map<String, Integer> nameFrequency,
      int depthBudget) {

    int childCount = (component instanceof Container container) ? container.getComponentCount() : 0;

    List<ComponentTreeSnapshot> children = List.of();
    if (childCount > 0 && depthBudget != 0) {
      int nextBudget = depthBudget < 0 ? depthBudget : depthBudget - 1;
      List<ComponentTreeSnapshot> built = new ArrayList<>(childCount);
      for (Component child : ((Container) component).getComponents()) {
        built.add(buildNode(child, base, nameFrequency, nextBudget));
      }
      children = List.copyOf(built);
    }

    TextResult text = displayText(component);

    String id = ComponentAddress.idFor(component, base, nameFrequency);
    return new ComponentTreeSnapshot(
        id,
        component.getName(),
        ComponentAddress.idKindOf(component, id),
        ComponentAddress.simpleClassName(component.getClass()),
        component.getClass().getName(),
        text.capped(),
        text.source(),
        Bounds.of(component.getBounds()),
        component.isShowing()
            ? Bounds.of(new Rectangle(component.getLocationOnScreen(), component.getSize()))
            : null,
        component.isVisible(),
        component.isShowing(),
        component.isEnabled(),
        component.isFocusable(),
        component.isFocusOwner(),
        selectionOf(component),
        childCount,
        children);
  }

  private record TextResult(String raw, ComponentTreeSnapshot.TextSource source) {
    static final TextResult NONE = new TextResult(null, ComponentTreeSnapshot.TextSource.NONE);

    static TextResult of(String raw, ComponentTreeSnapshot.TextSource source) {
      return raw == null ? NONE : new TextResult(raw, source);
    }

    /**
     * The text as emitted in a snapshot: one shared cap on every free-text field, whatever the
     * source (a tooltip or accessible name can carry a multi-paragraph toString just as easily as a
     * text component). Matching and {@code get_text} read {@link #raw()} instead, so a cap on what
     * is *displayed* never silently narrows what is *found*.
     */
    String capped() {
      return truncate(raw);
    }
  }

  /**
   * Best-effort display text: first non-blank of
   * button/label/text-component/border/tooltip/accessible name.
   */
  private static TextResult displayText(Component component) {
    if (component instanceof AbstractButton button) {
      String t = blankToNull(button.getText());
      if (t != null) {
        return TextResult.of(t, ComponentTreeSnapshot.TextSource.BUTTON_TEXT);
      }
    }
    if (component instanceof JLabel label) {
      String t = blankToNull(label.getText());
      if (t != null) {
        return TextResult.of(t, ComponentTreeSnapshot.TextSource.LABEL_TEXT);
      }
    }
    if (component instanceof JTextComponent textComponent) {
      String t = blankToNull(textComponent.getText());
      if (t != null) {
        return TextResult.of(t, ComponentTreeSnapshot.TextSource.TEXT_COMPONENT);
      }
    }
    if (component instanceof JComponent jComponent) {
      String borderTitle = titledBorderTitle(jComponent.getBorder());
      if (borderTitle != null) {
        return TextResult.of(borderTitle, ComponentTreeSnapshot.TextSource.TITLED_BORDER);
      }
      String tooltip = blankToNull(jComponent.getToolTipText());
      if (tooltip != null) {
        return TextResult.of(tooltip, ComponentTreeSnapshot.TextSource.TOOLTIP);
      }
    }
    AccessibleContext accessible = component.getAccessibleContext();
    if (accessible != null) {
      String accessibleName = blankToNull(accessible.getAccessibleName());
      if (accessibleName != null) {
        return TextResult.of(accessibleName, ComponentTreeSnapshot.TextSource.ACCESSIBLE_NAME);
      }
    }
    return TextResult.NONE;
  }

  /**
   * The best-effort display text for {@code component} (same source order as the component tree's
   * {@code text}), or {@code null} when none. Package-private for {@link EdtDiagnostics}'s
   * await-text condition. Call on the EDT.
   */
  static String displayTextOf(Component component) {
    return displayText(component).raw();
  }

  private static String titledBorderTitle(Border border) {
    if (border instanceof TitledBorder titledBorder) {
      return blankToNull(titledBorder.getTitle());
    }
    return null;
  }

  /** Selection state for the kinds that have one, else {@code null}. */
  private static ComponentTreeSnapshot.Selection selectionOf(Component component) {
    if (component instanceof JTabbedPane tabbedPane) {
      int index = tabbedPane.getSelectedIndex();
      String title = index >= 0 ? tabbedPane.getTitleAt(index) : null;
      return new ComponentTreeSnapshot.Selection(
          ComponentTreeSnapshot.SelectionKind.TABBED_PANE,
          false,
          index,
          List.of(),
          truncate(title));
    }
    if (component instanceof JComboBox<?> comboBox) {
      int index = comboBox.getSelectedIndex();
      Object item = comboBox.getSelectedItem();
      return new ComponentTreeSnapshot.Selection(
          ComponentTreeSnapshot.SelectionKind.COMBO_BOX,
          false,
          index,
          List.of(),
          item != null ? truncate(String.valueOf(item)) : null);
    }
    if (component instanceof JList<?> list) {
      Object value = list.getSelectedValue();
      return new ComponentTreeSnapshot.Selection(
          ComponentTreeSnapshot.SelectionKind.LIST,
          false,
          list.getSelectedIndex(),
          toIntList(list.getSelectedIndices()),
          value != null ? truncate(String.valueOf(value)) : null);
    }
    if (component instanceof JTable table) {
      return new ComponentTreeSnapshot.Selection(
          ComponentTreeSnapshot.SelectionKind.TABLE,
          false,
          table.getSelectedRow(),
          toIntList(table.getSelectedRows()),
          null);
    }
    if (component instanceof AbstractButton button && isToggle(button)) {
      return new ComponentTreeSnapshot.Selection(
          ComponentTreeSnapshot.SelectionKind.TOGGLE, button.isSelected(), -1, List.of(), null);
    }
    return null;
  }

  private static boolean isToggle(AbstractButton button) {
    // JCheckBox and JRadioButton extend JToggleButton; the menu variants do not.
    return button instanceof JToggleButton
        || button instanceof JCheckBoxMenuItem
        || button instanceof JRadioButtonMenuItem;
  }

  private static List<Integer> toIntList(int[] values) {
    if (values.length == 0) {
      return List.of();
    }
    List<Integer> out = new ArrayList<>(values.length);
    for (int value : values) {
      out.add(value);
    }
    return List.copyOf(out);
  }

  /**
   * The shared free-text cap ({@link ComponentAddress#TEXT_CAP} chars + {@code …}) applied to every
   * text field this class emits — tree/find text, selection text, and item/cell text — so no single
   * component's toString can blow a result's payload budget. Null-safe.
   */
  private static String truncate(String text) {
    if (text == null || text.length() <= ComponentAddress.TEXT_CAP) {
      return text;
    }
    return text.substring(0, ComponentAddress.TEXT_CAP) + "…";
  }

  private static String blankToNull(String text) {
    return (text != null && !text.isBlank()) ? text : null;
  }

  private static <T> T onEdt(java.util.concurrent.Callable<T> task, Duration timeout) {
    return EdtOps.call(task, timeout);
  }
}
