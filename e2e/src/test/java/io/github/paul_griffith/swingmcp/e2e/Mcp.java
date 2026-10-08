package io.github.paul_griffith.swingmcp.e2e;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thin, test-friendly wrapper around the MCP Java SDK sync client: connects to the agent over the
 * Streamable HTTP transport, initializes the session, and exposes terse helpers for calling tools
 * and reading their JSON / image results. {@link AutoCloseable} so tests close the client (and its
 * connection) in {@code @AfterAll} / try-with-resources.
 */
final class Mcp implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final McpSyncClient client;

  private Mcp(McpSyncClient client) {
    this.client = client;
  }

  /** Connects and initializes a client against {@code 127.0.0.1:port}, no auth. */
  static Mcp connect(int port) {
    return connect(port, null);
  }

  /**
   * Builds a transport for {@code 127.0.0.1:port}, optionally adding an {@code Authorization:
   * Bearer token} header to every request, then connects and initializes the session.
   */
  static Mcp connect(int port, String token) {
    Mcp mcp = new Mcp(build(port, token));
    mcp.client.initialize();
    return mcp;
  }

  /**
   * Builds and returns an <b>uninitialized</b> client (call {@link McpSyncClient#initialize()}
   * yourself). Used by the security test, which asserts that initialize fails without a token.
   */
  static McpSyncClient buildClient(int port, String token) {
    return build(port, token);
  }

  private static McpSyncClient build(int port, String token) {
    HttpClientStreamableHttpTransport.Builder transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp");
    if (token != null) {
      transport.httpRequestCustomizer(
          (builder, method, uri, body, context) ->
              builder.header("Authorization", "Bearer " + token));
    }
    return McpClient.sync(transport.build())
        .clientInfo(McpSchema.Implementation.builder("swing-mcp-e2e", "test").build())
        .requestTimeout(Duration.ofSeconds(30))
        .initializationTimeout(Duration.ofSeconds(30))
        .build();
  }

  // ------------------------------------------------------------------ tool listing / info

  /** The server's advertised implementation version (from serverInfo). */
  String serverVersion() {
    return client.getServerInfo().version();
  }

  /** The server's advertised name (from serverInfo). */
  String serverName() {
    return client.getServerInfo().name();
  }

  /** Every registered tool's name, in listing order. */
  List<String> toolNames() {
    return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
  }

  // ------------------------------------------------------------------ tool calls

  /** Calls a tool and returns the raw result (for image/error inspection). */
  McpSchema.CallToolResult callRaw(String tool, Map<String, Object> args) {
    return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());
  }

  /**
   * Calls a tool and parses its first text-content payload as JSON. Fails the test on a tool error.
   */
  JsonNode call(String tool, Map<String, Object> args) {
    McpSchema.CallToolResult result = callRaw(tool, args);
    if (Boolean.TRUE.equals(result.isError())) {
      throw new AssertionError("tool '" + tool + "' returned an error: " + firstText(result));
    }
    return JSON.readTree(firstText(result));
  }

  // ------------------------------------------------------------------ result readers

  /** The first {@link McpSchema.TextContent} in the result, or {@code null}. */
  static String firstText(McpSchema.CallToolResult result) {
    for (McpSchema.Content content : result.content()) {
      if (content instanceof McpSchema.TextContent text) {
        return text.text();
      }
    }
    return null;
  }

  /** The first {@link McpSchema.ImageContent} in the result, or {@code null}. */
  static McpSchema.ImageContent firstImage(McpSchema.CallToolResult result) {
    for (McpSchema.Content content : result.content()) {
      if (content instanceof McpSchema.ImageContent image) {
        return image;
      }
    }
    return null;
  }

  /** Parses an arbitrary JSON string (helper for tests). */
  static JsonNode parse(String json) {
    return JSON.readTree(json);
  }

  @Override
  public void close() {
    client.closeGracefully();
  }
}
