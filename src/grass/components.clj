(ns grass.components
  (:require [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [schema.core :as s]
            [grass.stdio-jsonrpc-sever.component :as component.stdio-jsonrpc-sever])
  (:gen-class))

(def cli-options
  [["-t" "--auvp-token TOKEN" "AUVP portfolio consolidation token"
    :id :auvp-token
    :missing "--auvp-token is required"]])

(defn arrangement
  "The Integrant arrangement. The AUVP token is passed down as a sibling in `:components` so
  handlers reach it with `(get-in context [:components :auvp :token])`."
  [auvp-token]
  {::component.stdio-jsonrpc-sever/stdio-jsonrpc-sever
   {:handlers   []
    :components {:auvp {:token auvp-token}}}})

(s/defn ^:private start-system!
  [auvp-token :- s/Str]
  (let [system (ig/init (arrangement auvp-token))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! system)))
    system))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (when errors
      (log/error summary)
      (System/exit 1))
    (start-system! (:auvp-token options))))
