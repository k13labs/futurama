(ns futurama.protocols
  (:refer-clojure :exclude [realized?]))

(defprotocol AsyncCancellable
  (cancel [this])
  (cancelled? [this]))

(defprotocol AsyncPending
  (realized? [this]))
