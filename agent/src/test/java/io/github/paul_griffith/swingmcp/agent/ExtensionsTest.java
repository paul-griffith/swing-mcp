package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Extensions}: discovery on the classpath and in {@code extensions} paths,
 * and that every kind of broken extension is skipped with a reported problem while the rest load.
 * The extensions are compiled into real jars at test time (see {@link ExtensionJars}).
 */
class ExtensionsTest {

  @TempDir Path tmp;

  /** A loader that sees the extension API but no extension providers. */
  private final ClassLoader agentLoader = ExtensionsTest.class.getClassLoader();

  /** Source of an extension named {@code name} with one tool {@code <name>_ping}. */
  private static String extension(String pkg, String cls, String name) {
    return """
    package %s;
    import io.github.paul_griffith.swingmcp.api.*;
    public class %s implements SwingMcpExtension {
      public String name() { return "%s"; }
      public void register(ExtensionContext context) {
        context.addDescriber(component -> null);
        context.addTool(new ToolDefinition(
            "%s_ping", null, "Answers pong.", null, (arguments, ctx) -> "pong"));
      }
    }
    """
        .formatted(pkg, cls, name, name.replace('-', '_'));
  }

  private Path jar(String file, Map<String, String> sources, List<String> providers)
      throws IOException {
    return ExtensionJars.build(
        tmp, tmp.resolve("jars").resolve(file), sources, providers, List.of());
  }

  private Extensions.Loaded load(ClassLoader classpath, String... paths) {
    return Extensions.load(List.of(paths), classpath, "test");
  }

  // ------------------------------------------------------------------ discovery

  @Test
  void loadsAnExtensionFromAJar() throws IOException {
    Path jar =
        jar("alpha.jar", Map.of("t.Alpha", extension("t", "Alpha", "alpha")), List.of("t.Alpha"));

    Extensions.Loaded loaded = load(agentLoader, jar.toString());

    assertEquals(List.of(), loaded.problems());
    assertEquals(1, loaded.extensions().size());
    Extensions.Extension alpha = loaded.extensions().get(0);
    assertEquals("alpha", alpha.name());
    assertEquals(jar.toString(), alpha.source());
    assertEquals(1, alpha.describers().size());
    assertEquals("alpha_ping", alpha.tools().get(0).name());
  }

  @Test
  void loadsEveryJarInADirectoryInNameOrder() throws IOException {
    Path dir = tmp.resolve("exts");
    ExtensionJars.build(
        tmp,
        dir.resolve("b.jar"),
        Map.of("t.Zeta", extension("t", "Zeta", "zeta")),
        List.of("t.Zeta"),
        List.of());
    ExtensionJars.build(
        tmp,
        dir.resolve("a.jar"),
        Map.of("t.Eta", extension("t", "Eta", "eta")),
        List.of("t.Eta"),
        List.of());
    Files.writeString(dir.resolve("notes.txt"), "not a jar");

    Extensions.Loaded loaded = load(agentLoader, dir.toString());

    assertEquals(List.of(), loaded.problems());
    assertEquals(
        List.of("eta", "zeta"),
        loaded.extensions().stream().map(Extensions.Extension::name).toList());
  }

  @Test
  void aDirectoryCanCarryAnExtensionTogetherWithItsDependencyJar() throws IOException {
    Path dir = tmp.resolve("with-dep");
    Path helperJar =
        ExtensionJars.build(
            tmp,
            dir.resolve("helper.jar"),
            Map.of(
                "t.dep.Helper",
                "package t.dep; public class Helper { public static String suffix() { return"
                    + " \"_ping\"; } }"),
            List.of(),
            List.of());
    String beta =
        """
        package t;
        import io.github.paul_griffith.swingmcp.api.*;
        public class Beta implements SwingMcpExtension {
          public String name() { return "beta"; }
          public void register(ExtensionContext context) {
            context.addTool(new ToolDefinition(
                "beta" + t.dep.Helper.suffix(), null, "d", null, (a, c) -> "ok"));
          }
        }
        """;
    ExtensionJars.build(
        tmp,
        dir.resolve("main.jar"),
        Map.of("t.Beta", beta),
        List.of("t.Beta"),
        List.of(helperJar));

    Extensions.Loaded loaded = load(agentLoader, dir.toString());

    assertEquals(List.of(), loaded.problems());
    assertEquals("beta_ping", loaded.extensions().get(0).tools().get(0).name());
  }

  @Test
  void findsProvidersOnTheClasspathOnceEach() throws IOException {
    Path cpJar = jar("cp.jar", Map.of("t.Cp", extension("t", "Cp", "cp")), List.of("t.Cp"));
    Path pathJar =
        jar("alpha.jar", Map.of("t.Alpha", extension("t", "Alpha", "alpha")), List.of("t.Alpha"));
    try (URLClassLoader classpath =
        new URLClassLoader(new URL[] {cpJar.toUri().toURL()}, agentLoader)) {

      Extensions.Loaded loaded = load(classpath, pathJar.toString());

      assertEquals(List.of(), loaded.problems(), "the classpath provider must not load twice");
      assertEquals(
          List.of("cp", "alpha"),
          loaded.extensions().stream().map(Extensions.Extension::name).toList());
      assertEquals("classpath", loaded.extensions().get(0).source());
    }
  }

