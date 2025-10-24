(ns ^:no-doc futurama.state
  (:import [com.github.benmanes.caffeine.cache Caffeine Cache]))

(defonce ^:private GLOBAL_CANCEL_STATE
  (delay
    (.. (Caffeine/newBuilder)
        ;;; Use weak keys to allow removal of keys and values when no longer referenced elsewhere
        (weakKeys)
        ;;; Cancellation interruption is best effort, arbitrary size based on CPU count, should be enough for most use cases
        (maximumSize (-> (Runtime/getRuntime)
                         (.availableProcessors)
                         (* 1000)))
        (build))))

(def ^:dynamic *items* [])

(defmacro push-item
  "Pushes the async item into the `*items*` vector"
  [item & body]
  `(binding [*items* (conj *items* ~item)]
     ~@body))

(defn set-cancel-state!
  "Sets the cancellation state for the given async item"
  [key val]
  (.put ^Cache @GLOBAL_CANCEL_STATE key val))

(defn get-cancel-state
  "Gets the cancellation state for the given async item"
  [key]
  (.getIfPresent ^Cache @GLOBAL_CANCEL_STATE key))
