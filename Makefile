VERSION ?= dev
CODENAME ?= development

JAVAC ?= javac
JAVA ?= java
JAR ?= jar
RELEASE ?= 17

BUILD := build
JAR_FILE := $(BUILD)/glacierdb-$(VERSION).jar
PACKAGE_NAME := glacierdb-java-$(VERSION).tar.gz
MAIN_SOURCES := $(shell find src/main/java -name '*.java')
TEST_SOURCES := $(shell find src/test/java -name '*.java')
ADDR ?= 127.0.0.1:7878

.PHONY: help version build test example package clean

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*?## "}; /^[a-zA-Z0-9_.-]+:.*?## / {printf "  \033[32m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

version: ## Show package version
	@echo "glacierdb-java $(VERSION) ($(CODENAME))"

build: $(JAR_FILE) ## Compile the driver into build/glacierdb-$(VERSION).jar (no dependency)

$(JAR_FILE): $(MAIN_SOURCES)
	rm -rf $(BUILD)/classes && mkdir -p $(BUILD)/classes
	$(JAVAC) --release $(RELEASE) -Xlint:all -d $(BUILD)/classes $(MAIN_SOURCES)
	$(JAR) --create --file $@ -C $(BUILD)/classes .

test: ## Run the tests (GLACIERDB_TEST_ADDR=host:port also runs the live test)
	rm -rf $(BUILD)/test-classes && mkdir -p $(BUILD)/test-classes
	$(JAVAC) --release $(RELEASE) -d $(BUILD)/test-classes $(MAIN_SOURCES) $(TEST_SOURCES)
	$(JAVA) -cp $(BUILD)/test-classes org.openglacier.glacierdb.DriverTest

example: $(JAR_FILE) ## Run examples/Basic.java against ADDR (default 127.0.0.1:7878)
	$(JAVA) -cp $(JAR_FILE) examples/Basic.java $(ADDR) $(OGID)

package: $(JAR_FILE) ## Create the release archive (jar + sources)
	tar czf $(PACKAGE_NAME) -C $(BUILD) $(notdir $(JAR_FILE)) -C .. README.md pom.xml src examples

clean: ## Remove build files
	rm -rf $(BUILD) $(PACKAGE_NAME)
