plugins {
    id("swingmcp.java-conventions")
    `java-library`
}

// The extension API: the interfaces an extension jar implements and the types it is handed. It is
// JDK-only and is never relocated in the shaded agent jar, so an extension compiles against the
// agent jar (or this module) and runs against whatever agent version loads it, without touching
// any of the agent's shaded dependencies.
description = "swing-mcp extension API (JDK-only, unrelocated)"
