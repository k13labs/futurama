(ns futurama.core
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as core-impl]
            [clojure.core.async.impl.channels :refer [box]]
            [clojure.core.async.impl.ioc-macros :as ioc]
            [clojure.core.reducers :as r]
            [futurama.impl :as impl]
            [futurama.util :as u]
            [futurama.state :as state]
            [manifold.deferred :as d])
  (:import [clojure.lang Var IDeref IPending IFn IAtom IRef]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.util.concurrent
            CompletableFuture
            CompletionException
            ExecutionException
            ExecutorService
            ForkJoinPool
            Executors
            Future]
           [java.util.concurrent.locks Lock]
           [java.util.function Function BiConsumer]
           [manifold.deferred Deferred]))

(deftype JavaFunction [f]
  Function
  (apply [_ a]
    (f a)))

(deftype JavaBiConsumer [f]
  BiConsumer
  (accept [_ a b]
    (f a b)))

(def ^:const ASYNC_CANCELLED ::cancelled)

(def ^:dynamic *thread-pool* nil)

(defn async-channel-factory
  "Creates a core async channel of size 1"
  ^ManyToManyChannel []
  (async/chan 1))

(defn async-future-factory
  "Creates a new CompletableFuture"
  ^CompletableFuture []
  (CompletableFuture.))

(defn async-promise-factory
  "Creates a new clojure Promise"
  []
  (promise))

(defn async-deferred-factory
  "Creates a new Manifold Deferred"
  ^Deferred []
  (d/deferred))

(def ^:dynamic *async-factory* async-channel-factory)

