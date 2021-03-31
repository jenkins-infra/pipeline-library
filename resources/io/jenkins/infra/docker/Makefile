
IMAGE_NAME ?= helloworld
IMAGE_DEPLOY_NAME ?= $(IMAGE_NAME)
IMAGE_DIR ?= $(PWD)
IMAGE_DOCKERFILE ?= $(IMAGE_DIR)/Dockerfile
IMAGE_ARCHIVE ?= $(IMAGE_DIR)/image.tar
IMAGE_PLATFORM ?= linux/amd64
HADOLINT_REPORT ?= $(IMAGE_DIR)/hadolint.json
TEST_HARNESS ?= $(IMAGE_DIR)/cst.yml

## Use this variable if you want to use Docker instead
CONTAINER_BIN ?= img


## Image metadatas
GIT_COMMIT_REV ?= $(shell git log -n 1 --pretty=format:'%h')
GIT_SCM_URL ?= $(shell git config --get remote.origin.url)
SCM_URI ?= $(subst git@github.com:,https://github.com/,$(GIT_SCM_URL))
BUILD_DATE ?= $(shell date --utc '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || gdate --utc '+%Y-%m-%dT%H:%M:%S')

help: ## Show this Makefile's help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

all: clean lint build test ## Execute the complete process except the "deploy" step

lint: ## Lint the $(IMAGE_DOCKERFILE) content
	@echo "== Linting $(IMAGE_DOCKERFILE)..."
	@echo "Writing Lint results to $(HADOLINT_REPORT)"
	@hadolint --format=json $(IMAGE_DOCKERFILE) > $(HADOLINT_REPORT)
	@echo "== Lint ✅ Succeeded"

build: ## Build the Docker Image $(IMAGE_NAME) from $(IMAGE_DOCKERFILE) and export it to $(IMAGE_ARCHIVE)
	@echo "== Building $(IMAGE_NAME) from $(IMAGE_DOCKERFILE)..."
	@$(CONTAINER_BIN) build \
		-t $(IMAGE_NAME) \
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
		--platform $(IMAGE_PLATFORM) \
		-f $(IMAGE_DOCKERFILE) \
		$(IMAGE_DIR)
	@$(CONTAINER_BIN) save --output=$(IMAGE_ARCHIVE) $(IMAGE_NAME)
	@echo "== Build ✅ Succeeded, image $(IMAGE_NAME) exported to $(IMAGE_ARCHIVE)."

clean: ## Delete any file generated during the build steps
	@echo "== Cleaning working directory from generated artefacts..."
	@rm -f $(IMAGE_DIR)/*.tar $(HADOLINT_REPORT)
	@echo "== Cleanup ✅ Succeeded"

test: ## Execute the test harness on the Docker Image archive at $(IMAGE_ARCHIVE)
	@echo "== Test $(IMAGE_NAME) with $(TEST_HARNESS) from $(IMAGE_ARCHIVE)..."
	@container-structure-test test --driver=tar --image=$(IMAGE_ARCHIVE) --config=$(TEST_HARNESS)
	@echo "== Test ✅ Succeeded"

## This steps expects that you are logged to the Docker registry to push image into
deploy: ## Tag and push the built image as specified by $(IMAGE_DEPLOY).
	@echo "== Deploying $(IMAGE_NAME) to $(IMAGE_DEPLOY_NAME)..."
	$(CONTAINER_BIN) tag $(IMAGE_NAME) $(IMAGE_DEPLOY_NAME)
	$(CONTAINER_BIN) push $(IMAGE_DEPLOY_NAME)
	@echo "== Deploy ✅ Succeeded"

.PHONY: all clean lint build test deploy
