(ns futurama.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [futurama.core :refer [!<!! !<! !<!* with-pool
                                   async async-> async->> async?
                                   async-for async-map async-reduce
                                   async-some async-every?
                                   async-prewalk async-postwalk
                                   completable-future]]
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
    (Executors/newSingleThreadExecutor)))

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
                         (do
                           (!<! (timeout (- 1000 (* n 100))))
                           n))))
                   (range 10)))))))

(deftest async-every-test
  (testing "async-every? async test returns true when all true"
    (is (= true
           (!<!! (async-every?
                   (fn [n]
                     (async
                       (when (number? n)
                         (do
                           (!<! (timeout 50))
                           true))))
                   (range 10))))))
  (testing "async-every? async test returns false when some false"
    (is (= false
           (!<!! (async-every?
                   (fn [n]
                     (async
                       (when (not= n 5)
                         (do
                           (!<! (timeout 50))
                           true))))
                   (range 10)))))))

(deftest async-reduce-test
  (testing "async reduce async result handling"
    (is (= 45
           (!<!! (async-reduce (fn [& nsq]
                                 (async
                                   (apply + nsq))) (async (range 10))))))))

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
      (is (= v (!<! v)))))
  (testing "raw value handling - !<!!"
    (let [v {:foo "bar"}]
      (is (= v (!<!! v)))))
  (testing "async put! test"
    (let [^CompletableFuture f (CompletableFuture.)
          v {:foo "bar"}]
      (put! f v)
      (put! f {:foo "baz"})
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
                                 (let [p (promise)]
                                   (deliver p c)
                                   p)))))))))))))
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
