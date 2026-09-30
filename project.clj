(defproject grass "0.1.0"

  :description "Grass is a MCP server to allow LLM models to interact with AUVP portfolio consolidation tool (not oficial)"

  :url "https://github.com/macielti/grass"

  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[org.clojure/clojure "1.12.6"]
                 [integrant "1.0.1"]
                 [org.clojure/tools.logging "1.3.1"]
                 [com.github.clojure-lsp/jsonrpc4clj "1.0.2"]
                 [prismatic/schema "1.4.1"]
                 [io.pedestal/pedestal.interceptor "0.8.1"]]

  :plugins [[com.github.liquidz/antq "RELEASE"]]

  :profiles {:dev {:test-paths   ["test/unit" "test/integration" "test/helpers"]
                   :dependencies [[nubank/matcher-combinators "3.10.0"]]}}

  :repl-options {:init-ns grass.components})
