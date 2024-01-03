(ns futurama.deferred-test
  (:require [clojure.test :refer [deftest testing is]]
            [futurama.core :refer [!<!! !<! async completable-future]]
            [clojure.core.async :refer [go timeout put! take! >! <! <!!] :as a]
            [manifold.deferred :as d])
  (:import [java.util.concurrent CompletableFuture]))

(deftest async-ops
  (testing "async put! test"
    (let [d (d/deferred)
          v {:foo "bar"}]
      (put! d v)
      (is (= v @d))))
  (testing "async take! test"
    (let [d (d/deferred)
          v {:foo "bar"}
          p (promise)]
      (take! d (partial deliver p))
      (d/success! d v)
      (is (= v @p))))
  (testing "async put! test"
    (let [d (d/deferred)
          v {:foo "bar"}]
      (put! d v)
      (put! d {:foo "baz"})
      (is (= v @d))))
  (testing "async take! test"
    (let [d (d/deferred)
          v {:foo "bar"}
          p (promise)]
      (take! d (partial deliver p))
      (d/success! d v)
      (is (= v @p))))
  (testing "nested blocking take - !<!!"
    (is (= {:foo "bar"}
           (!<!! (async
                   (go
                     (CompletableFuture/completedFuture
                       (completable-future
                         (go
                           (<! (timeout 50))
                           (let [d (d/deferred)]
                             (>! d {:foo "bar"})
                             d))))))))))
  (testing "nested non-blocking take - !<!"
    (<!!
      (async
        (is (= {:foo "bar"}
               (!<!! (async
                       (go
                         (CompletableFuture/completedFuture
                           (completable-future
                             (go
                               (<! (timeout 50))
                               (let [d (d/deferred)]
                                 (d/success! d {:foo "bar"})
                                 d)))))))))))))
