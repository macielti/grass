(ns grass.stdio-jsonrpc-sever.models.handler
  (:require [schema.core :as s])
  (:import (clojure.lang IFn)))

(s/defschema Handler
  {:method       s/Str
   :interceptors [IFn]
   :handler-fn   IFn
   :type         (s/enum :request :notification)})

(s/defschema Handlers [Handler])
