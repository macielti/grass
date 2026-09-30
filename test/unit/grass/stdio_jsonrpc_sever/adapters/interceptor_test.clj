(ns grass.stdio-jsonrpc-sever.adapters.interceptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [grass.stdio-jsonrpc-sever.adapters.interceptor :as adapters.interceptor]
            [matcher-combinators.test :refer [match?]])
  (:import (io.pedestal.interceptor Interceptor)))

(deftest fn->interceptor-test
  (testing "that it builds an Interceptor carrying the given name"
    (is (instance? Interceptor (adapters.interceptor/fn->interceptor :my-name identity)))
    (is (match? {:name :my-name}
                (adapters.interceptor/fn->interceptor :my-name identity))))

  (testing "that it rejects a handler fn that is not a function"
    (is (thrown? AssertionError (adapters.interceptor/fn->interceptor :my-name "not-a-fn")))))
