package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import io.github.paul_griffith.swingmcp.api.ExtensionContext;
import io.github.paul_griffith.swingmcp.api.SwingMcpExtension;
import io.github.paul_griffith.swingmcp.api.ToolDefinition;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds and loads {@link SwingMcpExtension}s.
 *
 * <p>Two sources, in this order:
 *
 * <ol>
 *   <li>the <b>classpath</b> the agent was loaded from ({@link ServiceLoader} over the agent's
 *       class loader — for an application that bundles its own extension);
 *   <li>each <b>{@code extensions} path</b>: a jar, or a directory whose {@code *.jar} files
 *       (directly inside, in name order) are loaded together. Each path gets its own class loader,
 *       a child of the agent's, so extensions see the API and the JDK but not each other, and a
 *       directory can carry an extension together with its own dependency jars.
 * </ol>
 *
 * <p>Loading never fails the agent. A path that does not exist, a provider that cannot be
 * instantiated, a duplicate name, or an extension whose {@code name()} or {@code register} throws
 * is recorded as a problem (logged by the caller) and skipped; everything else still loads. An
 * extension's contributions are kept only if its {@code register} returns normally.
 */
final class Extensions {

  /** The API version this agent implements; see {@link ExtensionContext#apiVersion()}. */
  static final int API_VERSION = 1;

  /** Allowed extension names: what {@link SwingMcpExtension#name()} documents. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  /** Stops a provider iterator that keeps failing from spinning forever. */
  private static final int MAX_PROVIDER_ERRORS = 32;

  private Extensions() {}

  /**
   * One loaded extension and what it contributed.
   *
   * @param name the extension's name
   * @param source where it came from: {@code classpath}, or the {@code extensions} path entry
   * @param describers the describers it registered, in order
   * @param tools the tools it registered, in order (not yet validated against the tool surface)
   */
  record Extension(
      String name,
      String source,
      List<ComponentDescriber> describers,
      List<ToolDefinition> tools) {}

  /**
   * The outcome of loading: the extensions that loaded, and one line per problem.
   *
   * @param extensions the loaded extensions, in load order
   * @param problems human-readable descriptions of everything that was skipped
   */
  record Loaded(List<Extension> extensions, List<String> problems) {}

  /**
   * Loads every extension visible to {@code classpathLoader} and in the {@code paths}.
   *
   * @param paths the {@code extensions} argument's entries
   * @param classpathLoader the loader whose classpath is searched for providers (the agent's own);
   *     also the parent of each path's loader
   * @param agentVersion reported to extensions through {@link ExtensionContext}
   */
  static Loaded load(List<String> paths, ClassLoader classpathLoader, String agentVersion) {
    List<String> problems = new ArrayList<>();
    Map<String, Extension> byName = new LinkedHashMap<>();

    for (SwingMcpExtension extension : providers(classpathLoader, null, "classpath", problems)) {
      register(extension, "classpath", agentVersion, byName, problems);
    }
    for (String path : paths) {
      ClassLoader loader = loaderFor(path, classpathLoader, problems);
      if (loader == null) {
        continue;
      }
      List<SwingMcpExtension> found = providers(loader, loader, path, problems);
      if (found.isEmpty()) {
        problems.add(
            "no extension found in "
                + path
                + " (does it contain META-INF/services/"
                + SwingMcpExtension.class.getName()
                + "?)");
      }
      for (SwingMcpExtension extension : found) {
        register(extension, path, agentVersion, byName, problems);
      }
    }
    return new Loaded(List.copyOf(byName.values()), List.copyOf(problems));
  }

  // ------------------------------------------------------------------ discovery

