plugins {
    id("swingmcp.java-conventions")
}

// A sample extension, and the fixture the e2e suite loads through `extensions=`: it describes the
// demo app's StatusIndicator and adds a `demo_status` tool. It compiles against the extension API
// only (compileOnly — the agent provides it at run time) and reaches the demo's classes
// reflectively, the way a real extension reaches its application's.
description = "Sample swing-mcp extension for the demo app"

dependencies {
    compileOnly(project(":api"))
}

tasks.jar {
    // Unversioned, like the agent jar, so the e2e harness and docs can name one stable path.
    archiveBaseName.set("swing-mcp-demo-extension")
    archiveVersion.set("")
}
