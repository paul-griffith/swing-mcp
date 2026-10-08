package io.github.paul_griffith.swingmcp.agent;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tool surface: a thin aggregator over three per-family tool classes — {@link
 * IntrospectionTools} (read-only: {@code list_windows}, {@code get_component_tree}, {@code
 * find_components}, {@code get_items}, {@code screenshot}, {@code get_properties}, {@code
 * get_text}), {@link InteractionTools} (drives: {@code click}, {@code set_text}, {@code type_text},
 * {@code press_key}, {@code select}, {@code scroll_to}, {@code focus}, {@code close_window}, {@code
 * to_front}), and {@link DiagnosticTools} ({@code await_idle}, {@code wait_for}, {@code edt_stack})
 * — all over a shared {@link ToolSupport}. {@link #specifications()} returns the concatenated specs
 * in the client-facing listing order.
 *
 * <p>Results deliberately omit {@code null} and uninteresting {@code false} fields to keep the
 * payload small for an LLM consumer. Component/window handles ({@code id}) come straight from the
 * snapshots and are the addresses the interaction tools accept.
 *
 * <p>Actions that may open a modal dialog ({@code click}, {@code press_key}, and menu-item
 * activation via {@code select}) are dispatched modal-safely by {@code core}: they return promptly
 * with a {@code dispatched}/{@code edtSettled} status rather than blocking on the dialog's nested
 * event loop, cueing the caller to follow up with {@code await_idle} / {@code list_windows} /
 * {@code edt_stack}.
 */
final class SwingTools {

  private final ToolSupport support;
  private final IntrospectionTools introspection;
  private final InteractionTools interaction;
  private final DiagnosticTools diagnostic;

  SwingTools(JsonMapper json) {
    this.support = new ToolSupport(json);
    this.introspection = new IntrospectionTools(support);
    this.interaction = new InteractionTools(support);
    this.diagnostic = new DiagnosticTools(support);
  }

  /** The shared support (arg readers, schema builders, JSON render helpers) — exposed for tests. */
  ToolSupport support() {
    return support;
  }

  /** The tool specifications to register on the MCP server, in listing order. */
  List<McpServerFeatures.SyncToolSpecification> specifications() {
    return List.of(
        introspection.listWindows(),
        introspection.componentTree(),
        introspection.findComponents(),
        introspection.getItems(),
        introspection.screenshot(),
        interaction.click(),
        interaction.setText(),
        interaction.typeText(),
        interaction.pressKey(),
        interaction.select(),
        interaction.scrollTo(),
        interaction.focus(),
        interaction.closeWindow(),
        interaction.toFront(),
        introspection.getText(),
        introspection.getProperties(),
        diagnostic.awaitIdle(),
        diagnostic.waitFor(),
        diagnostic.edtStack());
  }
}
