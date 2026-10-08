import com.github.jengelman.gradle.plugins.shadow.transformers.MergeLicenseResourceTransformer

plugins {
    id("swingmcp.java-conventions")
    alias(libs.plugins.shadow)
}

description = "javaagent bootstrap + MCP server + embedded Jetty (shaded, relocated)"

dependencies {
    implementation(project(":core"))

    // MCP SDK 2.0.0 pulls in mcp-core + mcp-json-jackson3 (Jackson 3 = `tools.jackson`),
    // Jackson 2 annotations (`com.fasterxml.jackson`), Reactor, SLF4J and networknt's
    // json-schema-validator. The json-schema-validator POM drags in a Maven Surefire test
    // reporter as a *compile* dependency (a packaging bug on their side) — prune it.
    implementation(libs.mcp) {
        exclude(group = "me.fabriciorby")
    }

    // Embedded HTTP server for the Streamable HTTP transport (EE10 / jakarta.servlet 6).
    implementation(libs.jetty.ee10.servlet)
}

// Package prefix under which every third-party package is relocated, so the agent never
// clashes with the host application's own Jackson / Jetty / SLF4J / etc.
val internalPrefix = "io.github.paul_griffith.swingmcp.internal"

// Third-party top-level package roots present on the runtime classpath (verified via
// `./gradlew :agent:dependencies`). Everything here is rewritten under $internalPrefix.
val relocatedPackages = listOf(
    "io.modelcontextprotocol",   // MCP SDK
    "com.fasterxml.jackson",     // Jackson 2 annotations
    "tools.jackson",             // Jackson 3 core/databind/dataformat
    "com.networknt",             // json-schema-validator
    "com.ethlo",                 // ethlo itu (date/time parsing)
    "org.snakeyaml",             // snakeyaml-engine (Jackson 3 YAML backend)
    "reactor",                   // Project Reactor
    "org.reactivestreams",       // Reactive Streams API
    "org.slf4j",                 // SLF4J API
    "org.eclipse.jetty",         // Jetty 12
    "jakarta.servlet",           // Servlet API (bundled with jetty-ee10-servlet)
)

tasks.shadowJar {
    // Make the shaded jar the primary artifact (no classifier), replacing the thin jar. The file
    // name is deliberately unversioned — always `agent/build/libs/swing-mcp-agent.jar` — so external
    // tooling (MCP client configs, IDE run configs, scripts) can hard-reference one stable path
    // across releases. The build version still travels in the manifest's `Implementation-Version`.
    archiveBaseName.set("swing-mcp-agent")
    archiveClassifier.set("")
    archiveVersion.set("")

    relocatedPackages.forEach { pkg ->
        relocate(pkg, "$internalPrefix.$pkg")
    }

    // Merge META-INF/services so relocated SPI providers (Jetty, Jackson, etc.) resolve.
    mergeServiceFiles()

    // Several dependencies ship a META-INF/LICENSE or NOTICE under the same name, and by default
    // only the first copy survives. Merge the license files (swing-mcp's own MIT license first),
    // concatenate the NOTICEs, and bundle the full third-party list alongside them.
    transform(MergeLicenseResourceTransformer::class.java) {
        artifactLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
        artifactLicenseSpdxId.set("MIT")
    }
    append("META-INF/NOTICE")
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }

    manifest {
        attributes(
            "Premain-Class" to "io.github.paul_griffith.swingmcp.agent.SwingMcpAgent",
            "Agent-Class" to "io.github.paul_griffith.swingmcp.agent.SwingMcpAgent",
            // `java -jar swing-mcp-agent.jar [<pid> [args]]` runs the dynamic-attach CLI.
            "Main-Class" to "io.github.paul_griffith.swingmcp.agent.AttachCli",
            // We do not redefine loaded classes, but we may retransform (future hooks).
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "swing-mcp-agent",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// The plain (thin) jar would collide with the shaded jar's name; disable it and make the
// shaded jar the artifact that `build` / `assemble` produce.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

// THIRD-PARTY-NOTICES.md lists every module bundled into the shaded jar. Fail the build when the
// runtime classpath gains one that is not listed (e.g. a new transitive dependency after an update).
val checkThirdPartyNotices by tasks.registering {
    description = "Verifies THIRD-PARTY-NOTICES.md lists every bundled runtime module."
    val notices = rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")
    val runtimeModules = configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { artifacts ->
            artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { m -> "${m.group}:${m.module}" } }
                .distinct()
                .sorted()
        }
    inputs.file(notices)
    inputs.property("runtimeModules", runtimeModules)
    doLast {
        val text = notices.asFile.readText()
        val missing = runtimeModules.get().filterNot { "`$it`" in text }
        if (missing.isNotEmpty()) {
            throw GradleException("THIRD-PARTY-NOTICES.md is missing bundled modules: $missing")
        }
    }
}

tasks.named("check") {
    dependsOn(checkThirdPartyNotices)
}
