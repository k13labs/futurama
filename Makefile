.PHONY: repl test clean deploy install format-check format-fix

SHELL := /bin/bash
VERSION := 0.2.0

repl:
	clojure -M:dev:test:repl

test:
	clojure -M:dev:test:runner --focus :unit --reporter kaocha.report/documentation --no-capture-output

clean:
	rm -rf target build

lint:
	clojure -M:dev:test:clj-kondo --copy-configs --dependencies --parallel --lint "$(shell clojure -A:dev:test -Spath)"
	clojure -M:dev:test:clj-kondo --lint "src:test" --fail-level "error"

build:
	clojure -Spom
	clojure -X:jar \
		:sync-pom true \
		:group-id "com.github.k13labs" \
		:artifact-id "futurama" \
		:version '"$(VERSION)"'

deploy: clean build
	clojure -X:deploy-maven

install:
	clojure -X:install-maven

format-check:
	clojure -M:format-check

format-fix:
	clojure -M:format-fix
