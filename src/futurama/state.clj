(ns futurama.state
  (:import [java.util Map WeakHashMap]))

(defonce ^{:doc "Internal global state mananged using a Synchronized WeakHashMap"
           :private true}
  GLOBAL_STATE
  (WeakHashMap.))

(def ^:dynamic *async-state* {})

(defn ^:no-doc put-global-state*
  [key val]
  (locking GLOBAL_STATE
    (.put ^Map GLOBAL_STATE key val)))

(defn ^:no-doc get-global-state*
  [key]
  (locking GLOBAL_STATE
    (.get ^Map GLOBAL_STATE key)))

(defmacro push-item
  "Pushes the async item into the `*async-state*` with the default state of: `{}`"
  [item & body]
  `(let [key# ~item
         val# (atom {})]
     (binding [*async-state* (conj *async-state* [key# val#])]
       (put-global-state* key# val#)
       ~@body)))

(defn- get-state
  [item]
  (or (get *async-state* item)
      (get-global-state* item)))

(defn set-value!
  "Changes the state of an item key in the state map."
  [item key value]
  (when-let [state-atom (get-state item)]
    (-> (swap! state-atom conj [key value])
        (get key))))

(defn get-value
  "Gets the value of a state key in item's state map"
  [item key]
  (some-> (get-state item) deref (get key)))

(defn get-dynamic-items
  "Lists all the items in the dynamic `*async-state*` map"
  []
  (keys *async-state*))
