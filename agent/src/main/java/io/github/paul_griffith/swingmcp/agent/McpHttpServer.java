package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import io.github.paul_griffith.swingmcp.core.ComponentDescribers;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import tools.jackson.databind.json.JsonMapper;

/**
 * The embedded MCP-over-HTTP server: an {@link Server Jetty} bound to a loopback address serving
 * the MCP SDK's {@link HttpServletStreamableServerTransportProvider Streamable HTTP transport} at
 * {@code /mcp}, guarded by {@link LoopbackSecurityFilter}, with the full {@link SwingTools} surface
 * (introspection, interaction, and diagnostic tools), any tools loaded {@link Extensions}
 * contribute, and a set of client {@code instructions} registered.
 *
 * <p>All Jetty worker threads are daemon threads, so a running server never keeps the host JVM
 * alive after the application exits.
 */
final class McpHttpServer {

  /**
   * The path the MCP transport is served at (both the servlet mapping and the advertised endpoint).
   */
  static final String MCP_PATH = "/mcp";

  /**
   * The only interface the server ever binds. Not configurable: anyone who can reach the endpoint
   * can drive the host app, so it is never exposed beyond this machine.
   */
  static final String BIND_HOST = "127.0.0.1";

  /**
   * Server-level {@code instructions} sent to every MCP client on initialize — the workflow and
   * conventions that would otherwise have to be rediscovered from per-tool descriptions.
   */
  private static final String INSTRUCTIONS =
      """
      This server drives a live Java Swing UI in-process. Workflow and conventions:

      - Locate a target with find_components (prefer it over walking get_component_tree in a large \
      UI), then pass the returned `id` to the acting tools. A zero-match result carries `scanned` \
      and a `hint` (scope + near-miss names/classes) — follow it instead of blind retries.
      - list_windows returns `{windows: [...], hiddenCount?}` — only SHOWING windows by default, \
      focused-first, with `hiddenCount` reporting any suppressed hidden windows. Pass \
      `include_hidden: true` for the full list and `title` to filter by title substring.
      - screenshot with `save_path` (absolute path) writes a full-resolution PNG to disk and \
      returns text-only metadata — use it to capture without spending context on image payload. \
      `scale` is image px per LOGICAL px (`2` = twice the component's tree bounds, on any \
      display); inline captures are capped at 1200 px on the longest edge, `save_path` is not.
      - Reading text: every `text` field in a tree/find/items result is capped at 200 chars. To \
      read more — a whole JTextArea, a long label — call get_text, which pages with \
      `offset`/`max_chars` and reports the full `length`. Do not tile screenshots to read text.
      - Layout questions ("is this 8px from the edge?", "what border is this?") go to \
      get_properties with `properties: ["@layout"]`, not to a zoomed screenshot: it returns \
      bounds, insets, border, min/preferred/max size, the parent's layout manager and this \
      component's constraints in it, plus `edgeDistances` to the parent's inner bounds. Property \
      values are rendered structurally (Color as `#rrggbb`, a component as `ClassName#<id>` you \
      can pass straight back).
      - Tables: address cells by `row`/`column` on click (its `count: 2` opens the cell editor) \
      and select, in the same view coordinates get_items reports — never by pixel arithmetic \
      from rowHeight.
      - A menu/tree path that does not match reports the parent's actual children in the error; \
      read them rather than guessing again, since the labels come from renderers and often \
      differ from the underlying model values.
      - Handle durability (`idKind`): `name` = a deliberate, stable getName() — safe to reuse; \
      `auto` = an AWT-invented default like `frame2` that changes between runs — do not hard-code \
      it; absent = a structural path, stable only while the hierarchy on the path is unchanged. \
      Window handles (`w<n>`) are assigned once and stay stable for the process lifetime.
      - Modal-dialog workflow: click, press_key, and menu-path select are modal-safe — they \
      dispatch and return promptly with `edtSettled`, never blocking on a dialog's nested event \
      loop. Their result reports the UI delta directly: `opened[]` lists any windows/popups that \
      appeared (with ready-to-use ids) and `closed[]` any that went away — inspect an opened \
      dialog via get_component_tree / screenshot, interact, dismiss it, and the dismissing call's \
      `closed[]` confirms it is gone. When `deltaUnknown: true` (EDT still busy) or an open is \
      slow/async, fall back to wait_for.
      - Waiting: wait_for polls the find_components filters until a component `appears` / \
      `disappears` / is `enabled` / `text_contains` some text — the tool for async UI (its scope \
      is re-resolved each poll, so it works for a dialog that has not opened yet); a timeout is \
      `met: false`, not an error. await_idle is the other axis: flush the EDT, or wait on a \
      window title / a known component id's state.
      - If a tool reports `edtSettled: false` or the UI seems frozen, call edt_stack — it reads \
      the EDT's stack from outside the EDT, so it works even when the EDT is fully blocked.
      - Errors are structured JSON: {error: <code>, message, hint?}. Follow the `hint` (e.g. \
      component_not_found → re-locate the target with find_components).
      """;

