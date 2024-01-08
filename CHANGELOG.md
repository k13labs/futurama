This is a history of changes to k13labs/futurama

# 0.6.1
* Code linting updates

# 0.6.0
* Add some no-doc tags to extra namespaces
* Rename `cancel!` and `cancelled?` to `async-cancel!` and `async-cancelled?`

# 0.5.0
* Add custom state to keep track of async items
* Add custom cancel strategy which combines bound state, global weak state, and custom protocol impl

# 0.4.0
* Add `fixed-threadpool` method to create a FixedThreadPool
* The default `*thread-pool*` is now a FixedThreadPool which can be interrupted.
* Allow `async` to be interrupted just like `completable-future`, add tests.

# 0.3.9
* Add `with-pool` macro

# 0.3.8
* Add support for `Future` and `IDeref`.
* Rename and refactor internal `satisfies?` to `instance-satisfies?` and `class-satisfies?`

# 0.3.7
* Add collection helpers for: `async-reduce`, `async-some`, `async-every?`, `async-walk/prewalk/postwalk`

# 0.3.6
* Refactored `async-for` so it uses less async macros and it is more flexible
* Refactored `async-map` so it leverages `async-for` behind the scenes.
* Removed `async-some` and `async-every?` and instead added some new helpers.
* Added `async->` and `async->>` threading macros to make it easier to thread async.
* Replaced matching on Exception to Throwable to avoid leaving hanging promises due to errors.

# 0.3.5
* Add more async collection fns: `async-map`, `async-some`, `async-every?`

# 0.3.4
* Simplify `async-for` by just executing each iteration inside an async block and then collect after

# 0.3.3
* Add `async-for` comprehension which implicitly runs inside an async block

# 0.3.2
* Ensure Deferred is handled correctly on put! when realized

# 0.3.1
* Add new `async?` helper function which is useful

# 0.3.0
* Add better identity-like raw value handling for `!<!` and `!<!!`

# 0.2.2
* Add SCM to POM file

# 0.2.1
* Update exception handling to better deal with ExecutionException and CompletionException

# 0.2.0
* Add support for Manifold Deferred

# 0.1.0
* The initial release.
