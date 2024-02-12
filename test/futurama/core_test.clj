(ns futurama.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [futurama.core :refer [!<!! !<! !<!* <!* with-pool
                                   async async-> async->> async?
                                   async-future async-deferred
                                   async-for async-map async-reduce
                                   async-some async-every?
                                   async-prewalk async-postwalk
                                   async-cancel! async-cancelled?
                                   completable-future fixed-threadpool]]
            [clojure.core.async :refer [go timeout put! take! <! >! <!!] :as a]
            [criterium.core :refer [report-result
                                    quick-benchmark
                                    with-progress-reporting]])
  (:import [java.util.concurrent Executors CompletableFuture ExecutionException]
           [clojure.lang ExceptionInfo]))

(def ^:dynamic *test-val1* nil)
(def test-val2 nil)

(defmacro wrap-async
  [f & args]
  `(fn ~(symbol (str "async" (name f)))
     [& ~'argv]
     (async
      (apply ~f ~@args ~'argv))))

(defn plus-a-times-b
  [a b]
  (* (+ a 100) b))

(def test-pool
  (delay
    (fixed-threadpool)))

(deftest test-completable-future
  (testing "basic completable-future test"
    (let [a (atom false)
          f (completable-future
              ;; Body can contain multiple elements.
             (reset! a true)
             (range 10))]
      (is (= @f (range 10)))
      (is (true? @a))))
  (testing "binding frame completable-future test"
    (binding [*test-val1* 1]
      (let [f (completable-future *test-val1*)] ;;; found result of *test-val1* should be returned
        (is (= @f 1))))))

(deftest cancel-async-test
  (testing "cancellable completable-future is interrupted test"
    (let [a (promise)
          s (atom 0)
          f (completable-future
             (try
               (while (not (async-cancelled?)) ;;; this loop goes on infinitely until the thread is interrupted
                 (Thread/sleep 10)
                 (println "future looping..." (swap! s inc)))
               (println "ended future looping.")
               (deliver a true)
               (catch Throwable e
                 (println "interrupted looping by:" (type e))
                 (deliver a true))))]
      (go
        (<! (timeout 100))
        (async-cancel! f)) ;;; cancelling the completable future causes the backing thread to be interrupted
      (is (true? @a))
      (is (true? (async-cancelled? f)))))
  (testing "cancellable async-future block is interrupted test"
    (let [a (promise)
          s (atom 0)
          f (async-future
             (try
               (while (not (async-cancelled?)) ;;; this loop goes on infinitely until the thread is interrupted
                 (Thread/sleep 10)
                 (println "future looping..." (swap! s inc)))
               (println "ended future looping.")
               (deliver a true)
               (catch Throwable e
                 (println "interrupted looping by:" (type e))
                 (deliver a true))))]
      (go
        (<! (timeout 100))
        (async-cancel! f)) ;;; cancelling the completable future causes the backing thread to be interrupted
      (is (true? @a))
      (is (true? (async-cancelled? f)))))
  (testing "cancellable async-deferred block is interrupted test"
    (let [a (promise)
          s (atom 0)
          f (async-deferred
             (try
               (while (not (async-cancelled?)) ;;; this loop goes on infinitely until the thread is interrupted
                 (Thread/sleep 10)
                 (println "future looping..." (swap! s inc)))
               (println "ended future looping.")
               (deliver a true)
               (catch Throwable e
                 (println "interrupted looping by:" (type e))
                 (deliver a true))))]
      (go
        (<! (timeout 100))
        (async-cancel! f)) ;;; cancelling the completable future causes the backing thread to be interrupted
      (is (true? @a))
      (is (true? (async-cancelled? f)))))
  (testing "cancellable async block is interrupted test"
    (let [a (promise)
          s (atom 0)
          f (async
             (try
               (while (not (async-cancelled?)) ;;; this loop goes on infinitely until the thread is interrupted
                 (!<! (timeout 10))
                 (println "future looping..." (swap! s inc)))
               (println "ended future looping.")
               (deliver a true)
               (catch Throwable e
                 (println "interrupted looping by:" (type e))
                 (deliver a true))))]
      (go
        (<! (timeout 100))
        (async-cancel! f)) ;;; cancelling the completable future causes the backing thread to be interrupted
      (is (true? @a))
      (is (true? (async-cancelled? f)))))
  (testing "cancellable nested async cancellable is interrupted test"
    (let [a (promise)
          s (atom 0)
          f (async
             (completable-future
              (async
               (try
                 (while (not (async-cancelled?)) ;;; this loop goes on infinitely until the thread is interrupted
                   (!<! (timeout 10))
                   (println "future looping..." (swap! s inc)))
                 (println "ended future looping.")
                 (deliver a true)
                 (catch Exception e
                   (println "interrupted looping by:" (type e))
                   (deliver a true))))))]
      (go
        (<! (timeout 100))
        (async-cancel! f)) ;;; cancelling the completable future causes the backing thread to be interrupted
      (is (true? @a))
      (is (true? (async-cancelled? f))))))

(deftest with-pool-macro-test
  (testing "with-pool evals body"
    (!<!!
     (with-pool @test-pool
       (async
        (is (= 100
               (!<! (CompletableFuture/completedFuture 100)))))))))

(deftest thread-first-macro-tests
  (testing "can thread first async->"
    (is (= 1500
           (-> 10
               (+ 10)
               (* 5)
               (+ 100)
               (plus-a-times-b 5))
           (<!!
            (async-> 10
                     ((wrap-async +) 10)
                     ((wrap-async *) 5)
                     ((wrap-async +) 100)
                     ((wrap-async plus-a-times-b) 5)))))))

(deftest thread-last-macro-tests
  (testing "can thread first async->>"
    (is (= 21000
           (->> 10
                (+ 10)
                (* 5)
                (+ 100)
                (plus-a-times-b 5))
           (<!!
            (async->> 10
                      ((wrap-async +) 10)
                      ((wrap-async *) 5)
                      ((wrap-async +) 100)
                      ((wrap-async plus-a-times-b) 5)))))))

(deftest async-some-test
  (testing "async-some async test returns first returned valid logical true"
    (is (= 9 ;;; always returns 9 even though it's the last number because it is returned first
           (!<!! (async-some
                  (fn [n]
                    (async
                     (when (odd? n)
                       (!<! (timeout (- 1000 (* n 100))))
                       n)))
                  (range 10)))))))

(deftest async-every-test
  (testing "async-every? async test returns true when all true"
    (is (= true
           (!<!! (async-every?
                  (fn [n]
                    (async
                     (when (number? n)
                       (!<! (timeout 50))
                       true)))
                  (range 10))))))
  (testing "async-every? async test returns false when some false"
    (is (= false
           (!<!! (async-every?
                  (fn [n]
                    (async
                     (when (not= n 5)
                       (!<! (timeout 50))
                       true)))
                  (range 10)))))))

(deftest async-reduce-test
  (testing "async reduce async result handling"
    (is (= 55
           (!<!! (async-reduce (fn [& nsq]
                                 (if (empty? nsq)
                                   10
                                   (async
                                    (apply + nsq))))
                               (async (range 10)))))))
  (testing "async reduce async result handling - provide init"
    (is (= 55
           (!<!! (async-reduce (fn [total number]
                                 (async
                                  (+ total number))) 10 (async (range 10)))))))
  (testing "async reduce async result handling - reduce a map"
    (is (= {:foo 1
            :bar 2
            :zlu 10}
           (!<!! (async-reduce (fn [m k v]
                                 (async
                                  (assoc m k (inc v))))
                               {} (async {:foo 0
                                          :bar 1
                                          :zlu 9})))))))

(deftest async-prewalk-test
  (testing "async prewalk async walk handler"
    (is (= {:foo [:bar 100 #{1 2 3 4 5}]}
           (!<!! (async-prewalk (fn [n]
                                  (async n))
                                (CompletableFuture/completedFuture
                                 {:foo
                                  (CompletableFuture/completedFuture
                                   [:bar (go 100) (async #{1 2 3 4 5})])})))))))

(deftest async-postwalk-test
  (testing "async postwalk async walk handler"
    (is (= {:foo [:bar 100 #{1 2 3 4 5}]}
           (!<!! (async-postwalk (fn [n]
                                   (async n))
                                 (CompletableFuture/completedFuture
                                  {:foo
                                   (CompletableFuture/completedFuture
                                    [:bar (go 100) (async #{1 2 3 4 5})])})))))))

(deftest async-map-test
  (testing "works the same way as a map fn with multiple colls"
    (let [async-handler #(async (apply + %&))
          args (repeat 10 (range 10))]
      (is (= [0 10 20 30 40 50 60 70 80 90]
             (apply map + args)
             (!<!! (apply async-map async-handler args))))))
  (testing "can loop map concurrently, performance test"
    (let [bench (with-progress-reporting
                  (quick-benchmark
                   (<!! (async-map #(async (!<! (timeout 50)) (inc %)) (range 10)))
                   {:verbose true}))
          [mean [lower upper]] (:mean bench)]
      (report-result bench)
      (is (<= 0.05 lower mean upper 0.07)))))

(deftest async-for-test
  (testing "can loop for concurrently, performance test"
    (let [bench (with-progress-reporting
                  (quick-benchmark
                   (<!!
                    (async-for
                     [a (range 4)
                      b (range 4)
                      :let [c (+ a b)]
                      :when (and (odd? a) (odd? b))]
                     (async
                      (!<! (timeout 50))
                      [a b c (+ a b c)])))
                   {:verbose true}))
          [mean [lower upper]] (:mean bench)]
      (report-result bench)
      (is (<= 0.05 lower mean upper 0.07)))))

(deftest async-ops
  (testing "async? for CompletableFuture"
    (is (true? (async? (CompletableFuture/completedFuture "yes")))))
  (testing "async? for core.async channel"
    (is (true? (async? (go "yes")))))
  (testing "async? for raw value"
    (is (false? (async? "no"))))
  (testing "raw value handling - !<!"
    (let [v {:foo "bar"}]
      (is (= v (!<!! (async (!<! v)))))))
  (testing "raw value handling - !<!!"
    (let [v {:foo "bar"}]
      (is (= v (!<!! v)))))
  (testing "async put! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}]
      (put! f v)
      (put! f {:foo "baz"})
      (is (= v @f))))
  (testing "async put! nested test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}]
      (put! f (CompletableFuture/completedFuture v))
      (is (= v @f))))
  (testing "async take! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}
          p (promise)]
      (take! f (partial deliver p))
      (.complete f v)
      (is (= v @p))))
  (testing "async take! nested test"
    (let [^CompletableFuture f (CompletableFuture/completedFuture
                                (CompletableFuture/completedFuture {:foo "bar"}))
          v {:foo "bar"}
          r (<!! f)]
      (is (= v r))))
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
  (testing "sequential collection non-blocking take - <!*"
    (<!!
     (async
      (is (= (range 1 11)
             (<!*
              (for [n (range 10)]
                (async (inc n)))))))))
  (testing "sequential collection non-blocking take - !<!*"
    (<!!
     (async
      (is (= (range 1 11)
             (!<!*
              (for [n (range 10)]
                (async (inc n)))))))))
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
                          (delay
                            (future
                              (atom
                               (let [p (promise)]
                                 (deliver p c)
                                 p))))))))))))))
  (testing "nested non-blocking take - !<!"
    (<!!
     (async
      (is (= {:foo "bar"}
             (!<! (async
                   (go
                     (CompletableFuture/completedFuture
                      (completable-future
                       (go
                         (<! (timeout 50))
                         (delay
                           (future
                             (let [p (promise)]
                               (deliver p
                                        (CompletableFuture/completedFuture {:foo "bar"}))
                               p)))))))))))))))

(deftest error-handling
  (testing "throws async exception on blocking deref from completable future - @"
    (is (thrown-with-msg?
         ExceptionInfo #"foobar"
         (try
           @(completable-future
             (throw (ex-info "foobar" {}))
             ::result)
            ;;; this is just necessary to test when an exception is thrown
            ;;; via Deref it is wrapped in an ExecutionException
           (catch ExecutionException ee
             (throw (ex-cause ee)))))))
  (testing "throws async exception on blocking deref from async! with completable future - @"
    (is (thrown-with-msg?
         ExceptionInfo #"foobar"
         (try
           @(async-future
             (throw (ex-info "foobar" {}))
             ::result)
            ;;; this is just necessary to test when an exception is thrown
            ;;; via Deref it is wrapped in an ExecutionException
           (catch ExecutionException ee
             (throw (ex-cause ee)))))))
  (testing "throws async exception on blocking deref from async! with deferred - @"
    (is (thrown-with-msg?
         ExceptionInfo #"foobar"
         (try
           @(async-deferred
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