  /**
   * The server {@code instructions}: the base text plus, when extensions are loaded, a line naming
   * them and their tools.
   */
  static String instructions(List<ExtensionSummary> extensions) {
    StringBuilder text = new StringBuilder(INSTRUCTIONS);
    if (!extensions.isEmpty()) {
      List<String> parts = new ArrayList<>(extensions.size());
      for (ExtensionSummary e : extensions) {
        List<String> what = new ArrayList<>(2);
        if (!e.tools().isEmpty()) {
          what.add("tools: " + String.join(", ", e.tools()));
        }
        if (e.describers() > 0) {
          what.add("describes custom components");
        }
        parts.add(
            "`" + e.name() + "`" + (what.isEmpty() ? "" : " (" + String.join("; ", what) + ")"));
      }
      text.append("- Extensions loaded: ")
          .append(String.join(", ", parts))
          .append(
              ". Extension tools take the same component ids and return the same structured"
                  + " errors as the built-in tools, and the text extensions supply for custom"
                  + " components appears as `text` in tree/find results like any other.\n");
    }
    return text.toString();
  }

  /**
   * What the instructions and the startup log say about one loaded extension.
   *
   * @param name the extension's name
   * @param tools the names of its tools that were accepted
   * @param describers how many describers it registered
   */
  record ExtensionSummary(String name, List<String> tools, int describers) {}

  private final Server jetty;
  private final McpSyncServer mcpServer;
  private final int port;

  private McpHttpServer(Server jetty, McpSyncServer mcpServer, int port) {
    this.jetty = jetty;
    this.mcpServer = mcpServer;
    this.port = port;
  }

  /**
   * Builds, wires, and starts the server described by {@code config}. On return the server is
   * accepting connections on the loopback address.
   *
   * @throws Exception if the server fails to bind or start (callers log and swallow this so the
   *     host application is never disrupted)
   */
  static McpHttpServer start(AgentConfig config) throws Exception {
    // Extensions load before the tool surface is built; a broken one is logged and skipped.
    Extensions.Loaded extensions =
        Extensions.load(config.extensions(), SwingMcpAgent.class.getClassLoader(), version());

    JsonMapper jsonMapper = JsonMapper.builder().build();
    McpJsonMapper mcpJson = new JacksonMcpJsonMapper(jsonMapper);

    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mcpJson)
            .mcpEndpoint(MCP_PATH)
            .build();

    SwingTools tools = new SwingTools(jsonMapper);
    List<McpServerFeatures.SyncToolSpecification> toolSpecs =
        new ArrayList<>(tools.specifications());
    Set<String> builtInNames = new LinkedHashSet<>();
    for (McpServerFeatures.SyncToolSpecification spec : toolSpecs) {
      builtInNames.add(spec.tool().name());
    }
    ExtensionTools.Built extensionTools =
        new ExtensionTools(tools.support()).build(extensions.extensions(), builtInNames);
    toolSpecs.addAll(extensionTools.specifications());
    List<ExtensionSummary> summaries = installExtensions(extensions, extensionTools);

