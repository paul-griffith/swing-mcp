plugins {
    // Auto-provision JDK toolchains (e.g. Java 17) when they are not already
    // installed locally or in CI. Declared with literal coordinates because the
    // version catalog is not yet available in the settings `plugins {}` block.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "swing-mcp"

include(
    "api",
    "core",
    "agent",
    "demo-java",
    "demo-kotlin",
    "demo-extension",
    "e2e",
)
