# swing-mcp

An in-process [MCP](https://modelcontextprotocol.io) server for Java Swing applications. It gives
coding agents (Claude Code, Copilot, and other MCP clients) a real feedback loop for Swing
development — list windows, snapshot the component tree, capture screenshots, and drive the UI
(clicks, typing, selection) — without a human launching the app and taking screenshots by hand. The
server ships as a Java agent that loads into the target application's own JVM (via `-javaagent:` at
launch, or dynamic attach to a running JVM), stands up an embedded HTTP endpoint bound to
`127.0.0.1`, and exposes its tools over the MCP Streamable HTTP transport. All UI access is
funnelled through the Event Dispatch Thread with timeouts, so a wedged EDT surfaces as a diagnosable
`edt_stack` result instead of a hung tool call.

## Quick start

You only need the shaded agent jar and a JDK 17+ — no clone or build required.

### Get the agent jar

**Download (recommended).** Grab `swing-mcp-agent.jar` from the
[latest release](https://github.com/paul-griffith/swing-mcp/releases/latest) — it is fully
self-contained (MCP server, HTTP endpoint, and all dependencies are shaded in), so there is nothing
else to install. Each release also carries a `swing-mcp-agent.jar.sha256` checksum.

```bash
curl -LO https://github.com/paul-griffith/swing-mcp/releases/latest/download/swing-mcp-agent.jar
# or, with the GitHub CLI:
gh release download --repo paul-griffith/swing-mcp --pattern swing-mcp-agent.jar
```

**Or build it yourself** (always produces the same unversioned path,
`agent/build/libs/swing-mcp-agent.jar`, so you can hard-reference it from MCP client
configs and scripts and keep rebuilding in place; the release version is recorded in the
jar manifest's `Implementation-Version`):

```bash
./gradlew :agent:shadowJar
```

Then pick one of the two ways to load it into your Swing app:

### Option A — launch with the agent (`premain`)

Add the jar as a `-javaagent` when you start your Swing application:

```bash
java -javaagent:/path/to/swing-mcp-agent.jar=port=8765 -jar yourapp.jar
```

The agent starts on a background **daemon** thread, so it never delays or crashes the host app; any
startup failure is logged to stderr with a `[swing-mcp]` prefix and swallowed. On success it logs:

```
[swing-mcp] listening on http://127.0.0.1:8765/mcp
```

**Agent arguments** (`=key=value,key=value`, all optional):

| Arg          | Default     | Meaning |
|--------------|-------------|---------|
| `port`       | `8765`      | TCP port to bind. `0` picks a free ephemeral port (read the actual port from the startup line). |
| `token`      | *(none)*    | When set, requests must send `Authorization: Bearer <token>`. |
| `extensions` | *(none)*    | Extension jars to load: jar files and/or directories of jars, separated by `:` (`;` on Windows); see [Extensions](#extensions). |

Example: `-javaagent:swing-mcp-agent.jar=port=9000,token=s3cret`.

The server only ever listens on `127.0.0.1`. Read the [Security model](#security-model) before
loading the agent into an application that handles sensitive data.

### Option B — attach to an already-running JVM (dynamic attach)

The jar's `Main-Class` is a dynamic-attach launcher. With no arguments it lists attachable JVMs;
with a PID it injects the agent into that JVM:

```bash
# List attachable JVMs on this machine (PID + display name):
java -jar swing-mcp-agent.jar

# Attach to a target by PID, with the usual (optional) agent args:
java -jar swing-mcp-agent.jar 12345
java -jar swing-mcp-agent.jar 12345 port=0,token=secret
```

The agent then logs its `[swing-mcp] listening on http://…/mcp` line into the **target's** output.
Dynamic attach uses the JDK Attach API (`com.sun.tools.attach`, module `jdk.attach`), so **the
launcher must run on a JDK** (a plain JRE lacks `jdk.attach`). The `-javaagent` mode above has no
such requirement. Attaching to a JVM that already has the agent is a no-op (it logs and returns).

Common messages: a non-numeric or unknown PID prints a usage hint; if the environment refuses
attach (e.g. a locked-down CI sandbox), the launcher says so and exits with a distinct code.

### Connect a coding agent

From Claude Code (Streamable HTTP transport):

```bash
claude mcp add --transport http swing http://localhost:8765/mcp
```

Or poke at it directly with the MCP Inspector CLI:

```bash
npx @modelcontextprotocol/inspector --cli http://localhost:8765/mcp --method tools/list
npx @modelcontextprotocol/inspector --cli http://localhost:8765/mcp \
    --method tools/call --tool-name list_windows
```

## Tools

Component/window handles (`id`) come from `list_windows` / `get_component_tree` / `find_components`;
an `id` is the component's `getName()` when set and unique, otherwise a structural path (e.g.
`w0/JRootPane[0]/…/JButton[0]`). The `w<n>` anchor is a **stable, process-lifetime window handle** —
assigned once and never reused or renumbered — so a structural id does not rot as other windows
open and close (unlike an index into the window list). Naming your components makes for stable,
readable ids. Each entry also carries an `idKind` so you can tell how durable a handle is: `name` (a
deliberate `getName()` — safe to hard-code in a test), `auto` (an AWT-invented default like `frame2`
that changes between runs — **do not** hard-code it), or absent (a structural path). The raw `name`
is included whenever it differs from the id. Real apps often name a *container* rather than its
inner input (a filter panel wrapping a field); `set_text` / `type_text` auto-descend to the unique
text field within such a handle and report the field's `resolvedId`.

Every acting/reading tool also accepts an optional `timeout_ms` to bound its EDT wait for that one
call (defaults are unchanged). On failure a tool returns a **structured error** — `{"error":
"<code>", "message": …, "hint"?: …}` with `isError` set — where `<code>` is one of
`component_not_found`, `ambiguous_component`, `edt_timeout`, `invalid_argument`, `no_target`,
`io_error`, or `internal_error`, and `hint` (when present) is the recovery cue (e.g. "call
find_components to relocate the target"). Branch on `error` rather than parsing the message.

| Tool | Arguments | Returns |
|------|-----------|---------|
| `list_windows` | optional `include_hidden` (default false: only **showing** windows are returned), `title` (case-insensitive substring filter) | `{windows: [...], hiddenCount?}` — top-level windows sorted **focused-first**, each with `id`, `idKind`/`name` (handle durability — see above), `kind`, `title`, `bounds` (logical px), and state flags (`visible`/`showing`, plus `focused`/`active`/`modal`/`iconified`/`maximized` when true). `hiddenCount` (present only when hidden windows were suppressed) reports how many — pass `include_hidden: true` to see them. |
| `get_component_tree` | `window_id` / `component_id` (default: focused/first-visible window), `max_depth` (default 12; 0 = root only) | JSON component tree: `id`, `idKind`/`name` (handle durability — see above), `class`, best-effort `text`, `bounds` (logical px), `selection`, `childCount`, `children`. A node cut off by `max_depth` is marked `childrenTruncated` while still reporting its true `childCount`. |
| `find_components` | at least one of `name`, `class`, `text` (case-insensitive substrings); optional `interactable` (input widgets only), `window_id` / `component_id` scope (default: all windows), `limit` (default 50) | `{count, scanned?, hint?, truncated?, matches[]}` — a flat list of matches (each `id`, `idKind`/`name`, `class`, `text`, `bounds`, `selection`, `childCount`). On zero matches, `scanned` is the number of components searched and `hint` carries diagnostics: the scope covered (an empty subtree may be lazily built), plus near-miss names/classes seen during the scan. Locate a target in a large UI without walking `get_component_tree`. |
| `get_items` | `component_id` (a JTable/JList/JComboBox/JTree), `limit` (default 100) | `{kind, columns?, rows, count, truncated?, selectedIndex?}` — the component's **contents**: a table's columns + cell text, a list/combo's options, or a tree's nodes (depth-indented). Text is **renderer-aware** (what the user sees, not a model object's `toString()`). Read values before `select value=…`, or read a result table without a screenshot. |
| `screenshot` | `component_id` / `window_id` (default: focused window), `scale` (optional), `save_path` (optional absolute path), `return_image` (with `save_path`; default false) | A PNG image plus a text note with the **logical size**, **pixel size**, and **effective scale** — multiply logical (tree) bounds by the effective scale to map them onto screenshot pixels. Painted off-screen (works for occluded/background windows, no OS screen-recording permission). `scale` is the target **image px per logical px** (`2` = twice the component's tree bounds on any display, HiDPI or not); omit it for native resolution. Inline captures are downscaled to a longest edge of ≤ 1200 px, explicit `scale` included. With `save_path`, the **full-resolution** PNG is written to disk with no cap (parent dirs created, existing file overwritten; relative paths are rejected as `invalid_argument`, write failures surface as `io_error`) and only the text metadata (`saved` path + sizes/scale) is returned — add `return_image: true` for both. |
| `get_properties` | `component_id`, `properties` (optional name filter, or `["@layout"]`) | JSON object of bean-style properties (public `getX()`/`isX()`) rendered as strings; getters that throw are skipped, collections element-capped. Values are **structured, not `toString()`**: `Color` → `#rrggbb` (`/aa` when translucent), `Insets` → `{top:…, left:…, bottom:…, right:…}`, `Dimension` → `WxH`, `Point` → `(x,y)`, `Rectangle` → `(x,y WxH)`, a `Border` → `ClassName{insets…}`, a **component-valued** property (`parent`, `labelFor`) → `ClassName#<id>` you can pass straight to another tool, and a list/table/tree model → `ClassName(size=n)` / `(rows=r, cols=c)`. |
| `get_properties` `["@layout"]` | `component_id` | The layout preset: `bounds`, `insets`, `border`, `minimumSize`/`preferredSize`/`maximumSize`, `parent`, `parentLayout`, this component's `constraints` in it (`BorderLayout`, `GridBagLayout`, and MigLayout — read reflectively, so `core` keeps no MigLayout dependency), and **`edgeDistances`** = the gap on each side to the parent's border-adjusted inner bounds. `edgeDistances` answers "is this exactly 8px from the edge" directly, replacing high-scale screenshots and manual bounds arithmetic. |
| `get_text` | `component_id`, `offset` (default 0), `max_chars` (default 4000, cap 32000), `selected_only` | `{id, text, length, offset, truncated?, source, resolvedId?}`. Reads a component's text **in full, paged** — the escape hatch from the 200-char cap that every `text` field in the tree/find/items output carries. A text component is read from its document (or its selection); anything else falls back to the same best-effort display text the tree shows, uncapped. `length` is the *full* document length, so page with `offset` until `truncated` stops coming back. Auto-descends into a named composite like `set_text`. Use instead of `scroll_to` + screenshot tiling. |
| `click` | `component_id`, `button` (`left`/`middle`/`right`, default left), `count` (default 1; 2 = double-click), optional `x`/`y` (logical px within the component; default center) **or** `row`+`column` (a JTable cell) | `{dispatched, edtSettled, opened?, closed?, deltaUnknown?, note?, mouseListeners, warning?}`. `doClick()` for buttons/menu items; otherwise synthesized `MouseEvent`s (with rollover enter/move so hover-armed overlays trigger) at `x`/`y` or the center. For a **JTable**, pass `row`/`column` (view coordinates, as in `get_items`) instead of `x`/`y` — the cell rectangle's centre is computed for you, so it stays correct with varying row heights and a scrolled table; pair with `count: 2` to open the cell editor. **Reports the UI delta**: `opened[]` lists windows/popups that appeared (`{id, kind, title?, bounds}`) and `closed[]` those that went away (omitted when empty); `deltaUnknown: true` when the EDT stayed busy past the grace period. `warning` when the click will likely no-op — the target is **disabled**, **not showing**, or is not a button and has no mouse listeners. **Modal-safe**: returns promptly even when it opens a modal dialog. |
| `set_text` | `component_id`, `text`, `mode` (`replace` default / `append`) | `{ok, length, text, mode?, resolvedId?}`. Fast one-shot `setText`; fires document events but skips per-keystroke handling. `mode: "append"` adds to the end of the document instead (caret to end + `replaceSelection`), replacing the click + `press_key end` + `type_text` dance — it is **rejected on a read-only target**, where it would silently do nothing. Auto-descends to the inner field when `component_id` is a container of one text field (reporting `resolvedId`); errors when the target is neither a text component nor the container of one. |
| `type_text` | `component_id`, `text` | `{ok, typed, resolvedId?}`. Types character by character via real `KeyEvent`s so `DocumentListener`s, key bindings, and input validation all fire — use over `set_text` when those matter. Same auto-descend/reject rules as `set_text` (no more silent no-ops on a non-text target). |
| `press_key` | `key` (a name like `escape`/`enter`/`tab`/`up`/`f5`, a single character, or a chord like `ctrl+space`), optional `component_id` (default: focus owner, else focused window), `count` (default 1) | `{dispatched, edtSettled, opened?, closed?, deltaUnknown?, note?, key}`. Delivers a named key or chord as real `KeyEvent`s, driving key listeners and `InputMap` bindings — unlike `type_text` it needs no text target (dismiss a popup, submit a dialog, navigate a tree, fire an accelerator). Reports the same `opened[]`/`closed[]` UI delta as `click`. **Modal-safe**. |
| `select` | `component_id` + exactly one of `index`, `value`, `path` (string array), or `row`+`column` | Selects on a combo/list/tabbed-pane/table row/spinner/tree, or activates a menu path on a `JMenuBar`/`JMenu`/`JPopupMenu` (so after a right-click opens a context menu you can activate its items by path). Value/path matching is **renderer-aware**, and a path that doesn't match reports the parent's **actual children** in the error (the labels come from renderers and often differ from the model values you'd guess). `row`+`column` selects a single **JTable cell** and scrolls it visible, where `index` selects a whole row. Synchronous selections return `{ok, description}`; menu activation is **modal-safe** (`{dispatched, edtSettled, opened?, closed?, deltaUnknown?}` — the same UI delta as `click`). |
| `scroll_to` | `component_id` | `{ok}`. Scrolls the component's enclosing scroll pane so it is visible (`scrollRectToVisible`) — e.g. before screenshotting an off-screen component. |
| `focus` | `component_id` | `{ok, accepted}`. Requests keyboard focus (`requestFocusInWindow`); `accepted` is best-effort (focus transfer is async). |
| `close_window` | `component_id` (a window id, or any component within it) | `{dispatched, edtSettled}`. Posts `WINDOW_CLOSING` — the OS close-button event — so the app's own close handling runs; dismisses dialogs/frames `press_key escape` can't. **Modal-safe** (a close may open a "save changes?" dialog). |
| `to_front` | `component_id` (a window id, or any component within it) | `{ok}`. Brings the enclosing window to the front (`Window.toFront`). |
| `await_idle` | `timeout_ms` (default 5000); optional `window_title`, or `component_id` + (`text` substring, or `state` `visible`/`showing`/`enabled`/`focused`) | `{met, elapsedMs, …}`. No condition = flush the EDT; a condition = poll until met or timeout. `text` waits for the component's display text to contain a substring (await an async status update). Use for EDT-settle waits and state checks on a **known** component id; use `wait_for` to await a component that may not exist yet. |
| `wait_for` | `condition` (`appears`/`disappears`/`enabled`/`text_contains`, required) + the `find_components` filters (`name`, `class`, `text`, `interactable`; at least one) and `window_id`/`component_id` scope; `expect_text` (for `text_contains`), `timeout_ms` (default 10000), `poll_ms` (default 250, min 100) | Polls until the matching components satisfy the condition. The scope is **re-resolved every poll** (an unresolvable scope counts as zero matches, so you can wait for a component in a dialog that hasn't opened yet). First satisfying match wins (`disappears` = zero matches overall). Success: `{met: true, elapsedMs, match?}`; timeout: `{met: false, elapsedMs, lastCount, timedOut}` — **not** an error, branch on `met`. The fallback for slow/async opens the `click` delta missed. |
| `edt_stack` | `threshold_ms` (default 200) | `{verdict, responsive, latencyMs, threadName, stack}`. Measures EDT responsiveness and dumps its stack — read from *outside* the EDT, so it works even when the EDT is fully blocked. |

### Modal-dialog workflow

Actions that can open a **modal** dialog (a `click`, `press_key`, or menu-item activation via
`select`) never block the tool call on the dialog's nested event loop. They dispatch the action
and return immediately with `dispatched: true`, an `edtSettled` flag, and the **UI delta** they
caused: `opened[]` (windows/popups that appeared, with ready-to-use ids) and `closed[]` (those
that went away). The workflow is:

1. `click` the opener (e.g. a "Details…" button) → returns promptly with the new dialog in
   `opened[]`.
2. `get_component_tree` / `screenshot` the dialog (by its `opened[]` id) and interact with it.
3. `click` the dialog's close/OK button to dismiss it → that result's `closed[]` confirms it is
   gone.

For a slow/async open (nothing in `opened[]` yet), or when the result reports
`deltaUnknown: true`, fall back to `wait_for` (or `await_idle` with `window_title`). If a `click`
reports `edtSettled: false`, the EDT is still busy or blocked — reach for `edt_stack` to see
exactly where it is stuck.

## Security model

**Read this before loading the agent into an application.** swing-mcp exists to give an automated
client full control of a running Swing UI, and it does exactly that. By loading it, you accept that
**any client that can reach the endpoint can**:

- read everything the UI holds — every window, component, and model, **including the plain-text
  contents of password fields** (`JPasswordField` is not redacted);
- drive the UI as if it were the user — click, type, select, close windows — which means doing
  anything the application lets its user do (saving, deleting, sending, …);
- call any public no-argument getter on any component (`get_properties`), and write screenshots to
  any path the application process can write to (`screenshot` with `save_path`).

Whatever the client reads is sent onward to wherever the client sends it — for a coding agent,
usually a hosted model provider. Don't attach the agent to an application holding data you would not
hand to that client.

What limits who that client can be:

- The server binds **`127.0.0.1` only**. This is not configurable; the endpoint is never reachable
  from another machine.
- Without a `token`, **any process on this machine, running as any user**, can connect. On a
  single-user workstation that is usually fine; on a shared machine, set `token=…` (the token is
  then visible to local users who can read the target's command line, so treat it as a speed bump,
  not a secret store).
- A request filter (per the MCP spec's DNS-rebinding guidance) runs before anything reaches the MCP
  server, so a web page in your browser cannot reach the endpoint:
  - the `Host` header must name a loopback host on the server's port (else `403`). "Loopback" means
    `localhost`, the IPv6 loopback, or an actual `127.0.0.0/8` **IP literal** — a resolvable
    hostname like `127.evil.com` is **not** accepted, and no name resolution is ever performed;
  - an `Origin` header, if present, must be loopback by the same rule (a page at
    `http://evil.example` gets `403`); non-browser clients that omit `Origin` are allowed;
  - when a `token` is configured, a missing/non-Bearer `Authorization` header is `401` and a wrong
    token is `403` (compared in constant time).

## Extensions

The built-in tools only know what standard Swing components expose. An **extension** teaches
swing-mcp about an application's own components and adds tools of its own, without a fork:

- a **describer** gives a component display text, so a custom-painted gauge, a docking frame whose
  title lives in a field, or a canvas widget shows up meaningfully in `get_component_tree`, and
  `find_components`, `wait_for`, and `get_text` can match and read it;
- a **tool** is an MCP tool listed next to the built-in ones. It takes the same component ids and
  returns the same `{error, message, hint?}` errors.

An extension is a class implementing `io.github.paul_griffith.swingmcp.api.SwingMcpExtension`,
registered in `META-INF/services/io.github.paul_griffith.swingmcp.api.SwingMcpExtension`:

```java
public final class GaugeExtension implements SwingMcpExtension {
  @Override
  public String name() {
    return "gauges";
  }

  @Override
  public void register(ExtensionContext context) {
    // Called on the EDT for every component the tools look at; null means "not mine".
    context.addDescriber(c -> isGauge(c) ? "Gauge: " + reading(c) : null);

    Map<String, Object> schema =
        Map.of(
            "type", "object",
            "properties", Map.of("component_id", Map.of("type", "string")),
            "required", List.of("component_id"));
    context.addTool(
        new ToolDefinition(
            "gauges_read",
            "Read a gauge",
            "Returns {id, reading} for a gauge component.",
            schema,
            (args, ctx) -> {
              String id = (String) args.get("component_id");
              Double reading = ctx.withComponent(id, c -> isGauge(c) ? reading(c) : null);
              if (reading == null) {
                throw new ToolException("not_a_gauge", id + " is not a gauge", "find one first");
              }
              return Map.of("id", id, "reading", reading);
            }));
  }
}
```

Load it either way:

- **`extensions=`** — `-javaagent:swing-mcp-agent.jar=extensions=/opt/swing-mcp/gauges.jar`. Each
  entry is a jar, or a directory whose `*.jar` files are loaded together (an extension plus the jars
  it depends on). Each entry gets its own class loader, so extensions do not see each other.
- **the classpath** — an extension on the classpath of the JVM the agent runs in is found
  automatically.

Things to know:

- The API (`io.github.paul_griffith.swingmcp.api`, the `api` module) depends only on the JDK and is
  never relocated in the shaded jar. Compile against `swing-mcp-agent.jar` (or the `api` module) as
  a compile-only dependency.
- Application classes usually live in a different class loader than the extension. Reach them
  reflectively through the component you are handed, as the sample does with the demo's
  `StatusIndicator` (`demo-extension/`).
- Describers run on the EDT, before the built-in rules, for every component: answer quickly and
  return `null` for anything you do not recognize. Tool handlers run on a request thread; touch
  Swing only through `ToolContext.withComponent` / `onEdt`, which apply the usual EDT timeout.
- Prefix tool names with something identifying the extension. A tool whose name is taken (by a
  built-in or another extension) or malformed, or whose schema is not an object schema, is skipped.
- A broken extension never stops the agent: a missing path, a provider that cannot be created, a
  duplicate extension name, or a `register` that throws is logged with the `[swing-mcp]` prefix and
  skipped; a describer that throws counts as "not mine" (its first failure is logged). The startup
  log lists what loaded, and the server instructions tell clients which extension tools exist.
- An extension runs inside the host application with the same power as the agent itself: only
  load extensions you trust, the same as the agent jar.

## Development

| Module        | What it is |
|---------------|------------|
| `api`         | The [extension](#extensions) API: the interfaces an extension implements. JDK-only, and never relocated in the shaded jar. |
| `core`        | Swing introspection & driving logic (window list, component tree, `paint()` screenshots, EDT-safe interactions/diagnostics, component addressing). No MCP dependency. |
| `agent`       | The `-javaagent` bootstrap, dynamic-attach CLI, MCP server, and embedded Jetty, built as a shaded jar with every third-party package relocated. The only published artifact. |
| `demo-java`, `demo-kotlin` | Small runnable Swing apps used as test beds (the Kotlin one shows Kotlin-authored UIs need nothing special). |
| `demo-extension` | A sample extension for the demo app (a describer for its custom `StatusIndicator` and a `demo_status` tool), also the fixture the e2e suite loads. |
| `e2e`         | End-to-end tests that fork a demo app with the shaded agent and drive every tool over MCP. |

Building needs a JDK 17+ on `PATH`; the Gradle wrapper and the
[Foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) handle the rest. Bytecode
always targets Java 17.

```bash
./gradlew build                              # compile, test (incl. e2e), assemble the agent jar
./gradlew :agent:shadowJar                   # just the agent jar → agent/build/libs/swing-mcp-agent.jar
./gradlew :demo-java:run                     # run a demo app
./gradlew build -Pswingmcp.javaVersion=25    # build and test on another JDK (CI runs 17 and 25)
./gradlew build -Pswingmcp.headless=true     # skip the tests that need a display
```

The `core` integration tests and the `e2e` suite show real windows, so they need a display; they
skip themselves when headless. CI runs them under `xvfb-run` on Linux.

### Releasing

Bump `version` in `build.gradle.kts`, commit, then push a matching tag (`git tag v1.2.3 && git push
origin v1.2.3`). The release workflow checks the tag matches the build version, builds and tests,
and publishes `swing-mcp-agent.jar` plus its `.sha256` to a GitHub release.

## Troubleshooting

- **A tool hangs or the UI seems frozen** — the EDT is likely blocked. Call `edt_stack`: it reads
  the AWT-EventQueue thread's stack from outside the EDT, so it works even when the EDT is wedged,
  and returns a `responsive`/`blocked` verdict plus the stack. A `click`/`select` that reports
  `edtSettled: false` is the usual cue.
- **A dialog you expected isn't found** — after a modal-safe `click`, the dialog opens
  asynchronously. Use `await_idle` with `window_title` (or poll `list_windows`) before addressing
  it; then `get_component_tree` with the dialog's `component_id`.
- **Screenshot coordinates don't line up with tree bounds** — tree `bounds` are logical
  (device-independent) pixels; screenshots are device pixels. Multiply logical coordinates by the
  **effective scale** the `screenshot` tool reports (it differs from 1 on HiDPI/Retina displays).
- **`java -jar agent.jar <pid>` can't attach** — the attach CLI must run on a JDK; some sandboxes
  (and cross-user targets) refuse attach outright. Run `java -jar agent.jar` with no args to confirm
  the target JVM is listed.

## License

[MIT](LICENSE) © 2026 Paul Griffith. The agent jar bundles third-party libraries under their own
licenses; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
