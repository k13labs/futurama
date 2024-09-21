(ns futurama.main
  (:require
   [clojure.core.async :refer [<! go timeout]]
   [futurama.core :refer [!<! !<!! async completable-future]])
  (:gen-class)
  (:import
   [java.util.concurrent CompletableFuture]))

(defn- format-thing-async
  [thing]
  (go
    (format "thing: %s" thing)))

(defn -main
  "this is a test to compile a bunch of async code into native code, pretty nasty"
  [& args]
  (println
   "async result:"
   (!<!!
    (async
     (<! (timeout 50))
     (!<!
      (CompletableFuture/completedFuture
       (completable-future
        (delay
          (future
            (let [p (promise)]
              (deliver p
                       (CompletableFuture/completedFuture
                        (format-thing-async (vec args))))
              p))))))))))
