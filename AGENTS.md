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
   :doc          "Calls a tool"          ; string, required
   :interceptors [#(fn [context] ...)]   ; vector of fns, run before handler-fn
   :handler-fn   (fn [context] ...)      ; fn
   :type         :notification}          ; :request | :notification
  ```
  - `:doc` is a **required** key (`s/Str`), not a comment or an optional annotation. A handler without it fails
    `s/validate` at init with `{:doc missing-required-key}`.
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

- Tests live in `test/unit/`, mirroring the `src/` tree: `test/unit/grass/<path>/<name>_test.clj` for
  `src/grass/<path>/<name>.clj`. The test ns is the source ns with a `-test` suffix, and it requires the
  source ns with its own alias (`grass.stdio-jsonrpc-sever.adapters.interceptor` -> `adapters.interceptor`).
  This is the dominant sibling layout (`baiacu`, `risky`, `beagle-cli` all split `test/unit`,
  `test/integration`, `test/helpers`); `project.clj` sets `:test-paths` to match.
- **Test our code, not the library's.** Cover the behaviour a namespace in `src/grass/` is responsible for —
  the shape it builds and the contract it exposes. Do **not** assert on behaviour that a dependency already
  guarantees on its own (interceptor chain ordering, what a chain returns, how a library validates its own
  arguments, what a macro expands to). If that behaviour ever changes upstream it arrives as a published
  break change, and our test would fail without telling us anything about our code. A test should only fail
  when *we* changed something.
  - Concretely, `adapters/interceptor` is tested only on the result: that it returns a Pedestal `Interceptor`
    carrying the given `:name`, and that it does not build one from a non-fn handler. It is **not** tested on
    the fields of the record we happen to populate (`:enter` being a fn), nor on what Pedestal does with that
    record — how `io.pedestal.interceptor.chain/execute` orders the enters, or what context it hands them.
    A one-key pass-through like this adapter has a two-assertion test; anything more is testing Pedestal.
    Prefer a single `match?` on the whole record over one assertion per key.
  - Before adding an assertion, ask which file someone would have to edit for it to go red. If the answer is a
    file outside `src/grass/`, drop the test.
- `matcher-combinators` is the assertion library of choice (`match?`), with `clojure.test`'s
  `is` / `testing` for the surrounding shape — the house pattern in `baiacu` and `risky`.
- `schema.test/deftest` wraps a test to also assert the fn's return value matches its declared
  return schema. Use it for adapters with a `:-` return annotation; plain `deftest` is fine otherwise.
- `test.helper.schema/generate` from `net.clojars.macielti/common-test-clj` builds fixture data from a
  schema instead of a hand-written map, so fixtures track schema changes.
- **`s/defn` arglist annotations do not validate at runtime by default.** `s/defn` records
  `:- Long`, `:- IFn` etc. in the arglist metadata only; enforcement needs `s/with-fn-validation` around
  the call. So a bad argument to an annotated fn usually surfaces as whatever the *body* throws, not as an
  `ExceptionInfo`. Note Pedestal's `interceptor` raises an `AssertionError`, which is an `Error` — so
  `(is (thrown? Exception ...))` silently misses it; assert on `AssertionError` instead. This is worth
  knowing while reading the schemas, not worth a test of its own.
- `test/grass/core_test.clj` is still the template placeholder and fails (`(is (= 0 1))`). It sits outside
  `test/unit/`, so it only runs because Leiningen's default `test` path is still on the classpath.