(defn set-async-factory!
  "alters the root binding of *async-factory* to be equal to `async-factory-fn`"
  [async-factory-fn]
  (alter-var-root #'*async-factory* (constantly async-factory-fn)))

(defmacro with-async-factory
  "temporarily binds *async-factory* to the speficied async-factory-fn and executes body."
  [async-factory-fn & body]
  `(binding [*async-factory* ~async-factory-fn]
     ~@body))

(defmacro with-async-future-factory
  "temporarily binds *async-factory* to the speficied `async-future-factory` and executes body."
  [& body]
  `(with-async-factory async-future-factory
     ~@body))

(defmacro with-async-channel-factory
  "temporarily binds *async-factory* to the speficied `async-channel-factory` and executes body."
  [& body]
  `(with-async-factory async-channel-factory
     ~@body))

(defmacro with-async-promise-factory
  "temporarily binds *async-factory* to the speficied `async-promise-factory` and executes body."
  [& body]
  `(with-async-factory async-promise-factory
     ~@body))

(defmacro with-async-deferred-factory
  "temporarily binds *async-factory* to the speficied `async-deferred-factory` and executes body."
  [& body]
  `(with-async-factory async-deferred-factory
     ~@body))

(defn fixed-threadpool
  "Creates a fixed-threadpool, by default uses the number of available processors."
  ([]
   (let [cpu-count (.. Runtime getRuntime availableProcessors)]
     (fixed-threadpool cpu-count)))
  ([n]
   (Executors/newFixedThreadPool n)))

(defmacro with-pool
  "Utility macro which binds *thread-pool* to the supplied pool and then evaluates the `body`."
  [pool & body]
  `(binding [*thread-pool* ~pool]
     ~@body))

(defn dispatch
  "dispatch the function by submitting it to the `*thread-pool*`"
  ^Future [^Runnable f]
  (let [^ExecutorService pool (or *thread-pool* (ForkJoinPool/commonPool))]
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

(def ^:no-doc rte rethrow-exception)

(defn async-cancellable?
  "Determines if v instance-satisfies? `AsyncCancellable`"
  [v]
  (u/instance-satisfies? impl/AsyncCancellable v))

(defn async-cancel!
  "Cancels the async item."
  [item]
  (let [proto-cancel (when (async-cancellable? item)
                       (impl/cancel! item))
        stack-cancel (state/set-global-state! item ASYNC_CANCELLED)]
    (or proto-cancel stack-cancel false)))

(defn async-cancelled?
  "Checks if the current executing async item or one of its parents or provided item has been cancelled.
  Also checks if the thread has been interrupted and restores the interrupt status."
  ([]
   (or (when (Thread/interrupted)
         (.. (Thread/currentThread)
             (interrupt))
         true)
       (some async-cancelled? state/*items*)
       false))
  ([item]
   (or (when (async-cancellable? item)
         (impl/cancelled? item))
       (= (state/get-global-state item) ASYNC_CANCELLED)
       false)))

(defn async-completed?
  "Checks if the provided `AsyncCompletableReader` instance is completed?"
  [x]
  (when (u/instance-satisfies? impl/AsyncCompletableReader x)
    (impl/completed? x)))

(defn async?
  "returns true if v instance-satisfies? core.async's `ReadPort`"
  ^Boolean [v]
  (u/instance-satisfies? core-impl/ReadPort v))

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
                            ^Function cancel# (JavaFunction.
                                                (fn [~'_]
                                                  (async-cancel! res-fut#)
                                                  (future-cancel fut#)))] ;;; submit the work to the pool and get the FutureTask doing the work
         ;;; if the CompletableFuture returns exceptionally
         ;;; then cancel the Future which is currently doing the work
                        (.exceptionally res-fut# cancel#)
                        res-fut#))))

(extend-type Future
  core-impl/ReadPort
  (take! [x handler]
    (impl/async-read-port-take! x handler))

  impl/AsyncCancellable
  (cancel! [fut]
    (future-cancel fut))
  (cancelled? [fut]
    (future-cancelled? fut))

  impl/AsyncCompletableReader
  (get! [fut]
    (try
      (.get ^Future fut)
      (catch Throwable e
        e)))
  (completed? [fut]
    (.isDone ^Future fut))
  (on-complete [fut f]
    (async/thread
      (let [r (impl/get! fut)]
        (f r))))

  core-impl/Channel
  (close! [x]
    (when-not (async-completed? x)
      (async-cancel! x)))
  (closed? [x]
    (async-completed? x)))

(extend-protocol impl/AsyncCompletableWriter
  IFn
  (complete! [f v]
    (boolean (f v)))

  IAtom
  (complete! [a v]
    (reset! a v)
    true)

  IRef
  (complete! [a v]
    (dosync
     (ref-set a v))
    true))

(extend-protocol core-impl/WritePort
  IFn
  (put! [x val handler]
    (impl/async-write-port-put! x val handler))

  IAtom
  (put! [x val handler]
    (impl/async-write-port-put! x val handler))

  IRef
  (put! [x val handler]
    (impl/async-write-port-put! x val handler)))

(extend-type IDeref
  core-impl/ReadPort
  (take! [x handler]
    (impl/async-read-port-take! x handler))

  impl/AsyncCompletableReader
  (get! [ref]
    (try
      (deref ref)
      (catch Throwable e
        e)))
  (completed? [ref]
    (if (instance? IPending ref)
      (realized? ref)
      true))
  (on-complete [ref f]
    (async/thread
      (let [r (impl/get! ref)]
        (f r))))

  core-impl/Channel
  (close! [ref]
    (when (instance? IFn ref)
      (ref nil)))
  (closed? [ref]
    (impl/completed? ref)))

(extend-type CompletableFuture
  core-impl/ReadPort
  (take! [x handler]
    (impl/async-read-port-take! x handler))

  core-impl/WritePort
  (put! [x val handler]
    (impl/async-write-port-put! x val handler))

  impl/AsyncCancellable
  (cancel! [this]
    (future-cancel this))
  (cancelled? [this]
    (future-cancelled? this))

  impl/AsyncCompletableReader
  (get! [fut]
    (try
      (.get ^CompletableFuture fut)
      (catch Throwable e
        e)))
  (completed? [fut]
    (.isDone ^CompletableFuture fut))
  (on-complete [fut f]
    (let [^BiConsumer invoke-cb (JavaBiConsumer.
                                  (fn [val ex]
                                    (f (or ex val))))]
      (.whenComplete ^CompletableFuture fut ^BiConsumer invoke-cb)))

  impl/AsyncCompletableWriter
  (complete! [fut v]
    (if (instance? Throwable v)
      (.completeExceptionally ^CompletableFuture fut ^Throwable v)
      (.complete ^CompletableFuture fut v)))

  core-impl/Channel
  (close! [fut]
    (.complete ^CompletableFuture fut nil))
  (closed? [fut]
    (.isDone ^CompletableFuture fut)))

(extend-type Deferred
  core-impl/ReadPort
  (take! [x handler]
    (impl/async-read-port-take! x handler))

  core-impl/WritePort
  (put! [x val handler]
    (impl/async-write-port-put! x val handler))

  impl/AsyncCompletableReader
  (get! [dfd]
    (try
      (deref dfd)
      (catch Throwable e
        e)))
  (completed? [dfd]
    (d/realized? dfd))
  (on-complete [dfd f]
    (d/on-realized dfd f f))

  impl/AsyncCompletableWriter
  (complete! [dfd v]
    (if (instance? Throwable v)
      (d/error! dfd v)
      (d/success! dfd v)))

  core-impl/Channel
  (close! [dfd]
    (d/success! dfd nil))
  (closed? [dfd]
    (d/realized? dfd)))

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

  Returns the provided port which will receive the result of the body when
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
                            (let [^Function cancel# (JavaFunction.
                                                      (fn [~'_]
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

  Returns an instance of the default *async-factory* which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [& body]
  `(async!
    (*async-factory*)
    ~@body))

(defmacro async-channel
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
    (async-channel-factory)
    ~@body))

(defmacro async-future
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to !<!, <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread.
  Upon completion of the operation, the body will be resumed.

  async blocks should not (either directly or indirectly) perform operations
  that may block indefinitely. Doing so risks depleting the fixed pool of
  go block threads, causing all go block processing to stop. This includes
  core.async blocking ops (those ending in !!) and other blocking IO.

  Returns a CompletableFuture which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [& body]
  `(async!
    (async-future-factory)
    ~@body))

(defmacro async-deferred
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to !<!, <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread.
  Upon completion of the operation, the body will be resumed.

  async blocks should not (either directly or indirectly) perform operations
  that may block indefinitely. Doing so risks depleting the fixed pool of
  go block threads, causing all go block processing to stop. This includes
  core.async blocking ops (those ending in !!) and other blocking IO.

  Returns a Deferred which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [& body]
  `(async!
    (async-deferred-factory)
    ~@body))

(defmacro async-promise
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to !<!, <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread.
  Upon completion of the operation, the body will be resumed.

  async blocks should not (either directly or indirectly) perform operations
  that may block indefinitely. Doing so risks depleting the fixed pool of
  go block threads, causing all go block processing to stop. This includes
  core.async blocking ops (those ending in !!) and other blocking IO.

  Returns a clojure Promise which will receive the result of the body when
  completed; the pool used can be specified via `*thread-pool*` binding."
  [& body]
  `(async!
    (async-promise-factory)
    ~@body))

(deftype AsyncReader [val]
  core-impl/ReadPort
  (take! [_ handler]
    (let [^Lock handler handler
          commit-handler (fn do-commit []
                           (.lock handler)
                           (let [take-cb (and (core-impl/active? handler) (core-impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (when-let [cb (commit-handler)]
        (if (async? val)
          (do
            (async/take! val (u/async-reader-handler cb))
            nil)
          (box val))))))

(defn ->async-reader
  "Creates an AsyncReader to read anything via `take!`"
  [x]
  (AsyncReader. x))

(def ^:no-doc rdr ->async-reader)

(defmacro <!
  "An improved macro version of <!, which also rethrows exceptions returned over the channel.
  Must be called INSIDE a (go ...) or (async ...) block.
  - Will return nil if closed.
  - Will park if nothing is available.
  - Will throw if an Exception is taken from port.
  - Will return the raw value if it is not a ReadPort"
  [v]
  `(rte (async/<! ~v)))

(defmacro <!*
  "Like <! but works with collections of async values"
  [coll]
  `(loop [~'icoll (not-empty ~coll)
          ~'ocoll []]
     (if (nil? ~'icoll)
       ~'ocoll
       (recur (next ~'icoll) (conj ~'ocoll (<! (first ~'icoll)))))))

(defmacro <!!
  "An improved macro version of <!!, which also rethrows exceptions returned over the channel.
  Must be called OUTSIDE a (go ...) or (async ...) block.
  - Will return nil if closed.
  - Will block if nothing is available.
  - Will throw if a Exception is taken from port.
  - Will return the raw value if it is not a ReadPort"
  [v]
  `(rte (async/<!! ~v)))

(defmacro !<!
  "An improved macro version of <!, which also rethrows exceptions returned over the channel.
  Must be called INSIDE a (go ...) or (async ...) block.
  - Will return nil if closed.
  - Will park if nothing is available.
  - Will throw if an Exception is taken from port.
  - Will return the raw value if it is not a ReadPort
  - Will fully read through any async result returned"
  [v]
  `(<! (rdr ~v)))

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
  - Will return the raw value if it is not a ReadPort
  - Will fully read through any async result returned"
  [v]
  `(<!! (rdr ~v)))

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
  [f coll & coll-seq]
  (async-for
   [params (let [colls (!<!* (cons coll coll-seq))]
             (->> (apply interleave colls)
                  (partition (count colls))))]
   (apply f params)))

(defn- async-do-reduce*
  "internal reducer function to work with async args"
  [f & args]
  (async
   (apply f (!<!* args))))

(defn async-reduce
  "Like core/reduce except, when init is not provided, (f) is used, and async results are read with !<!."
  ([f coll]
   (async-reduce f (f) coll))
  ([f init coll]
   (async
    (r/reduce (partial async-do-reduce* f)
              (!<! init)
              (!<! coll)))))

(defn- async-some-call*
  "internal async-some fn handler to handle async args and result"
  [result pred item]
  (async
   (when-let [value (!<! (pred (!<! item)))]
     (async/put! result value))))

(defn async-some
  "Concurrently executes (pred x) and returns the first returned
  logical true value of (pred x) for any x in coll, else nil.
  One common idiom is to use a set as pred, for example
  this will return :fred if :fred is in the sequence, otherwise nil:
  (some #{:fred} coll). Please beware that unlike `clojure.core/some`
  this function returns the first asynchronous result that completes
  and evaluates to logical true, but not necessarily the first one
  in sequential order."
  [pred coll]
  (let [result (async/chan 1)]
    (async!
     result
     (let [results (doall
                    (for [item (!<! coll)]
                      (async-some-call* result pred item)))]
       (loop [results (seq results)]
         (cond
           (core-impl/closed? result)
           nil

           (nil? results)
           (async/close! result)

           :else
           (do
             (!<! (first results))
             (recur (next results)))))))
    result))

(defn- async-every-call*
  "internal async-every? fn handler to handle async args and result"
  [result pred item]
  (async
   (when-not (!<! (pred (!<! item)))
     (async/put! result false))))

(defn async-every?
  "Returns true if (pred x) is logical true for every x in coll, else false."
  [pred coll]
  (let [result (async/chan 1)]
    (async!
     result
     (let [results (for [item coll]
                     (async-every-call* result pred item))]
       (loop [results (seq results)]
         (cond
           (core-impl/closed? result)
           nil

           (nil? results)
           (async/put! result true)

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
  [innerf r x]
  (async
   (conj (!<! r) (!<! (innerf (!<! x))))))

(defn async-walk
  "Traverses form, an arbitrary data structure.  inner and outer are
  functions.  Applies inner to each element of form, building up a
  data structure of the same type, then applies outer to the result.
  Recognizes all Clojure data structures. Consumes seqs as with doall."
  [inner outer form]
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
  [f form]
  (async-walk (partial async-postwalk f) f form))

(defn async-prewalk
  "Like postwalk, but does pre-order traversal."
  [f form]
  (async-walk (partial async-prewalk f) identity (f form)))
