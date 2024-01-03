(ns futurama.deferred
  (:require [clojure.core.async :refer [take!]]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.channels :refer [box]]
            [manifold.deferred :as d])
  (:import [java.util.concurrent.locks Lock]
           [manifold.deferred IDeferred]))

(extend-type IDeferred
  impl/ReadPort
  (take! [d handler]
    (let [^Lock handler handler
          commit-handler (fn do-commit []
                           (.lock handler)
                           (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (when-let [cb (commit-handler)]
        (d/on-realized d
                       (fn [val]
                         (if (satisfies? impl/ReadPort val)
                           (take! val (fn do-read
                                        [val]
                                        (if (satisfies? impl/ReadPort val)
                                          (take! val do-read)
                                          (cb val))))
                           (cb val)))
                       (fn [ex]
                         (cb ex)))
        nil)))
  impl/WritePort
  (put! [d val handler]
    (let [^Lock handler handler]
      (when (nil? val)
        (throw (IllegalArgumentException. "Can't put nil on channel")))
      (if (d/realized? d)
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
             (d/error! d val)
             (d/success! d val)))))))

  impl/Channel
  (close! [d]
    (d/success! d nil))
  (closed? [d]
    (d/realized? d)))
