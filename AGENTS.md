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
  - `register-handler!` is a `defmulti` dispatching on `(:type handler)`, with an `s/defmethod` per type. Each
    method calls `defmethod` on the matching jsonrpc4clj multimethod — `receive-request` for `:request`,
    `receive-notification` for `:notification` — so the multimethod on the *jsonrpc4clj* side is chosen by
    which `defmethod` of ours runs, not by a lookup table. Dispatch is on `(:type handler)`, not `:type` as a
    bare key, matching `ell-iot/adapters/metric.clj` and `beagle-cli`.
  - Return the **result value directly** from a `:request` handler (the server wraps it as `result`);
    returning `{:result ...}` double-wraps. A return that looks like `{:error ...}` becomes an error response.
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
  - `clojure.tools.logging` needs a backend or it is a **silent no-op** — `tools.logging` only ships the SLF4J
    facade, and SLF4J's own default is NOP. `[org.slf4j/slf4j-simple "2.0.17"]` is in `:dependencies` for this
    reason; without it every `log/info` in the project silently vanishes (it did — the `init-key`/shutdown-hook
    logging was invisible until this was added). `slf4j-simple` writes to **stderr** by default, which is
    exactly right here. Siblings use `com.taoensso/timbre` + `tools.logging` (`beagle-cli`, `baiacu`) or
    `ch.qos.logback/logback-classic` (`common-clj`); `slf4j-simple` is the smallest thing that makes the calls
    visible and needs no `resources/logback.xml`.
- Wire framing is LSP-style: `Content-Length: N\r\n\r\n<json>`. Server-side errors land on `(:log-ch server)`,
  which nothing consumes yet — subscribe to it when debugging.
- Consider `mcp-clojure-sdk` (unravel-team) — a Clojure MCP SDK built on this same lib — before hand-rolling more.
- `grass.components` is the system entrypoint and the REPL init ns (`:main grass.components`). It holds the
  Integrant `arrangement` and `-main`; it defines no `ig/init-key` of its own.
  - The AUVP token is parsed with `clojure.tools.cli` from `-t` / `--auvp-token TOKEN`, carried in
    `cli-options` (declared with `:id :auvp-token` and `:missing "--auvp-token is required"`). Run it as
    `lein run -- --auvp-token <token>`. `parse-opts` returns errors rather than throwing, so `-main` checks
    `errors` itself: a `when errors` guard `(log/error summary)` and exits 1, then `start-system!` runs
    unconditionally below it. The `:errors` messages themselves are discarded, so a typo, a missing token, and a
    flag with no value all produce identical output — deliberately, the usage line is enough for a one-option
    CLI. The explicit exit matters: without it the process reports 0 on a misconfigured launch, which reads as
    "started and shut down cleanly" to the client subprocess that launched it.
  - The token is threaded into the arrangement as `{:components {:auvp {:token ...}}}`, so a handler reads it
    with `(get-in context [:components :auvp :token])`. It is a plain map, not an Integrant component — making
    it one would need a real `ig/init-key` just to hold a string.
  - It registers the JVM shutdown hook per the style guide — `(.addShutdownHook (Runtime/getRuntime) (Thread.
    #(ig/halt! system)))` — which is also what lets `ig/halt!` close the `ChanServer`.
  - It does **not** call `jsonrpc4clj.server/discarding-stdout` for now. Nothing in the current path prints to
    stdout: SLF4J goes to stderr, and the `receive-*` multimethods already have `*out*` discarded by the server.
    If a background thread or a library ever gains a `println`, re-add `(server/discarding-stdout)` **before**
    `ig/init` — it has to be installed before anything can log, and a stray write corrupts the client's stream.
  - `:handlers` is currently `[]`: the component starts and answers nothing yet. Adding a tool means adding
    its handler to that vector, not to the component.

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
  `test/integration`, `test/helpers`); `project.clj` sets `:test-paths` to match. `test/integration` and
  `test/helpers` are declared but not yet created — git does not track empty dirs.
- **Test our code, not the library's.** Cover the behaviour a namespace in `src/grass/` is responsible for —
  the shape it builds and the contract it exposes. Do **not** assert on behaviour that a dependency already
  guarantees on its own (interceptor chain ordering, what a chain returns, how a library validates its own
  arguments, what a macro expands to). If that behaviour ever changes upstream it arrives as a published
  break change, and our test would fail without telling us anything about our code. A test should only fail
  when *we* changed something.
  - Concretely, `adapters/interceptor` has a single `testing` block: that it returns a Pedestal `Interceptor`
    carrying the given `:name`. It is **not** tested on the fields of the record we happen to populate
    (`:enter` being a fn), on argument validation (the `s/deftest` wrapper covers that), nor on what Pedestal
    does with the record afterwards — how `io.pedestal.interceptor.chain/execute` orders the enters, or what
    context it hands them. A one-key pass-through like this adapter does not need more than that.
  - Before adding an assertion, ask which file someone would have to edit for it to go red. If the answer is a
    file outside `src/grass/`, drop the test.
- `matcher-combinators` is the assertion library of choice (`match?`), with `clojure.test`'s
  `is` / `testing` for the surrounding shape — the house pattern in `baiacu` and `risky`.
- **Use `schema.test/deftest`, not `clojure.test/deftest`.** It expands to `clojure.test/deftest` with the
  body wrapped in `s/with-fn-validation`, which turns the `:-` arglist annotations on for every call made in
  the test. That makes the annotations *enforced* rather than documentation, and it means you do not write
  assertions for argument validation — a bad call raises `ExceptionInfo` on its own, so a
  `(is (thrown? ...))` for "rejects a non-fn handler" and similar is redundant. Prefer
  `(:require [schema.test])` and calling `schema.test/deftest` by full name, as the siblings do.
  - Note what this does *not* cover: Pedestal's own asserts. Under fn validation the schema fires first, so
    a test that wants to pin Pedestal's `AssertionError` would have to step outside the validation wrapper.
    We do not test that — it is the library's guard, not ours.
- `s/defn` arglist annotations (`:- Long`, `:- IFn`, ...) are recorded in metadata and are **inert** until a
  call happens inside `s/with-fn-validation` — which `s/deftest` supplies. That is why a schema is worth
  reading as documentation of the contract, and why it is trustworthy inside our own tests.
- To validate a whole namespace rather than one test at a time, add the fixture
  `(use-fixtures :once schema.test/validate-schemas)`.
- `test.helper.schema/generate` from `net.clojars.macielti/common-test-clj` builds fixture data from a
  schema instead of a hand-written map, so fixtures track schema changes.
- The template placeholder test was removed along with its `test/grass/` folder. Note that Leiningen's default
  `:test-paths` is `["test"]`, and setting `:test-paths` in the `:dev` profile *adds* to that default rather
  than replacing it — so a stray test outside `test/unit/` still runs. Keep every test under `test/unit/`.
