# Third-party notices

swing-mcp itself is licensed under the [MIT License](LICENSE). The shaded agent jar
(`swing-mcp-agent.jar`) also bundles the third-party libraries below, relocated under
`io.github.paul_griffith.swingmcp.internal`. Each remains under its own license; the source for every
one is available from its project page and as a `-sources.jar` on Maven Central.

| Module | License | Project |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | MIT | https://github.com/modelcontextprotocol/java-sdk |
| `io.modelcontextprotocol.sdk:mcp-core` | MIT | https://github.com/modelcontextprotocol/java-sdk |
| `io.modelcontextprotocol.sdk:mcp-json-jackson3` | MIT | https://github.com/modelcontextprotocol/java-sdk |
| `org.eclipse.jetty:jetty-http` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty:jetty-io` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty:jetty-security` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty:jetty-server` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty:jetty-session` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty:jetty-util` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `org.eclipse.jetty.ee10:jetty-ee10-servlet` | EPL-2.0 OR Apache-2.0 | https://github.com/jetty/jetty.project |
| `jakarta.servlet:jakarta.servlet-api` | EPL-2.0 OR GPL-2.0-with-classpath-exception | https://github.com/jakartaee/servlet |
| `tools.jackson.core:jackson-core` | Apache-2.0 | https://github.com/FasterXML/jackson-core |
| `tools.jackson.core:jackson-databind` | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | Apache-2.0 | https://github.com/FasterXML/jackson-dataformats-text |
| `com.fasterxml.jackson.core:jackson-annotations` | Apache-2.0 | https://github.com/FasterXML/jackson-annotations |
| `org.snakeyaml:snakeyaml-engine` | Apache-2.0 | https://bitbucket.org/snakeyaml/snakeyaml-engine |
| `com.networknt:json-schema-validator` | Apache-2.0 | https://github.com/networknt/json-schema-validator |
| `com.ethlo.time:itu` | Apache-2.0 | https://github.com/ethlo/itu |
| `io.projectreactor:reactor-core` | Apache-2.0 | https://github.com/reactor/reactor-core |
| `org.reactivestreams:reactive-streams` | MIT-0 | https://github.com/reactive-streams/reactive-streams-jvm |
| `org.slf4j:slf4j-api` | MIT | https://github.com/qos-ch/slf4j |

The license and NOTICE files these libraries ship in their own jars are preserved in the agent jar
under `META-INF/` (`LICENSE` is merged, starting with swing-mcp's own; `NOTICE` is concatenated).

Full license texts:

- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- EPL-2.0: https://www.eclipse.org/legal/epl-2.0/
- GPL-2.0 with Classpath Exception: https://openjdk.org/legal/gplv2+ce.html
- MIT: https://opensource.org/license/mit
- MIT-0: https://opensource.org/license/mit-0

The build fails (`./gradlew :agent:checkThirdPartyNotices`, part of `check`) if the agent's runtime
classpath gains a module that is not listed above.
