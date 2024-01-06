[![Build Status](https://github.com/k13labs/futurama/actions/workflows/clojure.yml/badge.svg)](https://github.com/k13labs/futurama/actions/workflows/clojure.yml)

# _About_

Futurama is a Clojure library for more deeply integrating async abstractions in the Clojure and JVM ecosystem with Clojure [core.async](https://github.com/clojure/core.async).

It adds support for [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html) and [IDeferred](https://github.com/clj-commons/manifold/blob/master/src/manifold/deferred.clj) to be used in approximately the same fashion as Clojure [core.async](https://github.com/clojure/core.async) channels, and for `Future` and `IDeref` to be read in the same manner as well.

# _Usage_

Here's a simple example.

```clj
(ns user
  (:require [futurama.core :refer [async async-for !<!! !<!]])
  (:import [java.util.concurrent CompletableFuture]))

(defn future-thing
  [what]
  (CompletableFuture/completedFuture (format "%s: done" what)))

(async
  (println (!<! (future-thing "laundry"))))
;;; => prints out: "laundry: done"

(!<!!
  (async-for
    [a (range 4)
     b (range 4)
     :let [c (+ a b)]
     :when (and (odd? a) (odd? b))]
    (!<! (timeout 50)) ;;; can use !<! inside `async-for` comprehension so the items are evaluated sequentially
    [a b c (+ a b c)]))
;;; => returns `[[1 1 2 4] [1 3 4 8] [3 1 4 8] [3 3 6 12]]` and takes slightly over 200ms total time.

(!<!!
  (async-for
    [a (range 4)
     b (range 4)
     :let [c (+ a b)]
     :when (and (odd? a) (odd? b))]
    (async
      (!<! (timeout 50)) ;;; here we use !<! inside an async block so we iterate faster through the items
      [a b c (+ a b c)])))
;;; => returns `[[1 1 2 4] [1 3 4 8] [3 1 4 8] [3 3 6 12]]` evaluated fully async and takes slightly over 50ms total time.
```

See the existing tests for more examples.

# _Building_

Futurama is built, tested, and deployed using [Clojure Tools Deps](https://clojure.org/guides/deps_and_cli).

CMake is used to simplify invocation of some commands.

# _Availability_

Futurama releases for this project are on [Clojars](https://clojars.org/). Simply add the following to your project:

[![Clojars Project](http://clojars.org/com.github.k13labs/futurama/latest-version.svg)](http://clojars.org/com.github.k13labs/futurama)

# _Communication_

- For any other questions or issues about Futurama free to browse or open a [Github Issue](https://github.com/k13labs/futurama/issues).

# Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md)

# LICENSE

Copyright 2024 Jose Gomez

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

&nbsp;&nbsp;&nbsp;&nbsp;http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
