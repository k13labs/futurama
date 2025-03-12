(ns ^:no-doc futurama.impl
  (:refer-clojure :exclude [realized?])
  (:require
   [clojure.core.async :refer [take!]]
   [clojure.core.async.impl.channels :refer [box]]
   [clojure.core.async.impl.protocols :as core-impl])
  (:import
   [java.util.concurrent
    CompletableFuture
    ExecutorService
    Future]
   [java.util.concurrent.locks Lock]
   [java.util.function BiConsumer Function]))

(def async-state-machine
  "Use a requiring resolve here to dynamically use the correct implementation and maintain backwards compatibility"
  (or (requiring-resolve 'clojure.core.async.impl.ioc-macros/state-machine)
      (requiring-resolve 'clojure.core.async.impl.go/state-machine)))

(deftype JavaFunction [f]
  Function
  (apply [_ a]
    (f a)))

(deftype JavaBiConsumer [f]
  BiConsumer
  (accept [_ a b]
    (f a b)))

(defprotocol AsyncCompletableReader
  (get! [x])
  (completed? [x])
  (on-complete [x f]))

(defprotocol AsyncCompletableWriter
  (complete! [x v]))

(defprotocol AsyncCancellable
  (cancel! [this])
  (cancelled? [this]))

(defn dispatch
  ^Future [^Runnable task ^ExecutorService pool]
  (.submit ^ExecutorService pool ^Runnable task))

(defn async-dispatch-task-handler
  [pool port task]
  (let [^Future fut (dispatch task pool)]
    (when (instance? CompletableFuture port)
      (.exceptionally ^CompletableFuture port
                      ^Function (->JavaFunction
                                 (fn [t]
                                   (cancel! port)
                                   (future-cancel fut)
                                   (throw t)))))
    port))

(defn- async-reader-handler*
  [cb val]
  (if (satisfies? core-impl/ReadPort val)
    (take! val (partial async-reader-handler* cb))
    (cb val)))

(defn async-reader-handler
  [cb]
  (partial async-reader-handler* cb))

(defn async-read-port-take!
  [x handler]
  (let [^Lock handler handler
        commit-handler (fn do-commit []
                         (.lock handler)
                         (let [take-cb (and (core-impl/active? handler)
                                            (core-impl/commit handler))]
                           (.unlock handler)
                           take-cb))]
    (when-let [cb (commit-handler)]
      (cond
        (completed? x)
        (let [r (get! x)]
          (if (satisfies? core-impl/ReadPort r)
            (do
              (take! r (async-reader-handler cb))
              nil)
            (box r)))

        :else
        (do
          (on-complete x cb)
          nil)))))

(defn async-write-port-put!
  [x val handler]
  (when (nil? val)
    (throw (IllegalArgumentException. "Can't put nil on an async thing, close it instead!")))
  (let [^Lock handler handler]
    (if (and (satisfies? AsyncCompletableReader x)
             (completed? x))
      (do
        (.lock handler)
        (when (core-impl/active? handler)
          (core-impl/commit handler))
        (.unlock handler)
        (box false))
      (do
        (.lock handler)
        (when (core-impl/active? handler)
          (core-impl/commit handler))
        (.unlock handler)
        (box
         (complete! x val))))))
