(ns grass.stdio-jsonrpc-sever.adapters.interceptor
  (:require [io.pedestal.interceptor :as pedestal.interceptor]
            [schema.core :as s])
  (:import (clojure.lang IFn)
           (io.pedestal.interceptor Interceptor)))

(s/defn fn->interceptor :- Interceptor
  [name :- s/Keyword
   handler-fn :- IFn]
  (pedestal.interceptor/interceptor
   {:name  name
    :enter handler-fn}))