    McpSyncServer mcpServer =
        McpServer.sync(transport)
            .serverInfo("swing-mcp", version())
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .instructions(instructions(summaries))
            .tools(toolSpecs.toArray(new McpServerFeatures.SyncToolSpecification[0]))
            .build();

    // The expected Host port is resolved lazily so an ephemeral port=0 is validated against
    // the port Jetty actually binds (known only after start()).
    AtomicInteger effectivePort = new AtomicInteger(config.port());
    SecurityPolicy policy =
        new SecurityPolicy(effectivePort::get, config.hasToken() ? config.token() : null);

    QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setName("swing-mcp-jetty");
    threadPool.setDaemon(true); // never hold the host JVM open
    // A single localhost MCP client embedded in a host JVM needs only a small pool; keep the
    // footprint polite rather than Jetty's default (200).
    threadPool.setMaxThreads(8);
    Server server = new Server(threadPool);

    // One acceptor + one selector is ample for a single localhost client, and keeps the connector's
    // thread demand well under the small pool cap above (Jetty's defaults scale selectors with core
    // count and would otherwise starve an 8-thread pool on a many-core host).
    ServerConnector connector = new ServerConnector(server, 1, 1);
    connector.setHost(BIND_HOST);
    connector.setPort(config.port());
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");
    context.addFilter(
        new FilterHolder(new LoopbackSecurityFilter(policy)),
        "/*",
        EnumSet.of(DispatcherType.REQUEST));
    context.addServlet(new ServletHolder(transport), MCP_PATH);
    server.setHandler(context);

    try {
      server.start();
    } catch (Exception e) {
      server.stop();
      throw e;
    }
    effectivePort.set(connector.getLocalPort());
    return new McpHttpServer(server, mcpServer, connector.getLocalPort());
  }

  /**
   * The port the server is actually listening on (resolved even when {@code port=0} was requested).
   */
  int port() {
    return port;
  }

  /** Stops the MCP server and the underlying Jetty instance, ignoring shutdown errors. */
  void stop() {
    try {
      mcpServer.closeGracefully();
    } catch (RuntimeException ignored) {
      // best-effort shutdown
    }
    try {
      jetty.stop();
    } catch (Exception ignored) {
      // best-effort shutdown
    }
  }

  /**
   * Installs the loaded extensions' describers into core, logs what loaded and every problem, and
   * returns the per-extension summaries for the instructions.
   */
  private static List<ExtensionSummary> installExtensions(
      Extensions.Loaded loaded, ExtensionTools.Built tools) {
    ComponentDescribers.setFailureReporter(SwingMcpAgent::log);
    List<ComponentDescribers.Registration> describers = new ArrayList<>();
    List<ExtensionSummary> summaries = new ArrayList<>();
    for (Extensions.Extension extension : loaded.extensions()) {
      for (ComponentDescriber describer : extension.describers()) {
        describers.add(new ComponentDescribers.Registration(extension.name(), describer));
      }
      List<String> toolNames =
          tools.toolNamesByExtension().getOrDefault(extension.name(), List.of());
      summaries.add(
          new ExtensionSummary(extension.name(), toolNames, extension.describers().size()));
      SwingMcpAgent.log(
          "extension '"
              + extension.name()
              + "' loaded from "
              + extension.source()
              + ": "
              + extension.describers().size()
              + (extension.describers().size() == 1 ? " describer, " : " describers, ")
              + toolNames.size()
              + (toolNames.size() == 1 ? " tool" : " tools")
              + (toolNames.isEmpty() ? "" : " " + toolNames));
    }
    ComponentDescribers.install(describers);
    for (String problem : loaded.problems()) {
      SwingMcpAgent.log("extension problem: " + problem);
    }
    for (String problem : tools.problems()) {
      SwingMcpAgent.log("extension problem: " + problem);
    }
    return List.copyOf(summaries);
  }

  /** The agent version from the jar manifest ({@code Implementation-Version}), or {@code "dev"}. */
  private static String version() {
    String version = SwingMcpAgent.class.getPackage().getImplementationVersion();
    return version != null ? version : "dev";
  }
}
