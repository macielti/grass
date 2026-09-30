(ns grass.stdio-jsonrpc-sever.models.handler
  (:require [schema.core :as s])
  (:import (clojure.lang IFn)))

(s/defschema InputSchema
  "JSON Schema object describing the params a tool call expects. It goes out verbatim in
  the `tools/list` response, so `:type` is always the string \"object\" - a tool with no
  params still declares `{:type \"object\"}`. Param names are keywords, which the JSON
  encoder renders as strings (`:required [:period]` ships as `\"required\":[\"period\"]`).
  Only the keys below are accepted - widen it as a tool needs more."
  {:type                        (s/eq "object")
   (s/optional-key :properties) (s/pred map?)
   (s/optional-key :required)   [s/Keyword]})

(s/defschema Handler
  {:method       s/Str
   :doc          s/Str
   :input-schema InputSchema
   :interceptors [IFn]
   :handler-fn   IFn
   :type         (s/enum :request :notification)})

(s/defschema Handlers [Handler])
