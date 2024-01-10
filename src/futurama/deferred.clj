(ns ^:no-doc futurama.deferred
  (:require [clojure.core.async.impl.protocols :as impl]
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
        (d/on-realized d cb cb)
        nil)))
  impl/WritePort
  (put! [d val handler]
    (when (nil? val)
      (throw (IllegalArgumentException. "Can't put nil on an async thing, close it instead!")))
    (let [^Lock handler handler]
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
           (if (instance? Throwable val)
             (d/error! d val)
             (d/success! d val)))))))

  impl/Channel
  (close! [d]
    (d/success! d nil))
  (closed? [d]
    (d/realized? d)))
