plugins {
    id("swingmcp.java-conventions")
    `java-library`
}

// core is deliberately MCP-free: it holds the Swing introspection & driving logic
// so it can be unit-tested headlessly without any transport dependency.
description = "Swing introspection & driving core (no MCP dependency)"

dependencies {
    // Test-only: the headed integration tests launch the demo-java frame in-JVM to snapshot it
    // and screenshot it. demo-java does not depend on core, so there is no dependency cycle.
    testImplementation(project(":demo-java"))
}

// The java-conventions plugin forces headless=true for unit tests. Introspection has both:
//   * headless-safe unit tests (id scheme, tree logic) — run under any setting,
//   * headed integration tests — need a real display (local, or Xvfb in CI) and self-skip via
//     assumeFalse(GraphicsEnvironment.isHeadless()).
// Default to non-headless here so the headed tests actually run; pass
// -Pswingmcp.headless=true to force headless and confirm the guarded tests skip cleanly.
val headless = providers.gradleProperty("swingmcp.headless").getOrElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", headless)
    // Absolute directory the headed tests write captured PNGs to (asserted + eyeballed).
    systemProperty(
        "swingmcp.itScreenshotDir",
        layout.buildDirectory.dir("it-screenshots").get().asFile.absolutePath,
    )
}
