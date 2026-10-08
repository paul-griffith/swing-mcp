// Root build script. Shared configuration lives in the `swingmcp.java-conventions`
// convention plugin (see buildSrc/). Per-module scripts apply that plugin plus
// whatever extra plugins/dependencies they need.
allprojects {
    group = "io.github.paul-griffith.swingmcp"
    version = "1.0.0"
}
