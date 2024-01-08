(ns futurama.protocols)

(defprotocol AsyncCancellable
  (cancel [this])
  (cancelled? [this]))
