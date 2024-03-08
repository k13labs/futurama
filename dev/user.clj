(ns user
  (:require [clojure.set :as set]
            [clojure.core.async :as async]
            [futurama.core :refer [async async! !<! !<!!
                                   async-cancel!
                                   async-completed?
                                   async-cancelled?]]))

(defn branch-fn
  [all-num-set max-num-sum]
  "create a fn to branch:
  given a tuple containing a sorted set of numbers and their sum,
  branch if the sum is less than or equal to our maximum number sum,
  and if is a difference between the set of all the numbers and the
  each number set so far in this branch."
  (fn is-branch?
    [[num-set num-sum]]
    (boolean
     (when-not (async-cancelled?)
       (and (seq (set/difference all-num-set num-set))
            ;;; reducing the number of branches is a solid optimization
            (<= num-sum max-num-sum))))))

(defn children-fn
  "create a fn to get children:
  given a tuple containing a sorted set of numbers and their sum,
  return a child for every new number that is not yet in the set,
  each child should be a tuple containing the set of numbers and
  their total sum, to make future branching and matching easier.
  Effectively we only sum once (here) and only have to compare the
  sum when we're checking branch? or match?."
  [all-num-set max-num-sum]
  (fn get-children
    [[num-set num-sum]]
    (when-not (async-cancelled?)
      (for [num-child (set/difference all-num-set num-set)
            :let [num-val (second num-child)
                  num-sum (+ num-sum num-val)]
            ;;; reducing the number of children is a solid optimization
            :when (<= num-sum max-num-sum)]
        [(conj num-set num-child) num-sum]))))

(defn match-fn
  "when the sum of a set of numbers is equal to the max-num-sum
    then return the set of numbers"
  [max-num-sum]
  (fn sum-match?
    [[num-set num-sum]]
    (when (= num-sum max-num-sum)
      ;;; we map second here because each number is a tuple of it's
      ;;; order in the set and it's value
      (->> num-set
           (sort-by first)
           (map second)))))

(defn cancel-timeout!
  [channel timeout-ms]
  (async
   (!<! (async/timeout timeout-ms))
   (when-not (async-completed? channel)
     (async-cancel! channel))))

(def nums
  [10 20 30 40 50 60 70 80 90 100 100
   15 25 35 45 55 65 75 85 95 105])

(def nums-sorted-set
  (->> (map-indexed vector nums)
       (into (sorted-set))))

(let [expected-sum 5000
      branch? (branch-fn nums-sorted-set expected-sum)
      children (children-fn nums-sorted-set expected-sum)
      match? (match-fn expected-sum)
      num-sets (tree-seq branch? children [#{} 0])]
  (let [match-channel (async
                       (some match? num-sets))]
    (cancel-timeout! match-channel 1000)
    (println "match result:" (time (!<!! match-channel)))))
