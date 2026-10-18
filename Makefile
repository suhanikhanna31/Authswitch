SRC := $(shell find src/main -name '*.java')
TST := $(shell find src/test -name '*.java')

.PHONY: build test run clean serve

build:
	@mkdir -p out && javac --release 17 -Xlint:all -d out $(SRC)

test: build
	@mkdir -p out-test && javac --release 17 -cp out -d out-test $(TST) && java -cp out:out-test com.authswitch.AllTests

run: build
	java -cp out com.authswitch.demo.Demo

clean:
	rm -rf out out-test

serve: build
	java -cp out com.authswitch.demo.ApiServer
