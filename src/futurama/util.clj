(ns ^:no-doc futurama.util
  (:refer-clojure :exclude [satisfies? find-protocol-impl])
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl])
  (:import [java.util.concurrent.locks Lock]))

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

(defn class-satisfies?
  "Checks if `protocol` is satisfied by the `class` just like Clojure's `satisfies?` does internally, but this version
  is memoized and thus faster if called multiple times."
  [protocol ^Class c]
  (boolean (find-protocol-impl-from-class protocol c)))

(defn instance-satisfies?
  "Like Clojure's `satisfies?` but calls `class-satisfies?` when the protocol is not implemented directly."
  [protocol x]
  (or (boolean (find-protocol-impl-from-object protocol x))
      (class-satisfies? protocol (class x))))

(defn- async-reader-handler*
  [cb val]
  (if (instance-satisfies? impl/ReadPort val)
    (async/take! val (partial async-reader-handler* cb))
    (cb val)))

(defn async-reader-handler
  [cb]
  (partial async-reader-handler* cb))
