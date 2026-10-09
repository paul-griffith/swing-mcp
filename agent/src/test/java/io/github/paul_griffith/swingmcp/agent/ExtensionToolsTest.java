package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paul_griffith.swingmcp.api.ComponentDescriber;
import io.github.paul_griffith.swingmcp.api.ToolDefinition;
import io.github.paul_griffith.swingmcp.api.ToolException;
import io.github.paul_griffith.swingmcp.api.ToolHandler;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Headless-safe unit tests for {@link ExtensionTools}: which extension tools are accepted, how
 * their results are encoded, and how their failures map onto the built-in structured errors.
 */
class ExtensionToolsTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final ExtensionTools tools = new ExtensionTools(new ToolSupport(json));

  private static ToolDefinition tool(String name, ToolHandler handler) {
    return new ToolDefinition(name, null, "A test tool.", null, handler);
  }

  private static Extensions.Extension extension(String name, ToolDefinition... definitions) {
    return new Extensions.Extension(
        name, "test", List.<ComponentDescriber>of(), List.of(definitions));
  }

  private McpSchema.CallToolResult call(ToolDefinition tool, Map<String, Object> arguments) {
    McpSchema.CallToolRequest.Builder request = McpSchema.CallToolRequest.builder(tool.name());
    if (arguments != null) {
      request.arguments(arguments);
    }
    return tools.call(tool, request.build());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> errorOf(McpSchema.CallToolResult result) {
    assertTrue(Boolean.TRUE.equals(result.isError()), "expected an error result");
    return json.readValue(Results.text(result), Map.class);
  }

  // ------------------------------------------------------------------ which tools are accepted

  @Test
  void acceptsWellFormedToolsAndRecordsTheirNames() {
    ExtensionTools.Built built =
        tools.build(
            List.of(
                extension("ext", tool("ext_one", (a, c) -> "1"), tool("ext.two-2", (a, c) -> "2"))),
            Set.of("click"));

    assertEquals(List.of(), built.problems());
    assertEquals(
        List.of("ext_one", "ext.two-2"),
        built.specifications().stream().map(s -> s.tool().name()).toList());
    assertEquals(Map.of("ext", List.of("ext_one", "ext.two-2")), built.toolNamesByExtension());
  }

  @Test
  void skipsAToolThatShadowsABuiltIn() {
    ExtensionTools.Built built =
        tools.build(List.of(extension("ext", tool("click", (a, c) -> "x"))), Set.of("click"));

    assertEquals(List.of(), built.specifications());
    assertTrue(built.problems().get(0).contains("built-in"), built.problems().get(0));
  }

  @Test
  void skipsALaterExtensionsToolWithATakenName() {
    ExtensionTools.Built built =
        tools.build(
            List.of(
                extension("first", tool("shared", (a, c) -> "1")),
                extension("second", tool("shared", (a, c) -> "2"))),
            Set.of());

    assertEquals(1, built.specifications().size());
    assertTrue(built.problems().get(0).contains("extension 'first'"), built.problems().get(0));
  }

  @Test
  void skipsMalformedNamesAndNonObjectSchemas() {
    ToolDefinition badName = tool("has space", (a, c) -> "x");
    ToolDefinition badSchema =
        new ToolDefinition("ext_array", null, "d", Map.of("type", "array"), (a, c) -> "x");

    ExtensionTools.Built built =
        tools.build(List.of(extension("ext", badName, badSchema)), Set.of());

    assertEquals(List.of(), built.specifications());
    assertEquals(2, built.problems().size());
  }

  @Test
  void aToolWithoutASchemaGetsAnEmptyObjectSchemaAndItsProvenance() {
    McpServerFeatures.SyncToolSpecification spec =
        tools.specification("ext", tool("ext_ping", (a, c) -> "pong"));

    assertEquals("object", spec.tool().inputSchema().get("type"));
    assertTrue(spec.tool().description().endsWith("(from the 'ext' extension)"));
  }

  // ------------------------------------------------------------------ results

  @Test
  void aMapResultIsEncodedAsJson() {
    McpSchema.CallToolResult result =
        call(tool("t", (a, c) -> Map.of("state", "online")), Map.of());
    assertFalse(Boolean.TRUE.equals(result.isError()));
    assertEquals("{\"state\":\"online\"}", Results.text(result));
  }

  @Test
  void aCharSequenceResultIsSentVerbatim() {
    McpSchema.CallToolResult result =
        call(tool("t", (a, c) -> new StringBuilder("plain text")), null);
    assertEquals("plain text", Results.text(result));
  }

  @Test
  void argumentsArriveAsGivenAndNeverNull() {
    AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
    ToolDefinition probe =
        tool(
            "t",
            (a, c) -> {
              seen.set(a);
              return "ok";
            });

    call(probe, null);
    assertEquals(Map.of(), seen.get());

    call(probe, Map.of("component_id", "x"));
    assertEquals(Map.of("component_id", "x"), seen.get());
  }

  // ------------------------------------------------------------------ errors

  @Test
  void aToolExceptionKeepsItsCodeAndHint() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) -> {
                      throw new ToolException("not_a_gauge", "x is not a gauge", "find a gauge");
                    }),
                Map.of()));
    assertEquals("not_a_gauge", error.get("error"));
    assertEquals("x is not a gauge", error.get("message"));
    assertEquals("find a gauge", error.get("hint"));
  }

  @Test
  void anIllegalArgumentIsInvalidArgument() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) -> {
                      throw new IllegalArgumentException("component_id is required");
                    }),
                Map.of()));
    assertEquals("invalid_argument", error.get("error"));
  }

  @Test
  void aCheckedExceptionIsInternalError() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) -> {
                      throw new java.io.IOException("disk full");
                    }),
                Map.of()));
    assertEquals("internal_error", error.get("error"));
    assertEquals("disk full", error.get("message"));
  }

  @Test
  void aLinkageErrorDoesNotEscape() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) -> {
                      throw new NoClassDefFoundError("com/example/Gone");
                    }),
                Map.of()));
    assertEquals("internal_error", error.get("error"));
  }

  @Test
  void anUnknownComponentIdIsComponentNotFound() {
    Map<String, Object> error =
        errorOf(
            call(tool("t", (a, c) -> c.withComponent("no-such-component", comp -> "x")), Map.of()));
    assertEquals("component_not_found", error.get("error"));
    assertTrue(error.containsKey("hint"), "the built-in recovery hint applies: " + error);
  }

  @Test
  void aCheckedExceptionOnTheEdtKeepsItsMessage() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) ->
                        c.onEdt(
                            () -> {
                              throw new java.io.IOException("config unreadable");
                            })),
                Map.of()));
    assertEquals("internal_error", error.get("error"));
    assertEquals("config unreadable", error.get("message"));
  }

  @Test
  void aToolExceptionThrownOnTheEdtKeepsItsCode() {
    Map<String, Object> error =
        errorOf(
            call(
                tool(
                    "t",
                    (a, c) ->
                        c.onEdt(
                            () -> {
                              throw new ToolException("busy", "the widget is busy");
                            })),
                Map.of()));
    assertEquals("busy", error.get("error"));
  }

  // ------------------------------------------------------------------ instructions

  @Test
  void instructionsNameLoadedExtensionsAndWhatTheyAdd() {
    String none = McpHttpServer.instructions(List.of());
    assertFalse(none.contains("Extensions loaded"));

    String some =
        McpHttpServer.instructions(
            List.of(
                new McpHttpServer.ExtensionSummary("demo", List.of("demo_status"), 1),
                new McpHttpServer.ExtensionSummary("quiet", List.of(), 0)));
    assertTrue(
        some.contains(
            "Extensions loaded: `demo` (tools: demo_status; describes custom components),"
                + " `quiet`."),
        some);
  }

  /** Reads a result's first text content. */
  private static final class Results {
    static String text(McpSchema.CallToolResult result) {
      for (McpSchema.Content content : result.content()) {
        if (content instanceof McpSchema.TextContent text) {
          return text.text();
        }
      }
      return null;
    }
  }
}
