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
      (is (= @d v))))
  (testing "async take! test"
    (let [d (d/deferred)
          v {:foo "bar"}
          p (promise)]
      (take! d (partial deliver p))
      (d/success! d v)
      (is (= @p v))))
  (testing "nested blocking take - !<!!"
    (is (= (!<!! (async
                   (go
                     (CompletableFuture/completedFuture
                       (completable-future
                         (go
                           (<! (timeout 50))
                           (let [d (d/deferred)]
                             (>! d {:foo "bar"})
                             d)))))))
           {:foo "bar"})))
  (testing "nested non-blocking take - !<!"
    (<!!
      (async
        (is (= (!<!! (async
                       (go
                         (CompletableFuture/completedFuture
                           (completable-future
                             (go
                               (<! (timeout 50))
                               (let [d (d/deferred)]
                                 (d/success! d {:foo "bar"})
                                 d)))))))
               {:foo "bar"}))))))
