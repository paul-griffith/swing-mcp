package io.github.paul_griffith.swingmcp.agent;

import io.github.paul_griffith.swingmcp.api.ToolContext;
import io.github.paul_griffith.swingmcp.api.ToolDefinition;
import io.github.paul_griffith.swingmcp.api.ToolException;
import io.github.paul_griffith.swingmcp.core.ComponentAddress;
import io.github.paul_griffith.swingmcp.core.EdtOps;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the {@link ToolDefinition}s extensions registered into MCP tool specifications, behind the
 * same error mapping as the built-in tools ({@link ToolSupport#guarded}), and hands their handlers
 * a {@link ToolContext}.
 */
final class ExtensionTools {

  /** Allowed tool names: what {@link ToolDefinition} documents. */
  private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

  private final ToolSupport support;
  private final ToolContext context = new Context();

  ExtensionTools(ToolSupport support) {
    this.support = support;
  }

  /**
   * The outcome of turning the extensions' tools into specifications.
   *
   * @param specifications the accepted tools, in extension then registration order
   * @param toolNamesByExtension each extension's accepted tool names (for logs and instructions)
   * @param problems one line per tool that was skipped, and why
   */
  record Built(
      List<McpServerFeatures.SyncToolSpecification> specifications,
      Map<String, List<String>> toolNamesByExtension,
      List<String> problems) {}

  /**
   * Builds specifications for every extension tool whose name is well-formed and not taken (by a
   * built-in tool in {@code reservedNames} or an earlier extension tool), and whose schema is an
   * object schema.
   */
  Built build(List<Extensions.Extension> extensions, Set<String> reservedNames) {
    List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
    Map<String, List<String>> names = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    Map<String, String> taken = new LinkedHashMap<>();
    for (String reserved : reservedNames) {
      taken.put(reserved, "a built-in tool");
    }
    for (Extensions.Extension extension : extensions) {
      List<String> accepted = new ArrayList<>();
      for (ToolDefinition tool : extension.tools()) {
        String problem = validate(tool, taken);
        if (problem != null) {
          problems.add("extension '" + extension.name() + "': tool skipped: " + problem);
          continue;
        }
        specs.add(specification(extension.name(), tool));
        taken.put(tool.name(), "extension '" + extension.name() + "'");
        accepted.add(tool.name());
      }
      names.put(extension.name(), List.copyOf(accepted));
    }
    return new Built(List.copyOf(specs), Collections.unmodifiableMap(names), List.copyOf(problems));
  }

  private static String validate(ToolDefinition tool, Map<String, String> taken) {
    String name = tool.name();
    if (!TOOL_NAME.matcher(name).matches()) {
      return "'" + name + "' is not a valid tool name (letters, digits, _ . - ; 1-128 characters)";
    }
    String owner = taken.get(name);
    if (owner != null) {
      return "'" + name + "' is already taken by " + owner;
    }
    Map<String, Object> schema = tool.inputSchema();
    if (schema != null && !"object".equals(schema.get("type"))) {
      return "'" + name + "' has an input schema whose type is not \"object\"";
    }
    return null;
  }

  /** One extension tool as an MCP specification. */
  McpServerFeatures.SyncToolSpecification specification(String extension, ToolDefinition tool) {
    Map<String, Object> schema = tool.inputSchema();
    if (schema == null) {
      schema = support.objectSchema(new LinkedHashMap<>());
    }
    McpSchema.Tool.Builder builder =
        McpSchema.Tool.builder(tool.name(), schema)
            .description(tool.description() + " (from the '" + extension + "' extension)");
    if (tool.title() != null && !tool.title().isBlank()) {
      builder.title(tool.title());
    }
    return ToolSupport.spec(builder.build(), (exchange, request) -> call(tool, request));
  }

  /** Runs one call: the handler's result as text/JSON, or its failure as a structured error. */
  McpSchema.CallToolResult call(ToolDefinition tool, McpSchema.CallToolRequest request) {
    Map<String, Object> arguments =
        request.arguments() != null ? Collections.unmodifiableMap(request.arguments()) : Map.of();
    return support.guarded(
        () -> {
          Object result;
          try {
            result = tool.handler().handle(arguments, context);
          } catch (ToolException e) {
            return support.errorResult(e.code(), messageOf(e), e.hint());
          } catch (RuntimeException e) {
            throw e; // guarded maps component_not_found, edt_timeout, invalid_argument, ...
          } catch (Exception e) {
            return support.errorResult("internal_error", messageOf(e), null);
          } catch (LinkageError | AssertionError e) {
            // A NoClassDefFoundError from an extension's own class loading, or a failed assert,
            // must not escape into the transport; a VirtualMachineError still does.
            return support.errorResult("internal_error", messageOf(e), null);
          }
          if (result instanceof CharSequence text) {
            return McpSchema.CallToolResult.builder().addTextContent(text.toString()).build();
          }
          return support.jsonText(result);
        });
  }

  private static String messageOf(Throwable t) {
    return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
  }

  /** The {@link ToolContext} every extension tool call gets: core's EDT and addressing helpers. */
  private static final class Context implements ToolContext {

    @Override
    public Duration defaultTimeout() {
      return EdtOps.DEFAULT_TIMEOUT;
    }

    @Override
    public <T> T withComponent(String id, Function<Component, T> action) {
      return withComponent(id, EdtOps.DEFAULT_TIMEOUT, action);
    }

    @Override
    public <T> T withComponent(String id, Duration timeout, Function<Component, T> action) {
      Objects.requireNonNull(id, "id");
      return ComponentAddress.withComponent(id, timeout, action);
    }

    @Override
    public <T> T onEdt(Callable<T> task) throws Exception {
      return onEdt(task, EdtOps.DEFAULT_TIMEOUT);
    }

    @Override
    public <T> T onEdt(Callable<T> task, Duration timeout) throws Exception {
      // invokeAndWait (not EdtOps.call) so the task's own checked exception reaches the handler
      // unwrapped, and its message reaches the client.
      return EdtOps.invokeAndWait(Objects.requireNonNull(task, "task"), timeout);
    }

    @Override
    public String idOf(Component component) {
      return ComponentAddress.idOf(component);
    }
  }
}
