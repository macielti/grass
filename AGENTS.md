# AGENTS.md

## Project

MCP server (stdio JSON-RPC) for AUVP portfolio consolidation. Clojure + Leiningen.

## Commands

- `lein test` — run tests
- `lein antq` — check for outdated dependencies
- `lein repl` — start REPL (init-ns: `grass.components`)

## Architecture

- **Integrant** for component lifecycle. Always register a JVM shutdown hook after `ig/init` (see style guide).
  - `init-key` state map is `{:server <ChanServer>}` only. `halt-key!` receives `[_ state]` — 2 args, not 1.
- `grass.stdio-jsonrpc-sever.component` (in `src/grass/stdio_jsonrpc_sever/`) builds the server with
  `jsonrpc4clj.io-server/stdio-server` and must call
  `endpoint/start` / `endpoint/shutdown` explicitly (`ChanServer` implements `IEndpoint`; it does not self-start).
  - The README says `jsonrpc4clj.server/start`, but in 1.0.2 only `send-request`/`send-notification` are aliased
    there — `start`/`shutdown` are protocol methods, so require `jsonrpc4clj.protocols.endpoint`.
  - The server is hardwired to real stdio (no `:in`/`:out` escape hatch). To verify, pipe a framed message in
    **and keep stdin open** — `{ printf 'Content-Length: 65\r\n\r\n{...}'; sleep 6; } | ...`. A single frame
    with immediate EOF makes the server shut down and *races the response write*, so responses vanish and the
    failure looks like a broken handler. Also get the byte count right (`printf '%s' "$body" | wc -c`) or the
    frame is silently mis-parsed. Report from handlers to `*err*`; stdout is the protocol.
- **Handler map** (prismatic schema in `grass.stdio-jsonrpc-sever.models.handler`, validated in `init-key`):
  ```clojure
  {:method       "tools/call"            ; string
   :interceptors [#(fn [context] ...)]   ; vector of fns, run before handler-fn
   :handler-fn   (fn [context] ...)      ; fn
   :type         :notification}          ; :request | :notification
  ```
  - `:type` picks the multimethod — `:request` registers `receive-request`, `:notification` registers
    `receive-notification`. Return the **result value directly** from a `:request` handler (the server wraps it
    as `result`); returning `{:result ...}` double-wraps. A return that looks like `{:error ...}` becomes an error response.
  - `:interceptors` and `:handler-fn` are both **context transformers** `(fn [context] context)`, not Pedestal HTTP
    handlers. Do **not** coerce with `pedestal.interceptor/-interceptor`: its `wrap-handler` passes `(:request ctx)`
    to the fn and stores `:response`, which silently yields `nil` here. `fn->interceptor` builds the record directly.
  - Chain context is the jsonrpc4clj context plus `:payload` (the kebab-cased params), so handlers reach siblings via
    `(get-in context [:components :db])` and inbound params via `(:payload context)`.
  - `params` keys arrive **recursively kebab-cased** (`textDocument` -> `:text-document`).
  - Never name a local `server` — it shadows the `jsonrpc4clj.server` alias.
- **stdout is the protocol.** Any stray `println` corrupts the client's stream. Inside the `receive-*` multimethods
  `*out*` is already discarded (so a `println` there vanishes and is *not* a bug), but background threads and
  startup code need `jsonrpc4clj.server/discarding-stdout`. Debug with `*err*`.
- Wire framing is LSP-style: `Content-Length: N\r\n\r\n<json>`. Server-side errors land on `(:log-ch server)`,
  which nothing consumes yet — subscribe to it when debugging.
- Consider `mcp-clojure-sdk` (unravel-team) — a Clojure MCP SDK built on this same lib — before hand-rolling more.
- `grass.components` is the REPL init namespace, not a real entrypoint. There is no system entrypoint yet; `grass.components/foo` is a placeholder from the project template.

## Conventions

- Follow [../clojure-style-guide/CLAUDE.md](../clojure-style-guide/CLAUDE.md) for all Clojure style, Integrant patterns, and library preferences.
- Use `net.clojars.macielti/common-clj` for shared utilities (UUID validation, schema extensions, etc.) — already common across sibling projects.
- Sibling projects under `../dev/` share the same style guide and common libraries; check them for patterns before writing new code.
- Components live in a **folder named after the component**, with the namespace inside always called `component`:
  `src/grass/<component>/component.clj` -> `grass.<component>.component`. Each carries its own `ig/init-key` /
  `ig/halt-key!` methods. The Integrant key auto-namespaces with the ns, so it reads
  `::grass.stdio-jsonrpc-sever.component/stdio-jsonrpc-sever` — it changes whenever the file moves.
  - Schemas go in that component's `models/` subfolder, named after the schema
    (`src/grass/<component>/models/handler.clj` -> `grass.<component>.models.handler`).
  - Thin wrappers over third-party types go in that component's `adapters/` subfolder, named after what they adapt
    (`adapters/interceptor.clj` -> `grass.<component>.adapters.interceptor`), as in
    `telegrama/component/adapters/event.clj`. Adapters are public; keep helpers `^:private` inside `component.clj`.

## Testing

- Test namespace: `grass.core-test`
- Current test is a placeholder that fails — replace with real tests as features are implemented.
