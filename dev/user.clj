(ns user
  (:require [criterium.core :refer [report-result
                                    quick-benchmark] :as crit]
            [clojure.core.async :refer [go timeout <!]])
  (:import [java.util.concurrent CompletableFuture]))

(comment
  (add-tap #'println)
  (remove-tap #'println)
  (tap> "foobar"))
