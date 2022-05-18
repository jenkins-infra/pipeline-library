# Windows detection
ifeq '$(findstring ;,$(PATH))' ';'
	FixPath = $(subst /,\,$1)
	IMAGE_DIR = $(WORKSPACE)
	IMAGE_PLATFORM_ARG = --platform "windows/amd64"
else
	FixPath = $1
	IMAGE_DIR ?= $(PWD)
	IMAGE_PLATFORM_ARG ?= --platform "linux/amd64"
endif

IMAGE_NAME ?= helloworld
IMAGE_DEPLOY_NAME ?= example/$(IMAGE_NAME)
# Paths
IMAGE_DOCKERFILE ?= $(IMAGE_DIR)/Dockerfile
IMAGE_ARCHIVE ?= $(IMAGE_DIR)/image.tar
HADOLINT_REPORT ?= $(IMAGE_DIR)/hadolint.json
TEST_HARNESS ?= $(IMAGE_DIR)/cst.yml

## Locate command line tools. Defaults to rely on PATH
CONTAINER_BIN ?= docker
HADOLINT_BIN ?= hadolint
CST_BIN ?= container-structure-test

## Image metadatas
GIT_COMMIT_REV ?= $(shell git log -n 1 --pretty=format:'%h')
GIT_SCM_URL ?= $(shell git config --get remote.origin.url)
SCM_URI ?= $(subst git@github.com:,https://github.com/,$(GIT_SCM_URL))
BUILD_DATE ?= $(shell date --utc '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || gdate --utc '+%Y-%m-%dT%H:%M:%S')

## Test Harness settings
CST_DRIVER ?= docker # https://github.com/GoogleContainerTools/container-structure-test#running-file-tests-without-docker
ifeq ($(CST_DRIVER),tar)
CST_SUT = $(IMAGE_ARCHIVE)
else
CST_SUT = $(IMAGE_NAME)
endif

help: ## Show this Makefile's help
	@echo "Help:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

all: clean lint build test ## Execute the complete process except the "deploy" step

lint: ## Lint the $(IMAGE_DOCKERFILE) content
	@echo "== Linting $(DOCKERFILE_FULL_PATH) with $(HADOLINT_BIN):"
	@echo "Writing Lint results to $(call FixPath,$(HADOLINT_REPORT))"
	$(HADOLINT_BIN) --format=json "$(IMAGE_DOCKERFILE)" > "$(call FixPath,$(HADOLINT_REPORT))"
	@echo "== Lint succeeded"

build: ## Build the Docker Image $(IMAGE_NAME) from $(IMAGE_DOCKERFILE)
	@echo "== Building $(IMAGE_NAME) from $(call FixPath,$(IMAGE_DOCKERFILE)) with $(CONTAINER_BIN):"
	$(CONTAINER_BIN) build \
		# --quiet \
		--tag $(IMAGE_NAME) \
		--build-arg "GIT_COMMIT_REV=$(GIT_COMMIT_REV)" \
		--build-arg "GIT_SCM_URL=$(GIT_SCM_URL)" \
		--build-arg "BUILD_DATE=$(BUILD_DATE)" \
		--label "org.opencontainers.image.source=$(GIT_SCM_URL)" \
		--label "org.label-schema.vcs-url=$(GIT_SCM_URL)" \
		--label "org.opencontainers.image.url=$(SCM_URI)" \
		--label "org.label-schema.url=$(SCM_URI)" \
		--label "org.opencontainers.image.revision=$(GIT_COMMIT_REV)" \
		--label "org.label-schema.vcs-ref=$(GIT_COMMIT_REV)" \
		--label "org.opencontainers.image.created=$(BUILD_DATE)" \
		--label "org.label-schema.build-date=$(BUILD_DATE)" \
		$(IMAGE_PLATFORM_ARG) \
		--file "$(call FixPath,$(IMAGE_DOCKERFILE))" \
		"$(IMAGE_DIR)"
	@echo "== Build succeeded"
	@$(CONTAINER_BIN)docker images

clean: ## Delete any file generated during the build steps
	@echo "== Cleaning working directory $(IMAGE_DIR) from generated artefacts:"
	rm -f "$(call FixPath,$(IMAGE_DIR)/*.tar)" "$(HADOLINT_REPORT)"
	@echo "== Cleanup succeeded"

test: ## Execute the test harness on the Docker Image (archived as $(IMAGE_ARCHIVE) if using tar CST driver)
	@echo "== Test $(IMAGE_NAME) with $(call FixPath,$(TEST_HARNESS)) from $(CST_SUT) with $(CST_BIN):"
ifeq ($(CST_DRIVER),tar)
	@echo "== Image $(IMAGE_NAME) exported to $(call FixPath,$(IMAGE_ARCHIVE)) for test."
	$(CONTAINER_BIN) save --output="$(call FixPath,$(IMAGE_ARCHIVE))" $(IMAGE_NAME)
endif
	$(CST_BIN) test --driver=$(CST_DRIVER) --image=$(CST_SUT) --config="$(call FixPath,$(TEST_HARNESS))"
	@echo "== Test succeeded"

## This steps expects that you are logged to the Docker registry to push image into
deploy: ## Tag and push the built image as specified by $(IMAGE_DEPLOY).
	@echo "== Deploying $(IMAGE_NAME) to $(IMAGE_DEPLOY_NAME) with $(CONTAINER_BIN):"
	$(CONTAINER_BIN) login
	$(CONTAINER_BIN) tag $(IMAGE_NAME) $(IMAGE_DEPLOY_NAME)
	$(CONTAINER_BIN) push $(IMAGE_DEPLOY_NAME)
	@echo "== Deploy succeeded"

.PHONY: all clean lint build test deploy
