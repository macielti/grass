(ns grass.stdio-jsonrpc-sever.adapters.interceptor-test
  (:require [clojure.test :refer [is testing]]
            [grass.stdio-jsonrpc-sever.adapters.interceptor :as adapters.interceptor]
            [matcher-combinators.test :refer [match?]]
            [schema.test])
  (:import (io.pedestal.interceptor Interceptor)))

(schema.test/deftest fn->interceptor-test
  (let [interceptor (adapters.interceptor/fn->interceptor :my-name identity)]
    (testing "that it builds an Interceptor carrying the given name"
      (is (instance? Interceptor interceptor))
      (is (match? {:name :my-name} interceptor)))))
