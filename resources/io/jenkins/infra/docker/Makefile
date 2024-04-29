# Detect Windows (only OS separating paths in $PATH with ";") to set specific variable and function to get compatible paths
IMAGE_DIR ?= .
ifeq '$(findstring ;,$(PATH))' ';'
	FixPath = $(subst /,\,$1)
else
	FixPath = $1
	# need absolute path for docker bake
	IMAGE_DIR := $(abspath $(PWD)/$(IMAGE_DIR))
endif

current_arch := $(shell uname -m)
export ARCH ?= $(shell case $(current_arch) in (x86_64) echo "amd64" ;; (i386) echo "386";; (aarch64|arm64) echo "arm64" ;; (armv6*) echo "arm/v6";; (armv7*) echo "arm/v7";; (s390*|riscv*|ppc64le) echo $(current_arch);; (*) echo "UNKNOWN-CPU";; esac)

IMAGE_NAME ?= helloworld
IMAGE_DEPLOY_NAME ?= "$(IMAGE_NAME)"
BUILD_TARGETPLATFORM ?= linux/amd64
# Paths
# Detect Windows (only OS separating paths in $PATH with ";") to set specific absolute paths for linux (bake) only
ifeq '$(findstring ;,$(PATH))' ';'
	IMAGE_DOCKERFILE ?= "$(IMAGE_DIR)"/Dockerfile
else
	# need absolute path for docker bake
	ifeq ($(IMAGE_DOCKERFILE),)
		IMAGE_DOCKERFILE := $(abspath $(PWD)/Dockerfile)
	else
		IMAGE_DOCKERFILE := $(abspath $(PWD)/$(IMAGE_DOCKERFILE))
	endif
endif
HADOLINT_REPORT ?= "$(IMAGE_DIR)"/hadolint.json
TEST_HARNESS ?= "$(IMAGE_DIR)"/cst.yml
DOCKER_BAKE_FILE ?= "$(IMAGE_DIR)"/docker-bake.hcl
DOCKER_BAKE_TARGET ?= default

## Image metadatas
GIT_COMMIT_REV ?= $(shell git log -n 1 --pretty=format:'%h')
GIT_SCM_URL ?= $(shell git config --get remote.origin.url)
SCM_URI ?= $(subst git@github.com:,https://github.com/,"$(GIT_SCM_URL)")
BUILD_DATE ?= $(shell date --utc '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || gdate --utc '+%Y-%m-%dT%H:%M:%S')

export 	IMAGE_DEPLOY_NAME \
		TAG_NAME \
		BAKE_TARGETPLATFORMS \
		IMAGE_DOCKERFILE \
		IMAGE_DIR \
		GIT_COMMIT_REV \
		GIT_SCM_URL \
		BUILD_DATE \
		SCM_URI

help: ## Show this Makefile's help
	@echo "Help:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

all: clean lint build test ## Execute the complete process except the "deploy" step

lint: ## Lint the $(IMAGE_DOCKERFILE) content
	@echo "== Linting $(call FixPath,$(IMAGE_DOCKERFILE)) with hadolint:"
	hadolint --format=json "$(call FixPath,$(IMAGE_DOCKERFILE))" > "$(call FixPath,$(HADOLINT_REPORT))"
	@echo "== Lint succeeded"

build: ## Build the Docker Image $(IMAGE_NAME) from $(IMAGE_DOCKERFILE)
	@echo "== Building $(IMAGE_NAME) from $(call FixPath,$(IMAGE_DOCKERFILE)) with docker:"
	docker image build \
		--tag "$(IMAGE_NAME)" \
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
		--platform "$(BUILD_TARGETPLATFORM)" \
		--file "$(call FixPath,$(IMAGE_DOCKERFILE))" \
		"$(IMAGE_DIR)"
	@echo "== Build succeeded"

bake-build: ## Build the Docker Image(s) with dockerbake file
	@echo "== Building ${DOCKER_BAKE_TARGET} target from DockerBake file $(DOCKER_BAKE_FILE)"
	@make show
	@docker buildx bake -f "$(DOCKER_BAKE_FILE)" "$(DOCKER_BAKE_TARGET)"
	@echo "== Build succeeded"


clean: ## Delete any file generated during the build steps
	@echo "== Cleaning working directory $(IMAGE_DIR) from generated artefacts:"
	rm -f "$(call FixPath,$(IMAGE_DIR)/*.tar)" "$(HADOLINT_REPORT)"
	@echo "== Cleanup succeeded"

test: ## Execute the test harness on the Docker Image
	@echo "== Test $(IMAGE_NAME) with $(call FixPath,$(TEST_HARNESS)) from $(IMAGE_NAME) with container-structure-test:"
	container-structure-test test --driver=docker --image="$(IMAGE_NAME)" --config="$(call FixPath,$(TEST_HARNESS))" --platform=$(BUILD_TARGETPLATFORM)
	@echo "== Test succeeded"

show: ## Show the output of docker buildx bake --print
	@docker buildx bake -f "$(DOCKER_BAKE_FILE)" "$(DOCKER_BAKE_TARGET)" --print

list: ## List the first tag of each target including the same platform than the current architecture, with "/" replaced by "#"
	@set -x; make --silent show | jq -r '.target | to_entries[] | select(.value.platforms[] | contains("linux/$(ARCH)")) | .value.tags[0] | gsub("/"; "#")'

bake-test: ## Execute the test harness on the Docker image(s) with load
	@echo "== Load $(IMAGE_NAME) within docker engine from docker bake buildx engine"
	@make show
	@docker buildx bake -f "$(call FixPath,$(DOCKER_BAKE_FILE))" "$(DOCKER_BAKE_TARGET)" --set "*.platform=linux/$(ARCH)" --load
	@make --silent list | while read image; do make --silent "bake-test-$${image}"; done
	@echo "== All tests succeeded"

bake-test-%: ## Execute the test harness on the tag of an image
	@echo "== Test $(subst #,/,$*) with $(call FixPath,$(TEST_HARNESS)) from $(IMAGE_NAME) with container-structure-test:"
	container-structure-test test --driver=docker --image="$(subst #,/,$*)" --config="$(call FixPath,$(TEST_HARNESS))" --platform=$(BUILD_TARGETPLATFORM)
	@echo "== Test succeeded"

## This steps expects that you are logged to the Docker registry to push image into
deploy: ## Tag and push the built image as specified by $(IMAGE_DEPLOY).
	@echo "== Deploying $(IMAGE_NAME) to $(IMAGE_DEPLOY_NAME) with docker:"
	docker image tag "$(IMAGE_NAME)" "$(IMAGE_DEPLOY_NAME)"
	docker image push "$(IMAGE_DEPLOY_NAME)"
	@echo "== Deploy succeeded"

bake-deploy: ## Tag and push the built image as specified by docker bake file
	@echo "== Deploying $(DOCKER_BAKE_TARGET) target with docker bake file"
	@docker buildx bake -f "$(call FixPath,$(DOCKER_BAKE_FILE))" "$(DOCKER_BAKE_TARGET)" --push

.PHONY: all clean lint build test deploy
