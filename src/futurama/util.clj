(ns futurama.util
  (:refer-clojure :exclude [satisfies? find-protocol-impl]))

(defn- super-chain [^Class c]
  (when c
    (cons c (super-chain (.getSuperclass c)))))

(defn- pref
  ([] nil)
  ([a] a)
  ([^Class a ^Class b]
   (if (.isAssignableFrom a b) b a)))

(def ^:private find-protocol-impl-from-class
  (memoize
   (fn [protocol ^Class c]
     (let [impl #(get (:impls protocol) %)]
       (or (impl c)
           (and c (or (first (remove nil? (map impl (butlast (super-chain c)))))
                      (when-let [t (reduce pref (filter impl (disj (supers c) Object)))]
                        (impl t))
                      (impl Object))))))))

(defn- find-protocol-impl-from-object
  [protocol x]
  (when (instance? (:on-interface protocol) x)
    x))

(defn find-protocol-impl
  [protocol x]
  (or (find-protocol-impl-from-object protocol x)
      (find-protocol-impl-from-class protocol (class x))))

(defn satisfies?
  [protocol x]
  (boolean (find-protocol-impl protocol x)))
