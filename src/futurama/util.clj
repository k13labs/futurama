(ns ^:no-doc futurama.util
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]))

(defn- async-reader-handler*
  [cb val]
  (if (satisfies? impl/ReadPort val)
    (async/take! val (partial async-reader-handler* cb))
    (cb val)))

(defn async-reader-handler
  [cb]
  (partial async-reader-handler* cb))
