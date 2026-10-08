package io.github.paul_griffith.swingmcp.core;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable component addressing: assigns each component an {@code id} and resolves an {@code id} back
 * to the live {@link Component}. Component-tree tools emit ids; interaction tools
 * (milestone&nbsp;4) consume them.
 *
 * <h2>Id grammar</h2>
 *
 * A component's id is either a <b>name id</b> or a <b>structural path</b>:
 *
 * <ul>
 *   <li><b>Name id</b> — the component's {@link Component#getName()} verbatim, used <i>only</i>
 *       when that name is set (non-blank) and unique within the addressing scope (all {@link
 *       Window#getWindows() live windows} for a window-anchored snapshot, or the snapshot root's
 *       subtree for a detached snapshot). Example: {@code okButton}.
 *   <li><b>Structural path</b> — used otherwise (unnamed, or a duplicated name). It is an anchor
 *       followed by {@code /}-separated segments walking down to the component:
 *       <pre>{@code w0/JRootPane[0]/JLayeredPane[0]/JPanel[0]/JButton[2]}</pre>
 *       <ul>
 *         <li>The anchor is {@code w{ordinal}} — the target's enclosing window's index in {@link
 *             Window#getWindows()} — or {@code $} when the target has no enclosing window (a
 *             detached tree, e.g. a panel built in a unit test).
 *         <li>Each segment is {@code SimpleClassName[k]} where {@code k} is the component's ordinal
 *             <i>among the siblings sharing its simple class name</i>, in {@link
 *             Container#getComponents()} order.
 *       </ul>
 *       A window's own structural id is just its anchor ({@code w0}); a detached root's is {@code
 *       $}.
 * </ul>
 *
 * <h2>Stability contract</h2>
 *
 * An id captured from one snapshot resolves to the same live component on a later call <b>as long
 * as the hierarchy has not changed structurally</b>:
 *
 * <ul>
 *   <li><b>Name ids</b> survive layout changes, re-parenting, and window reordering — they only
 *       require the name to remain set and unique.
 *   <li><b>Structural paths</b> survive re-layout but are invalidated by structural changes on the
 *       path: reordering or adding/removing earlier same-class siblings shifts indices, and (for
 *       {@code w{ordinal}} anchors) opening/closing other windows can renumber the anchor. Prefer
 *       naming components (via {@link Component#setName}) for durable addressing.
 * </ul>
 *
 * <h2>Failure modes</h2>
 *
 * Resolution never returns {@code null}; it throws:
 *
 * <ul>
 *   <li>{@link ComponentNotFoundException} — the component is gone or the path no longer leads
 *       anywhere (wrong window ordinal, a missing segment, a name that matches nothing).
 *   <li>{@link AmbiguousComponentException} — a name id now matches more than one component (a
 *       duplicate appeared since the id was minted, so the bare name is no longer unique).
 * </ul>
 *
 * <p>All resolution reads the AWT hierarchy on the Event Dispatch Thread via {@link EdtOps}, so the
 * public {@code resolve} methods are safe to call from any thread.
 */
public final class ComponentAddress {

  /** Cap for truncating best-effort text pulled from text components. */
  static final int TEXT_CAP = 200;

  private static final Duration DEFAULT_TIMEOUT = EdtOps.DEFAULT_TIMEOUT;

  /**
   * Matches a structural path: {@code w{ordinal}} or {@code $}, then optional {@code /segments}.
   */
  private static final Pattern STRUCTURAL = Pattern.compile("^(w\\d+|\\$)(/.*)?$");

  /** Matches one path segment: {@code SimpleClassName[index]}. */
  private static final Pattern SEGMENT = Pattern.compile("^(.+)\\[(\\d+)]$");

  private ComponentAddress() {}

  /**
   * How a component's {@code id} was derived — i.e. how durable a locator it is. Emitted alongside
   * the id so a consumer can tell a deliberately-assigned name from one Swing/AWT invented on its
   * own, without having to probe {@link Component#getName()} component by component.
   */
  public enum IdKind {
    /**
     * A deliberately-assigned, unique {@link Component#getName()} — the most durable locator, safe
     * to hard-code in an automated test.
     */
    NAME,
    /**
     * An AWT auto-generated default name ({@code frame3}, {@code dialog0}, {@code panel1}, …). It
     * is unique within a single run and so serves as an id, but AWT re-derives it from a global
     * counter on every run, so it is <b>not</b> stable across runs — do not hard-code it.
     */
    AUTO,
    /**
     * A structural path (e.g. {@code w0/JPanel[0]/JButton[2]}). Stable only while the hierarchy on
     * the path is unchanged; see the class-level stability contract.
     */
    STRUCTURAL
  }

  // ---------------------------------------------------------------------------------------
  // Id generation (package-private; driven by Introspection during a tree walk)
  // ---------------------------------------------------------------------------------------

  /**
   * The base a structural path is measured from, plus its anchor token. For a component with an
   * enclosing window this is the window ({@code w{handle}}); otherwise it is the snapshot root
   * itself ({@code $}).
   */
  record PathBase(Component base, String anchor) {}

  /** Determines the {@link PathBase} for a snapshot rooted at {@code root}. Call on the EDT. */
  static PathBase pathBaseFor(Component root) {
    Window window = enclosingWindow(root);
    if (window != null) {
      return new PathBase(window, "w" + windowHandle(window));
    }
    return new PathBase(root, "$");
  }

  // ---------------------------------------------------------------------------------------
  // Stable window handles (a process-lifetime registry; ordinals into Window.getWindows() rot)
  // ---------------------------------------------------------------------------------------

  /**
   * Process-lifetime window handles: each {@link Window} is assigned a monotonically increasing
   * integer the first time it is addressed, and keeps it for as long as it lives. The {@link
   * WeakHashMap} lets a closed, GC'd window's entry disappear without leaking, and a handle is
   * <b>never</b> reused or renumbered — so a {@code w{handle}} id minted from one snapshot resolves
   * to the same window on a later call regardless of how many windows have opened or closed since
   * (unlike an index into {@link Window#getWindows()}, whose array reorders and includes
   * disposed-but-not-yet-collected windows).
   *
   * <p>Accessed only on the Event Dispatch Thread (all id generation and resolution runs there), so
   * it needs no external synchronization.
   */
  private static final WeakHashMap<Window, Integer> WINDOW_HANDLES = new WeakHashMap<>();

  private static int nextWindowHandle = 0;

  /**
   * The stable handle for {@code window}, minting a fresh monotonic one on first sight. Call on the
   * EDT.
   */
  static int windowHandle(Window window) {
    Integer existing = WINDOW_HANDLES.get(window);
    if (existing != null) {
      return existing;
    }
    int handle = nextWindowHandle++;
    WINDOW_HANDLES.put(window, handle);
    return handle;
  }

  /**
   * The live window bearing stable {@code handle}, or {@code null} if none is registered (never
   * minted, or since GC'd). Call on the EDT.
   */
  static Window windowForHandle(int handle) {
    for (Map.Entry<Window, Integer> entry : WINDOW_HANDLES.entrySet()) {
      if (entry.getValue() == handle) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Computes the id for {@code component} given the path base and a name-frequency map over the
   * addressing scope. Call on the EDT.
   */
  static String idFor(Component component, PathBase base, Map<String, Integer> nameFrequency) {
    String name = usableName(component);
    if (name != null && nameFrequency.getOrDefault(name, 0) == 1) {
      return name;
    }
    return structuralPath(component, base);
  }

  /** Builds the structural path from {@code base} down to {@code component}. Call on the EDT. */
  static String structuralPath(Component component, PathBase base) {
    if (component == base.base()) {
      return base.anchor();
    }
    Deque<String> segments = new ArrayDeque<>();
    Component current = component;
    while (current != null && current != base.base()) {
      segments.addFirst(segmentFor(current));
      current = current.getParent();
    }
    StringBuilder sb = new StringBuilder(base.anchor());
    for (String segment : segments) {
      sb.append('/').append(segment);
    }
    return sb.toString();
  }

  private static String segmentFor(Component component) {
    String simpleName = simpleClassName(component.getClass());
    int index = 0;
    Container parent = component.getParent();
    if (parent != null) {
      for (Component sibling : parent.getComponents()) {
        if (sibling == component) {
          break;
        }
        if (simpleClassName(sibling.getClass()).equals(simpleName)) {
          index++;
        }
      }
    }
    return simpleName + "[" + index + "]";
  }

  // ---------------------------------------------------------------------------------------
  // Resolution (public entry points, EDT-guarded)
  // ---------------------------------------------------------------------------------------

  /** {@link #resolve(String, Duration)} with a default timeout. */
  public static Component resolve(String id) {
    return resolve(id, DEFAULT_TIMEOUT);
  }

  /**
   * Resolves {@code id} against all {@link Window#getWindows() live windows}. Handles {@code
   * w{ordinal}} structural paths and name ids; a {@code $}-anchored (detached) id must be resolved
   * with {@link #resolveIn(Component, String, Duration)} instead.
   *
   * @throws ComponentNotFoundException if nothing matches
   * @throws AmbiguousComponentException if a name id matches more than one component
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Component resolve(String id, Duration timeout) {
    return EdtOps.call(() -> resolveGlobal(id), timeout);
  }

  /** {@link #idOf(Component, Duration)} with a default timeout. */
  public static String idOf(Component component) {
    return idOf(component, DEFAULT_TIMEOUT);
  }

  /**
   * Computes the stable {@code id} a fresh snapshot would assign to a live {@code component} — the
   * inverse of {@link #resolve(String)}. For a component inside a live window this is a name id
   * (when the name is set and unique among all windows) or a {@code w{ordinal}} structural path,
   * either of which {@link #resolve(String)} can resolve back. A component with no enclosing window
   * yields a {@code $}-anchored (detached) id, resolvable only via {@link #resolveIn}.
   *
   * <p>Useful when an interaction {@linkplain Interactions#setText auto-descends} to a component
   * the caller did not name directly and the tool wants to report the id that actually received
   * input.
   *
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static String idOf(Component component, Duration timeout) {
    Objects.requireNonNull(component, "component");
    return EdtOps.call(
        () -> {
          PathBase base = pathBaseFor(component);
          List<? extends Component> nameScope =
              base.anchor().startsWith("w") ? List.of(Window.getWindows()) : List.of(base.base());
          return idFor(component, base, nameFrequency(nameScope));
        },
        timeout);
  }

  /** {@link #resolveIn(Component, String, Duration)} with a default timeout. */
  public static Component resolveIn(Component root, String id) {
    return resolveIn(root, id, DEFAULT_TIMEOUT);
  }

  /**
   * Resolves {@code id} relative to {@code root}. A {@code w{ordinal}} id is delegated to the
   * global resolver (it is window-anchored, not root-relative); a {@code $}-anchored id is walked
   * from {@code root}; any other id is treated as a name and searched within {@code root}'s
   * subtree.
   *
   * @throws ComponentNotFoundException if nothing matches
   * @throws AmbiguousComponentException if a name id matches more than one component
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Component resolveIn(Component root, String id, Duration timeout) {
    return EdtOps.call(() -> resolveRooted(root, id), timeout);
  }

  // ---------------------------------------------------------------------------------------
  // Single-hop resolve-and-act (close the resolve→act TOCTOU, cut EDT hops)
  // ---------------------------------------------------------------------------------------

  /**
   * Resolves {@code id} against all {@link Window#getWindows() live windows} <b>and</b> runs {@code
   * action} on the resolved component, both inside a single EDT task.
   *
   * <p>This is the preferred way for a tool to touch a component: doing {@link #resolve(String)}
   * (one EDT hop that hands a live component back to the transport thread) and then a second EDT
   * task to act opens a resolve→act TOCTOU window and, on a blocked EDT, burns a full timeout in
   * the resolve before the action even begins. Folding both into one task closes that window and
   * spends at most one timeout. {@code action} runs on the EDT, so it may read/drive the live
   * component directly (or, for a modal-safe action, post it fire-and-forget and return promptly —
   * see {@link Interactions}).
   *
   * @throws ComponentNotFoundException if nothing matches
   * @throws AmbiguousComponentException if a name id matches more than one component
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static <T> T withComponent(String id, Duration timeout, Function<Component, T> action) {
    Objects.requireNonNull(action, "action");
    return EdtOps.call(() -> action.apply(resolveGlobal(id)), timeout);
  }

  /**
   * Root-relative counterpart to {@link #withComponent}: resolves {@code id} relative to {@code
   * root} (a {@code w{ordinal}} id still resolves globally; a {@code $}-anchored id is walked from
   * {@code root}; any other id is a name searched within {@code root}'s subtree) and runs {@code
   * action} on the result, in one EDT task.
   *
   * @throws ComponentNotFoundException if nothing matches
   * @throws AmbiguousComponentException if a name id matches more than one component
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static <T> T withComponentIn(
      Component root, String id, Duration timeout, Function<Component, T> action) {
    Objects.requireNonNull(action, "action");
    return EdtOps.call(() -> action.apply(resolveRooted(root, id)), timeout);
  }

  private static Component resolveGlobal(String id) {
    Matcher m = STRUCTURAL.matcher(id);
    if (m.matches()) {
      String anchor = m.group(1);
      if (anchor.equals("$")) {
        throw new ComponentNotFoundException(
            "Detached ($) id '"
                + id
                + "' has no window anchor; resolve it with resolveIn(root, id)");
      }
      int handle = Integer.parseInt(anchor.substring(1));
      Window window = windowForHandle(handle);
      if (window == null) {
        throw new ComponentNotFoundException(
            "No live window with handle w" + handle + " (it was never addressed, or has closed)");
      }
      return walk(window, id, segmentsAfterAnchor(m));
    }
    return resolveByNameAmong(List.of(Window.getWindows()), id);
  }

  private static Component resolveRooted(Component root, String id) {
    Matcher m = STRUCTURAL.matcher(id);
    if (m.matches()) {
      if (m.group(1).startsWith("w")) {
        return resolveGlobal(id);
      }
      return walk(root, id, segmentsAfterAnchor(m));
    }
    return resolveByNameAmong(List.of(root), id);
  }

  private static List<String> segmentsAfterAnchor(Matcher structuralMatch) {
    String tail = structuralMatch.group(2); // e.g. "/JPanel[0]/JButton[1]" or null
    if (tail == null || tail.isEmpty()) {
      return List.of();
    }
    return List.of(tail.substring(1).split("/"));
  }

  private static Component walk(Component root, String id, List<String> segments) {
    Component current = root;
    for (String segment : segments) {
      Matcher m = SEGMENT.matcher(segment);
      if (!m.matches()) {
        throw new ComponentNotFoundException(
            "Malformed path segment '" + segment + "' in id '" + id + "'");
      }
      String simpleName = m.group(1);
      int wanted = Integer.parseInt(m.group(2));
      if (!(current instanceof Container container)) {
        throw new ComponentNotFoundException(
            "Cannot descend into non-container '" + segment + "' while resolving '" + id + "'");
      }
      Component match = null;
      int seen = 0;
      for (Component child : container.getComponents()) {
        if (simpleClassName(child.getClass()).equals(simpleName)) {
          if (seen == wanted) {
            match = child;
            break;
          }
          seen++;
        }
      }
      if (match == null) {
        throw new ComponentNotFoundException(
            "No child '"
                + segment
                + "' under "
                + describe(current)
                + " while resolving '"
                + id
                + "'");
      }
      current = match;
    }
    return current;
  }

  private static Component resolveByNameAmong(List<? extends Component> roots, String name) {
    List<Component> matches = new ArrayList<>();
    for (Component root : roots) {
      collectByName(root, name, matches);
    }
    if (matches.isEmpty()) {
      throw new ComponentNotFoundException("No component named '" + name + "'");
    }
    if (matches.size() > 1) {
      throw new AmbiguousComponentException(
          "Id '" + name + "' matches " + matches.size() + " components; it is no longer unique");
    }
    return matches.get(0);
  }

  private static void collectByName(Component component, String name, List<Component> out) {
    if (name.equals(component.getName())) {
      out.add(component);
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectByName(child, name, out);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------------

  /**
   * Name-frequency map (name → count) over the subtrees rooted at each of {@code roots}, for
   * uniqueness testing during id generation. Call on the EDT.
   */
  static Map<String, Integer> nameFrequency(List<? extends Component> roots) {
    Map<String, Integer> counts = new HashMap<>();
    for (Component root : roots) {
      countNames(root, counts);
    }
    return counts;
  }

  private static void countNames(Component component, Map<String, Integer> counts) {
    String name = usableName(component);
    if (name != null) {
      counts.merge(name, 1, Integer::sum);
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        countNames(child, counts);
      }
    }
  }

  /** The component's name if it is set and non-blank, else {@code null}. */
  static String usableName(Component component) {
    String name = component.getName();
    return (name != null && !name.isBlank()) ? name : null;
  }

  /**
   * Classifies the {@code id} that {@link #idFor} (or {@link Introspection}'s window snapshot)
   * assigned to {@code component}: whether it is a deliberate {@link IdKind#NAME name}, an AWT
   * {@link IdKind#AUTO auto-generated} default name, or a {@link IdKind#STRUCTURAL structural}
   * path. A pure function of the component's live state; call on the EDT (it reads {@link
   * Component#getName()}).
   */
  static IdKind idKindOf(Component component, String id) {
    String name = usableName(component);
    if (name != null && name.equals(id)) {
      return isAwtGeneratedName(component, name) ? IdKind.AUTO : IdKind.NAME;
    }
    return IdKind.STRUCTURAL;
  }

  /**
   * The AWT heavyweight types whose {@link Component#getName()} lazily fabricates a {@code prefix +
   * counter} default when {@code setName} was never called, paired with that prefix. Ordered
   * most-specific first (a {@link java.awt.FileDialog} must match before its {@link
   * java.awt.Dialog} supertype; the {@link java.awt.Window} supertypes come last). Lightweight
   * Swing components fabricate no such default — their name is {@code null} until set — so only
   * these AWT classes can produce an {@link IdKind#AUTO} id.
   */
  private static final List<Map.Entry<Class<?>, String>> AWT_DEFAULT_NAMES =
      List.of(
          Map.entry(java.awt.FileDialog.class, "filedlg"),
          Map.entry(java.awt.Dialog.class, "dialog"),
          Map.entry(java.awt.Frame.class, "frame"),
          Map.entry(java.awt.Window.class, "win"),
          Map.entry(java.awt.Panel.class, "panel"),
          Map.entry(java.awt.ScrollPane.class, "scrollpane"),
          Map.entry(java.awt.Canvas.class, "canvas"),
          Map.entry(java.awt.Button.class, "button"),
          Map.entry(java.awt.Checkbox.class, "checkbox"),
          Map.entry(java.awt.Choice.class, "choice"),
          Map.entry(java.awt.Label.class, "label"),
          Map.entry(java.awt.List.class, "list"),
          Map.entry(java.awt.Scrollbar.class, "scrollbar"),
          Map.entry(java.awt.TextField.class, "textfield"),
          Map.entry(java.awt.TextArea.class, "text"));

  /**
   * Whether {@code name} matches the default {@link Component#getName()} AWT would fabricate for
   * {@code component}'s type — the {@code prefix + digits} form (e.g. {@code frame3}) that flags an
   * un-{@code setName}'d heavyweight rather than a deliberate identifier.
   */
  private static boolean isAwtGeneratedName(Component component, String name) {
    for (Map.Entry<Class<?>, String> entry : AWT_DEFAULT_NAMES) {
      if (entry.getKey().isInstance(component)) {
        return matchesPrefixThenDigits(name, entry.getValue());
      }
    }
    return false;
  }

  private static boolean matchesPrefixThenDigits(String name, String prefix) {
    if (name.length() <= prefix.length() || !name.startsWith(prefix)) {
      return false;
    }
    for (int i = prefix.length(); i < name.length(); i++) {
      if (!Character.isDigit(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The nearest enclosing {@link Window} (the component itself if it is a window), else {@code
   * null}.
   */
  static Window enclosingWindow(Component component) {
    Component current = component;
    while (current != null) {
      if (current instanceof Window window) {
        return window;
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * {@link Class#getSimpleName()} with a fallback for anonymous/local classes (which report {@code
   * ""}).
   */
  static String simpleClassName(Class<?> type) {
    String simple = type.getSimpleName();
    if (!simple.isEmpty()) {
      return simple;
    }
    String full = type.getName();
    int dot = full.lastIndexOf('.');
    return dot >= 0 ? full.substring(dot + 1) : full;
  }

  private static String describe(Component component) {
    return simpleClassName(component.getClass())
        + (component.getName() != null ? "(" + component.getName() + ")" : "");
  }

  /** Thrown when an id resolves to no live component (gone, or a broken structural path). */
  public static final class ComponentNotFoundException extends RuntimeException {
    public ComponentNotFoundException(String message) {
      super(message);
    }
  }

  /** Thrown when a name id matches more than one live component. */
  public static final class AmbiguousComponentException extends RuntimeException {
    public AmbiguousComponentException(String message) {
      super(message);
    }
  }
}
