import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("swingmcp.java-conventions")
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Kotlin Swing demo app: proves Kotlin-authored UIs need nothing special from the agent"

application {
    mainClass.set("io.github.paul_griffith.swingmcp.demokotlin.DemoKotlinApp")
}

// Emit Java 17 bytecode from Kotlin too (the java-conventions plugin already pins the JDK
// toolchain and options.release=17 for Java). The toolchain JDK may be 17 or 25 in CI; Kotlin
// targets 17 regardless, so no kotlin-stdlib version drift or bytecode surprises reach consumers.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
