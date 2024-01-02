(ns futurama.core
  (:require [clojure.core.async :refer [<! <!! take!]]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.channels :refer [box]]
            [clojure.core.async.impl.ioc-macros :as ioc]
            [futurama.deferred])
  (:import [clojure.lang Var]
           [java.util.concurrent
            CompletableFuture
            CompletionException
            ExecutionException
            ExecutorService
            Future
            ForkJoinPool]
           [java.util.concurrent.locks Lock]
           [java.util.function Function BiConsumer]))

(def ^:dynamic *thread-pool* (ForkJoinPool/commonPool))

(defn unwrap-exception
  "unwraps an ExecutionException or CompletionException via ex-cause until the root exception is returned"
  [^Exception ex]
  (if-let [ce (and (or (instance? ExecutionException ex)
                       (instance? CompletionException ex))
                   (ex-cause ex))]
    ce
    ex))

(defn rethrow-exception
  "throw v if it is an Exception"
  [v]
  (if (instance? Exception v)
    (throw (unwrap-exception v))
    v))

(defmacro completable-future
  "Asynchronously invokes the body inside a completable future, preserves the current thread binding frame,
  using by default the `ForkJoinPool/commonPool`, the pool used can be specified via `*thread-pool*` binding."
  ^CompletableFuture [& body]
  `(let [binding-frame# (Var/cloneThreadBindingFrame) ;;; capture the thread local binding frame before start
         ^CompletableFuture res-fut# (CompletableFuture.) ;;; this is the CompletableFuture being returned
         ^ExecutorService pool# (or *thread-pool* (ForkJoinPool/commonPool))
         ^Runnable fbody# (fn do-complete#
                            []
                            (try
                              (Var/resetThreadBindingFrame binding-frame#) ;;; set the Clojure binding frame captured above
                              (.complete res-fut# (do ~@body)) ;;; send the result of evaluating the body to the CompletableFuture
                              (catch Exception ~'e
                                (.completeExceptionally res-fut# (unwrap-exception ~'e))))) ;;; if we catch an exception we send it to the CompletableFuture
         ^Future fut# (.submit pool# fbody#)
         ^Function cancel# (reify Function
                             (apply [~'_ ~'_]
                               (future-cancel fut#)))] ;;; submit the work to the pool and get the FutureTask doing the work
     ;;; if the CompletableFuture returns exceptionally
     ;;; then cancel the Future which is currently doing the work
     (.exceptionally res-fut# cancel#)
     res-fut#))

(extend-type CompletableFuture
  impl/ReadPort
  (take! [fut handler]
    (let [^CompletableFuture fut fut
          ^Lock handler handler
          commit-handler (fn do-commit []
                           (.lock handler)
                           (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (when-let [cb (commit-handler)]
        (if (.isDone fut)
          (let [val (try
                      (.getNow fut nil)
                      (catch Exception e
                        (unwrap-exception e)))]
            (if (satisfies? impl/ReadPort val)
              (do
                (take! val (fn do-read
                             [val]
                             (if (satisfies? impl/ReadPort val)
                               (take! val do-read)
                               (cb val))))
                nil)
              (box val)))
          (do
            (.whenComplete ^CompletableFuture fut
                           ^BiConsumer (reify BiConsumer
                                         (accept [_ val ex]
                                           (cond
                                             (satisfies? impl/ReadPort val)
                                             (take! val (fn do-read
                                                          [val]
                                                          (if (satisfies? impl/ReadPort val)
                                                            (take! val do-read)
                                                            (cb val))))

                                             (some? val)
                                             (cb val)

                                             (some? ex)
                                             (cb ex)

                                             :else
                                             (cb nil)))))
            nil)))))
  impl/WritePort
  (put! [fut val handler]
    (let [^CompletableFuture fut fut
          ^Lock handler handler]
      (when (nil? val)
        (throw (IllegalArgumentException. "Can't put nil on channel")))
      (if (.isDone fut)
        (do
          (.lock handler)
          (when (impl/active? handler)
            (impl/commit handler))
          (.unlock handler)
          (box false))
        (do
          (.lock handler)
          (when (impl/active? handler)
            (impl/commit handler))
          (.unlock handler)
          (box
           (if (instance? Exception val)
             (.completeExceptionally fut ^Exception val)
             (.complete fut val)))))))

  impl/Channel
  (close! [fut]
    (.complete ^CompletableFuture fut nil))
  (closed? [fut]
    (.isDone ^CompletableFuture fut)))

(defmacro async
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to !<!, <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread.
  Upon completion of the operation, the body will be resumed.

  async blocks should not (either directly or indirectly) perform operations
  that may block indefinitely. Doing so risks depleting the fixed pool of
  go block threads, causing all go block processing to stop. This includes
  core.async blocking ops (those ending in !!) and other blocking IO.

  Returns a channel which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [& body]
  (let [crossing-env (zipmap (keys &env) (repeatedly gensym))]
    `(let [c# (CompletableFuture.)
           captured-bindings# (Var/getThreadBindingFrame)]
       (.submit ^ExecutorService *thread-pool*
                ^Runnable (^:once fn* []
                                      (let [~@(mapcat (fn [[l sym]] [sym `(^:once fn* [] ~(vary-meta l dissoc :tag))]) crossing-env)
                                            f# ~(ioc/state-machine `(try
                                                                      ~@body
                                                                      (catch Exception ~'e
                                                                        (unwrap-exception ~'e))) 1 [crossing-env &env] ioc/async-custom-terminators)
                                            state# (-> (f#)
                                                       (ioc/aset-all! ioc/USER-START-IDX c#
                                                                      ioc/BINDINGS-IDX captured-bindings#))]
                                        (ioc/run-state-machine-wrapped state#))))
       c#)))

(defmacro !<!
  "An improved macro version of <!, which also rethrows exceptions returned over the channel.
  Must be called INSIDE a (go ...) or (async ...) block.
  - Will return nil if closed.
  - Will park if nothing is available.
  - Will throw if an Exception is taken from port.
  - Will return the raw value if it is not a ReadPort"
  [v]
  `(rethrow-exception
    (let [~'r ~v]
      (if (satisfies? impl/ReadPort ~'r)
        (<! ~'r)
        ~'r))))

(defmacro !<!!
  "An improved macro version of <!!, which also rethrows exceptions returned over the channel.
  Must be called OUTSIDE a (go ...) or (async ...) block.
  - Will return nil if closed.
  - Will block if nothing is available.
  - Will throw if a Exception is taken from port.
  - Will return the raw value if it is not a ReadPort"
  [v]
  `(rethrow-exception
    (let [~'r ~v]
      (if (satisfies? impl/ReadPort ~'r)
        (<!! ~'r)
        ~'r))))
