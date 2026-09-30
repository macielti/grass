(ns grass.stdio-jsonrpc-sever.component
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [jsonrpc4clj.io-server :as io-server]
            [jsonrpc4clj.protocols.endpoint :as endpoint]
            [jsonrpc4clj.server :as server]
            [schema.core :as s]
            [grass.stdio-jsonrpc-sever.adapters.interceptor :as adapters.interceptor]
            [grass.stdio-jsonrpc-sever.models.handler :as models.handler]))

(s/defn ^:private execute-handler!
  [{:keys [interceptors handler-fn]} :- models.handler/Handler
   context
   params]
  (interceptor.chain/execute
   (assoc context :payload params)
   (concat (map #(adapters.interceptor/fn->interceptor :interceptor %) interceptors)
           [(adapters.interceptor/fn->interceptor :handler-fn handler-fn)])))

(s/defn ^:private register-request-handler!
  [{:keys [method] :as handler} :- models.handler/Handler]
  (defmethod server/receive-request method
    [_ context params]
    (execute-handler! handler context params)))

(s/defn ^:private register-notification-handler!
  [{:keys [method] :as handler} :- models.handler/Handler]
  (defmethod server/receive-notification method
    [_ context params]
    (execute-handler! handler context params)))

(s/defn ^:private register-handler!
  [{:keys [type] :as handler} :- models.handler/Handler]
  (case type
    :request (register-request-handler! handler)
    :notification (register-notification-handler! handler)))

(s/defn ^:private register-handlers!
  [handlers :- models.handler/Handlers]
  (s/validate models.handler/Handlers handlers)
  (doseq [handler handlers]
    (register-handler! handler)))

(defmethod ig/init-key ::stdio-jsonrpc-sever
  [_ {:keys [handlers components]}]
  (let [stdio-server (io-server/stdio-server)]
    (log/info :starting ::stdio-jsonrpc-sever)
    (register-handlers! handlers)
    (endpoint/start stdio-server {:components components :server stdio-server})
    {:server stdio-server}))

(defmethod ig/halt-key! ::stdio-jsonrpc-sever
  [_ {:keys [server]}]
  (log/info :stopping ::stdio-jsonrpc-sever)
  (endpoint/shutdown server))
