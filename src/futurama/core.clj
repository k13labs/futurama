(ns futurama.core
  (:require [clojure.core.async :refer [<! <!! put! take! close! thread] :as async]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.channels :refer [box]]
            [clojure.core.async.impl.ioc-macros :as ioc]
            [clojure.core.reducers :as r]
            [futurama.protocols :as proto]
            [futurama.util :as u]
            [futurama.state :as state]
            [futurama.deferred])
  (:import [clojure.lang Var IDeref IFn]
           [java.util.concurrent
            CompletableFuture
            CompletionException
            ExecutionException
            ExecutorService
            Executors
            Future]
           [java.util.concurrent.locks Lock]
           [java.util.function Function BiConsumer]))

(def ^:dynamic *thread-pool* nil)

(defn fixed-threadpool
  "Creates a fixed-threadpool, by default uses the number of available processors."
  ([]
   (let [cpu-count (.. Runtime getRuntime availableProcessors)]
     (fixed-threadpool cpu-count)))
  ([n]
   (Executors/newFixedThreadPool n)))

(defonce
  ^{:doc "Default ExecutorService used, corresponds with a FixedThreadPool as many threads as available processors."}
  default-pool
  (delay (fixed-threadpool)))

(defmacro with-pool
  "Utility macro which binds *thread-pool* to the supplied pool and then evaluates the `body`."
  [pool & body]
  `(binding [*thread-pool* ~pool]
     ~@body))

(defn dispatch
  "dispatch the function by submitting it to the `*thread-pool*`"
  ^Future [^Runnable f]
  (let [^ExecutorService pool (or *thread-pool* @default-pool)]
    (.submit ^ExecutorService pool ^Runnable f)))

(defn unwrap-exception
  "unwraps an ExecutionException or CompletionException via ex-cause until the root exception is returned"
  ^Throwable [^Throwable ex]
  (if-let [ce (and (or (instance? ExecutionException ex)
                       (instance? CompletionException ex))
                   (ex-cause ex))]
    ce
    ex))

(defn rethrow-exception
  "throw v if it is an Exception"
  [v]
  (if (instance? Throwable v)
    (throw (unwrap-exception v))
    v))

(defn async-cancel!
  "Cancels the async item."
  [item]
  (let [proto-cancel (when (u/instance-satisfies? proto/AsyncCancellable item)
                       (proto/cancel item))
        stack-cancel (state/set-value! item :cancelled true)]
    (or proto-cancel stack-cancel false)))

(defn async-cancelled?
  "Checks if the current executing async item or one of its parents or provided item has been cancelled.
  Also checks if the thread has been interrupted and restores the interrupt status."
  ([]
   (or (when (Thread/interrupted)
         (.. (Thread/currentThread)
             (interrupt))
         true)
       (some async-cancelled? (state/get-dynamic-items))
       false))
  ([item]
   (or (proto/cancelled? item)
       (state/get-value item :cancelled)
       false)))

(defn async?
  "returns true if v instance-satisfies? core.async's `ReadPort`"
  ^Boolean [v]
  (u/instance-satisfies? impl/ReadPort v))

(defmacro completable-future
  "Asynchronously invokes the body inside a completable future, preserves the current thread binding frame,
  using by default the `ForkJoinPool/commonPool`, the pool used can be specified via `*thread-pool*` binding."
  ^CompletableFuture [& body]
  `(let [^CompletableFuture res-fut# (CompletableFuture.)] ;;; this is the CompletableFuture being returned
     (state/push-item res-fut#
                      (let [binding-frame# (Var/cloneThreadBindingFrame) ;;; capture the thread local binding frame before start
                            ^Runnable fbody# (fn do-complete#
                                               []
                                               (let [thread-frame# (Var/getThreadBindingFrame)] ;;; get the Thread's binding frame
                                                 (Var/resetThreadBindingFrame binding-frame#) ;;; set the Clojure binding frame captured
                                                 (try
                                                   (.complete res-fut# (do ~@body)) ;;; send the result of evaluating the body to the CompletableFuture
                                                   (catch Throwable ~'e
                                      ;;; if we catch an exception we send it to the CompletableFuture
                                                     (.completeExceptionally res-fut# (unwrap-exception ~'e)))
                                                   (finally
                                                     (Var/resetThreadBindingFrame thread-frame#))))) ;;; restore the original Thread's binding frame
                            ^Future fut# (dispatch fbody#)
                            ^Function cancel# (reify Function
                                                (apply [~'_ ~'_]
                                                  (async-cancel! res-fut#)
                                                  (future-cancel fut#)))] ;;; submit the work to the pool and get the FutureTask doing the work
         ;;; if the CompletableFuture returns exceptionally
         ;;; then cancel the Future which is currently doing the work
                        (.exceptionally res-fut# cancel#)
                        res-fut#))))

(extend-type Future
  proto/AsyncCancellable
  (cancel [this]
    (future-cancel this))
  (cancelled? [this]
    (future-cancelled? this))

  impl/ReadPort
  (take! [fut handler]
    (let [^Future fut fut
          ^Lock handler handler
          commit-handler (fn do-commit []
                           (.lock handler)
                           (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (when-let [cb (commit-handler)]
        (cond
          (realized? fut)
          (let [val (try
                      (.get ^Future fut)
                      (catch Throwable e
                        (unwrap-exception e)))]
            (if (u/instance-satisfies? impl/ReadPort val)
              (do
                (take! val (fn do-read
                             [val]
                             (if (u/instance-satisfies? impl/ReadPort val)
                               (take! val do-read)
                               (cb val))))
                nil)
              (box val)))

          :else
          (do
            (thread
              (let [[val ex]
                    (try
                      [(.get ^Future fut) nil]
                      (catch Throwable e
                        [nil e]))]
                (cond
                  (u/instance-satisfies? impl/ReadPort val)
                  (take! val (fn do-read
                               [val]
                               (if (u/instance-satisfies? impl/ReadPort val)
                                 (take! val do-read)
                                 (cb val))))

                  (some? val)
                  (cb val)

                  (some? ex)
                  (cb ex)

                  :else
                  (cb nil))))
            nil)))))

  impl/Channel
  (close! [fut]
    (when-not (realized? fut)
      (future-cancel ^Future fut)))
  (closed? [fut]
    (realized? ^Future fut)))

(extend-type IDeref
  impl/ReadPort
  (take! [ref handler]
    (let [^IDeref ref ref
          ^Lock handler handler
          commit-handler (fn do-commit []
                           (.lock handler)
                           (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (when-let [cb (commit-handler)]
        (cond
          (realized? ref)
          (let [val (try
                      (deref ref)
                      (catch Throwable e
                        (unwrap-exception e)))]
            (if (u/instance-satisfies? impl/ReadPort val)
              (do
                (take! val (fn do-read
                             [val]
                             (if (u/instance-satisfies? impl/ReadPort val)
                               (take! val do-read)
                               (cb val))))
                nil)
              (box val)))

          :else
          (do
            (thread
              (let [[val ex]
                    (try
                      [(deref ref) nil]
                      (catch Throwable e
                        [nil e]))]
                (cond
                  (u/instance-satisfies? impl/ReadPort val)
                  (take! val (fn do-read
                               [val]
                               (if (u/instance-satisfies? impl/ReadPort val)
                                 (take! val do-read)
                                 (cb val))))

                  (some? val)
                  (cb val)

                  (some? ex)
                  (cb ex)

                  :else
                  (cb nil))))
            nil)))))

  impl/Channel
  (close! [ref]
    (when (instance? IFn ref)
      (ref nil)))
  (closed? [ref]
    (realized? ref)))

(extend-type CompletableFuture
  proto/AsyncCancellable
  (cancel [this]
    (future-cancel this))
  (cancelled? [this]
    (future-cancelled? this))

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
                      (catch Throwable e
                        (unwrap-exception e)))]
            (if (u/instance-satisfies? impl/ReadPort val)
              (do
                (take! val (fn do-read
                             [val]
                             (if (u/instance-satisfies? impl/ReadPort val)
                               (take! val do-read)
                               (cb val))))
                nil)
              (box val)))
          (do
            (let [^BiConsumer invoke-cb (reify BiConsumer
                                         (accept [_ val ex]
                                           (cond
                                             (u/instance-satisfies? impl/ReadPort val)
                                             (take! val (fn do-read
                                                          [val]
                                                          (if (u/instance-satisfies? impl/ReadPort val)
                                                            (take! val do-read)
                                                            (cb val))))

                                             (some? val)
                                             (cb val)

                                             (some? ex)
                                             (cb ex)

                                             :else
                                             (cb nil))))]
              (.whenComplete ^CompletableFuture fut ^BiConsumer invoke-cb))
            nil)))))
  impl/WritePort
  (put! [fut val handler]
    (let [^CompletableFuture fut fut
          ^Lock handler handler]
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
           (if (instance? Throwable val)
             (.completeExceptionally fut ^Throwable val)
             (.complete fut val)))))))

  impl/Channel
  (close! [fut]
    (.complete ^CompletableFuture fut nil))
  (closed? [fut]
    (.isDone ^CompletableFuture fut)))

(defmacro async!
  "Asynchronously executes the body, returning `port` immediately to the
  calling thread. Additionally, any visible calls to !<!, <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread.
  Upon completion of the operation, the body will be resumed.

  The success or failure output will be put! in `port`.

  async blocks should not (either directly or indirectly) perform operations
  that may block indefinitely. Doing so risks depleting the fixed pool of
  go block threads, causing all go block processing to stop. This includes
  core.async blocking ops (those ending in !!) and other blocking IO.

  Returns a channel which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [port & body]
  (let [crossing-env (zipmap (keys &env) (repeatedly gensym))]
    `(let [c# ~port]
       (state/push-item c#
                        (let [captured-bindings# (Var/getThreadBindingFrame)
                              ^Runnable task# (^:once fn* []
                                                          (let [~@(mapcat (fn [[l sym]] [sym `(^:once fn* [] ~(vary-meta l dissoc :tag))]) crossing-env)
                                                                f# ~(ioc/state-machine `(try
                                                                                          ~@body
                                                                                          (catch Throwable ~'e
                                                                                            (unwrap-exception ~'e))) 1 [crossing-env &env] ioc/async-custom-terminators)
                                                                state# (-> (f#)
                                                                           (ioc/aset-all! ioc/USER-START-IDX c#
                                                                                          ioc/BINDINGS-IDX captured-bindings#))]
                                                            (ioc/run-state-machine-wrapped state#)))
                              ^Future fut# (dispatch task#)]
                          (when (instance? CompletableFuture c#)
                            (let [^Function cancel# (reify Function
                                                      (apply [~'_ ~'_]
                                                        (async-cancel! c#)
                                                        (future-cancel fut#)))]
                              (.exceptionally ^CompletableFuture c#
                                              ^Function cancel#)))
                          c#)))))

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
  `(async!
    (CompletableFuture.)
    ~@body))

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
      (if (u/instance-satisfies? impl/ReadPort ~'r)
        (<! ~'r)
        ~'r))))

(defmacro !<!*
  "Like !<! but works with collections of async values"
  [coll]
  `(loop [~'icoll (not-empty ~coll)
          ~'ocoll []]
     (if (nil? ~'icoll)
       ~'ocoll
       (recur (next ~'icoll) (conj ~'ocoll (!<! (first ~'icoll)))))))

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
      (if (u/instance-satisfies? impl/ReadPort ~'r)
        (<!! ~'r)
        ~'r))))

(defmacro async-for
  "works like a for macro, but supports core.async operations.
  bindings are initially bound and collected, then the data is iterated
  over using a loop and the output collected; note that only the body of
  the for can contain `<!` and `!<!` calls. This is implicitly wrapped in
  an `async` block."
  [bindings & body]
  (let [pairs (partition 2 bindings)
        bvars (loop [[pair & more] pairs
                     vars []]
                (if (nil? pair)
                  (vec (set vars))
                  (let [[pk pv] pair
                        [vars pairs]
                        (if (= pk :let)
                          [vars
                           (concat more (partition 2 pv))]
                          [(reduce
                            (fn flatten-reducer
                              [vs v]
                              (cond
                                (symbol? v)
                                (conj vs v)

                                (coll? v)
                                (into vs (reduce flatten-reducer [] v))

                                :else
                                vs))
                            vars
                            [pk])
                           more])]
                    (recur pairs vars))))]
    `(async
      (let [args# (for [~@bindings]
                    [~@bvars])
            output#
            (loop [results# []
                   args# (seq args#)]
              (if (nil? args#)
                results#
                (recur (conj results#
                             (let [[~@bvars] (first args#)]
                               ~@body))
                       (next args#))))]
        (!<!* output#)))))

(defn async-map
  "Asynchronously returns the result of applying f to
  the set of first items of each coll, followed by applying f to the
  set of second items in each coll, until any one of the colls is
  exhausted.  Any remaining items in other colls are ignored. Function
  f should accept number-of-colls arguments."
  ^CompletableFuture [f coll & coll-seq]
  (async-for
   [params (let [colls (!<!* (cons coll coll-seq))]
             (->> (apply interleave colls)
                  (partition (count colls))))]
   (apply f params)))

(defn- async-do-reduce*
  "internal reducer function to work with async args"
  ^CompletableFuture [f v x]
  (async
   (f (!<! v) (!<! x))))

(defn async-reduce
  "Like core/reduce except, when init is not provided, (f) is used, and async results are read with !<!."
  (^CompletableFuture [f coll]
   (async-reduce f (f) coll))
  (^CompletableFuture [f init coll]
   (async
    (r/reduce (partial async-do-reduce* f)
              (!<! init)
              (!<! coll)))))

(defn- async-some-call*
  "internal async-some fn handler to handle async args and result"
  [^CompletableFuture result pred item]
  (async
   (when-let [value (!<! (pred (!<! item)))]
     (put! result value))))

(defn async-some
  "Concurrently executes (pred x) and returns the first returned
  logical true value of (pred x) for any x in coll, else nil.
  One common idiom is to use a set as pred, for example
  this will return :fred if :fred is in the sequence, otherwise nil:
  (some #{:fred} coll). Please beware that unlike `clojure.core/some`
  this function returns the first asynchronous result that completes
  and evaluates to logical true, but not necessarily the first one
  in sequential order."
  ^CompletableFuture [pred coll]
  (let [^CompletableFuture result (CompletableFuture.)]
    (async!
     result
     (let [results (doall
                    (for [item (!<! coll)]
                      (async-some-call* result pred item)))]
       (loop [results (seq results)]
         (cond
           (impl/closed? result)
           nil

           (nil? results)
           (close! result)

           :else
           (do
             (!<! (first results))
             (recur (next results)))))))
    result))

(defn- async-every-call*
  "internal async-every? fn handler to handle async args and result"
  ^CompletableFuture [^CompletableFuture result pred item]
  (async
   (when-not (!<! (pred (!<! item)))
     (put! result false))))

(defn async-every?
  "Returns true if (pred x) is logical true for every x in coll, else false."
  ^CompletableFuture [pred coll]
  (let [^CompletableFuture result (CompletableFuture.)]
    (async!
     result
     (let [results (for [item coll]
                     (async-every-call* result pred item))]
       (loop [results (seq results)]
         (cond
           (impl/closed? result)
           nil

           (nil? results)
           (put! result true)

           :else
           (do
             (!<! (first results))
             (recur (next results)))))))
    result))

(defmacro async->
  "Threads the expr through the forms which can return async result.
  Inserts v as the second item in the first form, making a list of it
  if it is not a list already. If there are more forms, inserts the
  first form as the second item in second form, etc."
  [v & call-seq]
  (let [call-parsed-seq (for [call call-seq]
                          (if (list? call)
                            (vec call)
                            (vector call)))]
    `(async
      (loop [v# ~v
             [call# & more#] [~@call-parsed-seq]]
        (if-not call#
          v#
          (let [arg# (!<! v#)
                [f# & args#] call#
                res# (!<! (apply f# arg# args#))]
            (recur res# more#)))))))

(defmacro async->>
  "Threads the expr through the forms which can return async result.
  Inserts x as the last item in the first form, making a list of it
  if it is not a list already. If there are more forms, inserts the
  first form as the last item in second form, etc."
  [v & call-seq]
  (let [call-parsed-seq (for [call call-seq]
                          (if (list? call)
                            (vec call)
                            (vector call)))]
    `(async
      (loop [v# ~v
             [call# & more#] [~@call-parsed-seq]]
        (if-not call#
          v#
          (let [arg# (!<! v#)
                [f# & args#] call#
                r# (!<! (apply f# (concat args# [arg#])))]
            (recur r# more#)))))))

(defn- async-walk-record*
  "internal async walk adapter to walk a record"
  ^CompletableFuture [innerf r x]
  (async
   (conj (!<! r) (!<! (innerf (!<! x))))))

(defn async-walk
  "Traverses form, an arbitrary data structure.  inner and outer are
  functions.  Applies inner to each element of form, building up a
  data structure of the same type, then applies outer to the result.
  Recognizes all Clojure data structures. Consumes seqs as with doall."
  ^CompletableFuture [inner outer form]
  (async
   (let [form (!<! form)]
     (cond
       (list? form)
       (!<! (outer (apply list (!<! (async-map inner form)))))

       (instance? clojure.lang.IMapEntry form)
       (outer (clojure.lang.MapEntry/create (!<! (inner (!<! (key form))))
                                            (!<! (inner (!<! (val form))))))

       (seq? form)
       (outer (!<! (async-map inner form)))

       (instance? clojure.lang.IRecord form)
       (outer (!<! (reduce (partial async-walk-record* inner)
                           form form)))

       (coll? form)
       (outer (into (empty form) (!<! (async-map inner form))))

       :else
       (outer form)))))

(defn async-postwalk
  "Performs a depth-first, post-order traversal of form.  Calls f on
  each sub-form, uses f's return value in place of the original.
  Recognizes all Clojure data structures. Consumes seqs as with doall."
  ^CompletableFuture [f form]
  (async-walk (partial async-postwalk f) f form))

(defn async-prewalk
  "Like postwalk, but does pre-order traversal."
  ^CompletableFuture [f form]
  (async-walk (partial async-prewalk f) identity (f form)))
