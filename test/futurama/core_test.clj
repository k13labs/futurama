(ns futurama.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [futurama.core :refer [!<!! !<! async completable-future]]
            [clojure.core.async :refer [go timeout put! take! close! chan <! >! <!! >!!] :as a])
  (:import [java.util.concurrent CompletableFuture]
           [clojure.lang ExceptionInfo]))

(def ^:dynamic *test-val1* nil)
(def test-val2 nil)

(deftest async-ops
  (testing "async put! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}]
      (put! f v)
      (is (= @f v))))
  (testing "async take! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}
          p (promise)]
      (take! f (partial deliver p))
      (.complete f v)
      (is (= @p v))))
  (testing "bindings test blocking - !<!!"
    (binding [*test-val1* 100]
      (with-redefs [test-val2 200]
        (is (= [100 200]
               (!<!!
                 (async
                   [*test-val1* test-val2]))
               (!<!!
                 (completable-future
                   [*test-val1* test-val2])))))))
  (testing "bindings test non-blocking - !<!"
    (binding [*test-val1* 100]
      (with-redefs [test-val2 200]
        (<!!
          (go
            (is (= [100 200]
                   (!<!
                     (async
                       [*test-val1* test-val2]))
                   (!<!
                     (completable-future
                       [*test-val1* test-val2])))))))))
  (testing "nested blocking take - !<!!"
    (is (= (!<!! (async
                   (go
                     (CompletableFuture/completedFuture
                       (completable-future
                         (go
                           (<! (timeout 50))
                           {:foo "bar"}))))))
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
                               {:foo "bar"}))))))
               {:foo "bar"}))))))

(deftest error-handling
  (testing "throws async exception on take !<!"
    (<!!
      (async
        (is (thrown-with-msg?
              ExceptionInfo #"foobar"
              (!<! (async
                     (throw (ex-info "foobar" {}))
                     ::result))))))))
