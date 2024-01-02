(ns futurama.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [futurama.core :refer [!<!! !<! async completable-future]]
            [clojure.core.async :refer [go timeout put! take! <! >! <!!] :as a])
  (:import [java.util.concurrent CompletableFuture ExecutionException]
           [clojure.lang ExceptionInfo]))

(def ^:dynamic *test-val1* nil)
(def test-val2 nil)

(deftest async-ops
  (testing "async put! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}]
      (put! f v)
      (is (= v @f))))
  (testing "async take! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}
          p (promise)]
      (take! f (partial deliver p))
      (.complete f v)
      (is (= v @p))))
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
    (is (= {:foo "bar"}
           (!<!! (async
                   (go
                     (CompletableFuture/completedFuture
                       (completable-future
                         (go
                           (<! (timeout 50))
                           (let [c (CompletableFuture.)]
                             (>! c {:foo "bar"})
                             c))))))))))
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
                               (CompletableFuture/completedFuture {:foo "bar"})))))))))))))

(deftest error-handling
  (testing "throws async exception on blocking deref - @"
    (is (thrown-with-msg?
          ExceptionInfo #"foobar"
          (try
            @(async
               (throw (ex-info "foobar" {}))
               ::result)
            ;;; this is just necessary to test when an exception is thrown
            ;;; via Deref it is wrapped in an ExecutionException
            (catch ExecutionException ee
              (throw (ex-cause ee)))))))
  (testing "throws async exception on blocking take - !<!!"
    (is (thrown-with-msg?
          ExceptionInfo #"foobar"
          (!<!! (async
                  (throw (ex-info "foobar" {}))
                  ::result)))))
  (testing "throws async rxception on non-blocking take - !<!"
    (<!!
      (async
        (is (thrown-with-msg?
              ExceptionInfo #"foobar"
              (!<! (async
                     (throw (ex-info "foobar" {}))
                     ::result))))))))
