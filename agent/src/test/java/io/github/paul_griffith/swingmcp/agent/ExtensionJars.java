package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.api.SwingMcpExtension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Builds real extension jars for tests: compiles Java sources against the extension API with the
 * JDK's compiler and packages the classes, plus a {@code META-INF/services} file for the given
 * providers. Classes compiled this way are defined by the loader the test creates over the jar — as
 * in production — rather than by the test class loader.
 */
final class ExtensionJars {

  private ExtensionJars() {}

  /**
   * Compiles {@code sources} (fully-qualified class name → source text) and writes {@code jar}.
   *
   * @param work a scratch directory for sources and classes
   * @param jar the jar to write
   * @param sources the classes to compile
   * @param providers the provider class names to list in the services file (none: no file)
   * @param extraClasspath further jars/directories the sources compile against
   */
  static Path build(
      Path work,
      Path jar,
      Map<String, String> sources,
      List<String> providers,
      List<Path> extraClasspath)
      throws IOException {
    Path src = Files.createTempDirectory(work, "src");
    Path classes = Files.createTempDirectory(work, "classes");
    List<String> args = new ArrayList<>();
    for (Map.Entry<String, String> source : sources.entrySet()) {
      Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, source.getValue());
      args.add(file.toString());
    }
    StringBuilder classpath = new StringBuilder(apiLocation().toString());
    for (Path extra : extraClasspath) {
      classpath.append(java.io.File.pathSeparator).append(extra);
    }
    args.addAll(
        0, List.of("--release", "17", "-d", classes.toString(), "-cp", classpath.toString()));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("tests need a JDK (no system Java compiler)");
    }
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(diagnostics, true, StandardCharsets.UTF_8);
    int exit = compiler.run(null, out, out, args.toArray(new String[0]));
    if (exit != 0) {
      throw new IllegalStateException(
          "compiling test extension failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));
    }

    Files.createDirectories(jar.getParent());
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out2 = new JarOutputStream(file);
        Stream<Path> walk = Files.walk(classes)) {
      for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
        out2.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
        out2.write(Files.readAllBytes(path));
        out2.closeEntry();
      }
      if (!providers.isEmpty()) {
        out2.putNextEntry(new JarEntry("META-INF/services/" + SwingMcpExtension.class.getName()));
        out2.write((String.join("\n", providers) + "\n").getBytes(StandardCharsets.UTF_8));
        out2.closeEntry();
      }
    }
    return jar;
  }

  /** Where the extension API's classes live (a classes directory or a jar). */
  private static Path apiLocation() {
    try {
      return Path.of(
          SwingMcpExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
