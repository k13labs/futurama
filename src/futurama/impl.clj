(ns ^:no-doc futurama.impl
  (:refer-clojure :exclude [realized?])
  (:require [clojure.core.async.impl.protocols :as core-impl]
            [clojure.core.async.impl.channels :refer [box]]
            [clojure.core.async :refer [take!]]
            [futurama.util :as u])
  (:import
   [java.util.concurrent.locks Lock]))

(defprotocol AsyncCompletableReader
  (get! [x])
  (completed? [x])
  (on-complete [x f]))

(defprotocol AsyncCompletableWriter
  (complete! [x v]))

(defprotocol AsyncCancellable
  (cancel! [this])
  (cancelled? [this]))

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
          (if (u/instance-satisfies? core-impl/ReadPort r)
            (do
              (take! r (u/async-reader-handler cb))
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
    (if (and (u/instance-satisfies? AsyncCompletableReader x)
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
        (if (u/instance-satisfies? core-impl/ReadPort val)
          (do
            (take! val (u/async-reader-handler (partial complete! x)))
            (box false))
          (box
           (complete! x val)))))))
