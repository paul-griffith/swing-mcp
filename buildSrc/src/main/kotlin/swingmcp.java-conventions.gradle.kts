plugins {
    java
    id("com.diffplug.spotless")
}

// The toolchain version can be raised (e.g. to 25) so tests run on a newer JVM in CI,
// while options.release below keeps the emitted bytecode compatible with Java 17.
val toolchainVersion = providers.gradleProperty("swingmcp.javaVersion").getOrElse("17").toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(toolchainVersion))
    }
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Access the default version catalog from within this precompiled script plugin.
val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Unit tests must not require a display; core Swing model tests run headless.
    systemProperty("java.awt.headless", "true")
}

spotless {
    java {
        target("src/*/java/**/*.java")
        targetExclude("**/build/generated/**")

        googleJavaFormat().reflowLongStrings()

        endWithNewline()
    }
}
