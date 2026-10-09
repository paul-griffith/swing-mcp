plugins {
    id("swingmcp.java-conventions")
}

description = "End-to-end tests: launch demo + shaded agent and drive it via the MCP Java SDK client"

// Resolvable classpaths for the demo JVMs the tests fork. Depending on the demo projects pulls in
// their compiled output plus any runtime deps (e.g. kotlin-stdlib for demo-kotlin), so the forked
// `java -cp <path> <MainClass>` command works in CI without hardcoded relative paths.
val demoJavaRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val demoKotlinRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
// The sample extension's jar, which the extension e2e test hands to the agent via `extensions=`.
// demo-extension has only a compileOnly dependency (the API), so this resolves to its jar alone.
val demoExtensionJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // core for the shared vocabulary; MCP SDK for the *client* side of the tests. The client speaks
    // the wire protocol over HTTP, so its (un-relocated) SDK classes never meet the agent's shaded
    // ones — only JSON-RPC crosses the socket.
    testImplementation(project(":core"))
    testImplementation(libs.mcp) {
        // Same packaging-bug prune as the agent module (a Surefire reporter dragged in as compile).
        exclude(group = "me.fabriciorby")
    }

    demoJavaRuntime(project(":demo-java"))
    demoKotlinRuntime(project(":demo-kotlin"))
    demoExtensionJar(project(":demo-extension"))
}

// The shaded agent jar the forked demo JVMs load via `-javaagent:` (and the attach CLI loads via
// `java -jar`). Referenced as a lazy provider so the archive path is resolved at execution time.
val agentShadowJar = project(":agent").tasks.named<Jar>("shadowJar")

// Like core: default to a real display so the headed e2e suite actually runs; pass
// -Pswingmcp.headless=true to force headless and confirm the guarded tests skip cleanly. Only the
// *forked* demo JVMs need a display — the test JVM here just drives them over HTTP — but the guard
// keys off this JVM's headless flag so it can skip when no display is intended.
val headless = providers.gradleProperty("swingmcp.headless").getOrElse("false")

tasks.withType<Test>().configureEach {
    // The forked demo JVMs need the shaded agent jar and the demos' runtime classpaths.
    dependsOn(agentShadowJar)
    inputs.files(demoJavaRuntime, demoKotlinRuntime).withPropertyName("demoRuntimeClasspaths")
    inputs.files(demoExtensionJar).withPropertyName("demoExtensionJar")

    systemProperty("java.awt.headless", headless)

    // Paths/classpaths for the harness, resolved lazily at execution time (doFirst) so we never
    // force the task graph at configuration time.
    doFirst {
        systemProperty("swingmcp.agentJar", agentShadowJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("swingmcp.demoJavaClasspath", demoJavaRuntime.asPath)
        systemProperty("swingmcp.demoKotlinClasspath", demoKotlinRuntime.asPath)
        systemProperty("swingmcp.demoExtensionJar", demoExtensionJar.singleFile.absolutePath)
        systemProperty("swingmcp.projectVersion", project.version.toString())
    }
}