  /**
   * The providers {@code loader} can see, instantiated. When {@code definingLoader} is non-null,
   * only providers whose class it defines are kept (a path's loader also sees the classpath's
   * providers through its parent, which are loaded separately).
   */
  private static List<SwingMcpExtension> providers(
      ClassLoader loader, ClassLoader definingLoader, String source, List<String> problems) {
    List<SwingMcpExtension> out = new ArrayList<>();
    Iterator<ServiceLoader.Provider<SwingMcpExtension>> it;
    try {
      it = ServiceLoader.load(SwingMcpExtension.class, loader).stream().iterator();
    } catch (ServiceConfigurationError e) {
      problems.add("cannot list extensions in " + source + ": " + e.getMessage());
      return out;
    }
    int errors = 0;
    while (true) {
      ServiceLoader.Provider<SwingMcpExtension> provider;
      try {
        if (!it.hasNext()) {
          break;
        }
        provider = it.next();
      } catch (ServiceConfigurationError e) {
        problems.add("bad extension provider in " + source + ": " + e.getMessage());
        if (++errors >= MAX_PROVIDER_ERRORS) {
          break;
        }
        continue;
      }
      if (definingLoader != null && provider.type().getClassLoader() != definingLoader) {
        continue;
      }
      try {
        out.add(provider.get());
      } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
        problems.add(
            "cannot instantiate extension "
                + provider.type().getName()
                + " from "
                + source
                + ": "
                + rootMessage(e));
      }
    }
    return out;
  }

  /**
   * A class loader over the jar(s) of one {@code extensions} path entry, or {@code null} (with a
   * problem recorded) when the entry is missing or holds no jars.
   */
  private static ClassLoader loaderFor(String path, ClassLoader parent, List<String> problems) {
    Path entry = Path.of(path);
    List<Path> jars = new ArrayList<>();
    if (Files.isRegularFile(entry)) {
      jars.add(entry);
    } else if (Files.isDirectory(entry)) {
      try (Stream<Path> listing = Files.list(entry)) {
        listing
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            .sorted()
            .forEach(jars::add);
      } catch (IOException e) {
        problems.add("cannot list extension directory " + path + ": " + e.getMessage());
        return null;
      }
      if (jars.isEmpty()) {
        problems.add("extension directory " + path + " contains no .jar files");
        return null;
      }
    } else {
      problems.add("extension path " + path + " does not exist");
      return null;
    }
    URL[] urls = new URL[jars.size()];
    try {
      for (int i = 0; i < jars.size(); i++) {
        urls[i] = jars.get(i).toAbsolutePath().toUri().toURL();
      }
    } catch (MalformedURLException e) {
      problems.add("bad extension path " + path + ": " + e.getMessage());
      return null;
    }
    return new URLClassLoader("swing-mcp-extensions[" + path + "]", urls, parent);
  }

  // ------------------------------------------------------------------ registration

  private static void register(
      SwingMcpExtension extension,
      String source,
      String agentVersion,
      Map<String, Extension> byName,
      List<String> problems) {
    String type = extension.getClass().getName();
    String name;
    try {
      name = extension.name();
    } catch (RuntimeException | LinkageError e) {
      problems.add("extension " + type + " from " + source + ": name() threw " + rootMessage(e));
      return;
    }
    if (name == null || !NAME.matcher(name).matches()) {
      problems.add(
          "extension "
              + type
              + " from "
              + source
              + " has an invalid name '"
              + name
              + "' (letters, digits, _ . - ; at most 64 characters)");
      return;
    }
    Extension existing = byName.get(name);
    if (existing != null) {
      problems.add(
          "extension '"
              + name
              + "' from "
              + source
              + " skipped: an extension of that name was already loaded from "
              + existing.source());
      return;
    }
    Staging staging = new Staging(name, agentVersion);
    try {
      extension.register(staging);
    } catch (Exception | LinkageError e) {
      problems.add(
          "extension '" + name + "' from " + source + ": register threw " + rootMessage(e));
      return;
    } finally {
      staging.close();
    }
    byName.put(
        name,
        new Extension(name, source, List.copyOf(staging.describers), List.copyOf(staging.tools)));
  }

  /** The context handed to one extension's {@code register}; usable only during that call. */
  private static final class Staging implements ExtensionContext {
    private final String name;
    private final String agentVersion;
    private final List<ComponentDescriber> describers = new ArrayList<>();
    private final List<ToolDefinition> tools = new ArrayList<>();
    private boolean closed;

    Staging(String name, String agentVersion) {
      this.name = name;
      this.agentVersion = agentVersion;
    }

    @Override
    public int apiVersion() {
      return API_VERSION;
    }

    @Override
    public String agentVersion() {
      return agentVersion;
    }

    @Override
    public void addDescriber(ComponentDescriber describer) {
      checkOpen();
      describers.add(Objects.requireNonNull(describer, "describer"));
    }

    @Override
    public void addTool(ToolDefinition tool) {
      checkOpen();
      tools.add(Objects.requireNonNull(tool, "tool"));
    }

    @Override
    public void log(String message) {
      SwingMcpAgent.log("[" + name + "] " + message);
    }

    void close() {
      closed = true;
    }

    private void checkOpen() {
      if (closed) {
        throw new IllegalStateException(
            "extension '" + name + "' used its context after register returned");
      }
    }
  }

  /** {@code "ClassName: message"} of the innermost cause, which is what explains a failure. */
  static String rootMessage(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message != null ? ": " + message : "");
  }
}
