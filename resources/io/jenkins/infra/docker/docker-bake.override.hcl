variable "IMAGE_NAME" {}

variable "REGISTRY" {
  default = "docker.io"
}

variable "TAG_NAME" {
  default = ""
}

# return the full image name
function "full_image_name" {
  params = [tag]
  result = notequal("", tag) ? "${REGISTRY}/${IMAGE_NAME}:${tag}" : "${REGISTRY}/${IMAGE_NAME}:latest"
}

target "default" {
  dockerfile = "Dockerfile"
  context = "."
  tags = [
    full_image_name("latest"),
    full_image_name(TAG_NAME)
  ]
  platforms = "$(PLATFORMS)"
  args = {
    GIT_COMMIT_REV="$(GIT_COMMIT_REV)",
    GIT_SCM_URL="$(GIT_SCM_URL)",
    BUILD_DATE="$(BUILD_DATE)",
  }
  labels = {
    "org.opencontainers.image.source"="$(GIT_SCM_URL)",
    "org.label-schema.vcs-url"="$(GIT_SCM_URL)",
    "org.opencontainers.image.url"="$(SCM_URI)",
    "org.label-schema.url"="$(SCM_URI)",
    "org.opencontainers.image.revision"="$(GIT_COMMIT_REV)",
    "org.label-schema.vcs-ref"="$(GIT_COMMIT_REV)",
    "org.opencontainers.image.created"="$(BUILD_DATE)",
    "org.label-schema.build-date"="$(BUILD_DATE)",
  }
}
