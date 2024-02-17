(ns ^:no-doc futurama.state
  (:import [java.util Map WeakHashMap]
           [java.util.concurrent.locks Lock ReadWriteLock ReentrantReadWriteLock]))

(defonce ^:private ^ReadWriteLock GLOBAL_STATE_LOCK
  (ReentrantReadWriteLock.))

(defonce ^:private GLOBAL_STATE
  (WeakHashMap.))

(def ^:dynamic *items* [])

(defmacro push-item
  "Pushes the async item into the `*items*` vector"
  [item & body]
  `(let [item# ~item]
     (binding [*items* (conj *items* item#)]
       ~@body)))

(defn ^:no-doc set-global-state!
  "Sets the value of the item's global state"
  [key val]
  (let [^Lock lock (.writeLock GLOBAL_STATE_LOCK)]
    (.lock lock)
    (try
      (.put ^Map GLOBAL_STATE key val)
      (finally
        (.unlock lock)))))

(defn ^:no-doc get-global-state
  "Gets the value of the item's global state"
  [key]
  (let [^Lock lock (.readLock GLOBAL_STATE_LOCK)]
    (.lock lock)
    (try
      (.get ^Map GLOBAL_STATE key)
      (finally
        (.unlock lock)))))