  // ------------------------------------------------------------------ problems

  @Test
  void aMissingPathIsAProblemNotAFailure() throws IOException {
    Path jar =
        jar("alpha.jar", Map.of("t.Alpha", extension("t", "Alpha", "alpha")), List.of("t.Alpha"));
    String missing = tmp.resolve("nope.jar").toString();

    Extensions.Loaded loaded = load(agentLoader, missing, jar.toString());

    assertEquals(1, loaded.extensions().size(), "the good path still loads");
    assertEquals(1, loaded.problems().size());
    assertTrue(loaded.problems().get(0).contains("does not exist"), loaded.problems().get(0));
  }

  @Test
  void aJarWithoutAServicesFileIsAProblem() throws IOException {
    Path jar = jar("plain.jar", Map.of("t.Alpha", extension("t", "Alpha", "alpha")), List.of());

    Extensions.Loaded loaded = load(agentLoader, jar.toString());

    assertEquals(List.of(), loaded.extensions());
    assertTrue(loaded.problems().get(0).contains("no extension found"), loaded.problems().get(0));
  }

  @Test
  void anExtensionWhoseRegisterThrowsIsSkippedEntirely() throws IOException {
    String thrower =
        """
        package t;
        import io.github.paul_griffith.swingmcp.api.*;
        public class Thrower implements SwingMcpExtension {
          public String name() { return "thrower"; }
          public void register(ExtensionContext context) throws Exception {
            context.addTool(new ToolDefinition("thrower_ping", null, "d", null, (a, c) -> "x"));
            throw new java.io.IOException("cannot read config");
          }
        }
        """;
    Path jar =
        jar(
            "mixed.jar",
            Map.of("t.Thrower", thrower, "t.Alpha", extension("t", "Alpha", "alpha")),
            List.of("t.Thrower", "t.Alpha"));

    Extensions.Loaded loaded = load(agentLoader, jar.toString());

    assertEquals(
        List.of("alpha"), loaded.extensions().stream().map(Extensions.Extension::name).toList());
    assertEquals(1, loaded.problems().size());
    assertTrue(loaded.problems().get(0).contains("cannot read config"), loaded.problems().get(0));
  }

  @Test
  void anExtensionThatCannotBeInstantiatedIsAProblem() throws IOException {
    String broken =
        """
        package t;
        import io.github.paul_griffith.swingmcp.api.*;
        public class Broken implements SwingMcpExtension {
          public Broken() { throw new IllegalStateException("bad constructor"); }
          public String name() { return "broken"; }
          public void register(ExtensionContext context) {}
        }
        """;
    Path jar = jar("broken.jar", Map.of("t.Broken", broken), List.of("t.Broken"));

    Extensions.Loaded loaded = load(agentLoader, jar.toString());

    assertEquals(List.of(), loaded.extensions());
    assertTrue(loaded.problems().get(0).contains("cannot instantiate"), loaded.problems().get(0));
  }

  @Test
  void duplicateNamesKeepTheFirstOneFound() throws IOException {
    Path first = jar("a.jar", Map.of("t.One", extension("t", "One", "same")), List.of("t.One"));
    Path second = jar("b.jar", Map.of("t.Two", extension("t", "Two", "same")), List.of("t.Two"));

    Extensions.Loaded loaded = load(agentLoader, first.toString(), second.toString());

    assertEquals(1, loaded.extensions().size());
    assertEquals(first.toString(), loaded.extensions().get(0).source());
    assertTrue(loaded.problems().get(0).contains("already loaded"), loaded.problems().get(0));
  }

  @Test
  void anInvalidNameIsAProblem() throws IOException {
    Path jar =
        jar("bad.jar", Map.of("t.Bad", extension("t", "Bad", "bad name!")), List.of("t.Bad"));

    Extensions.Loaded loaded = load(agentLoader, jar.toString());

    assertEquals(List.of(), loaded.extensions());
    assertTrue(loaded.problems().get(0).contains("invalid name"), loaded.problems().get(0));
  }

  @Test
  void theContextReportsTheAgentsSettings() throws IOException {
    String probe =
        """
        package t;
        import io.github.paul_griffith.swingmcp.api.*;
        public class Probe implements SwingMcpExtension {
          public String name() { return "probe"; }
          public void register(ExtensionContext context) {
            String desc = context.apiVersion() + "/" + context.agentVersion();
            context.addTool(new ToolDefinition("probe_info", null, desc, null, (a, c) -> desc));
          }
        }
        """;
    Path jar = jar("probe.jar", Map.of("t.Probe", probe), List.of("t.Probe"));

    Extensions.Loaded loaded = Extensions.load(List.of(jar.toString()), agentLoader, "9.9.9");

    assertEquals("1/9.9.9", loaded.extensions().get(0).tools().get(0).description());
  }
}
