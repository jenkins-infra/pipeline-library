variable "IMAGE_DEPLOY_NAME" {}

variable "REGISTRY" {
  default = "docker.io"
}

variable "NEXT_VERSION" {
  default = ""
}

variable "BAKE_TARGETPLATFORMS" {
  default = "linux/arm64"
}

variable "IMAGE_DOCKERFILE" {
  default = "Dockerfile"
}

variable "IMAGE_DIR" {
  default = "."
}

variable "GIT_COMMIT_REV" {
  default = ""
}
variable "GIT_SCM_URL" {
  default = ""
}
variable "BUILD_DATE" {
  default = ""
}
variable "SCM_URI" {
  default = ""
}

# return the full image name
function "full_image_name" {
  params = [tag]
  result = notequal("", tag) ? "${REGISTRY}/${IMAGE_DEPLOY_NAME}:${tag}" : "${REGISTRY}/${IMAGE_DEPLOY_NAME}:latest"
}

target "default" {
  dockerfile = IMAGE_DOCKERFILE
  context = IMAGE_DIR
  tags = [
    full_image_name("latest"),
    full_image_name(NEXT_VERSION)
  ]
  platforms = [BAKE_TARGETPLATFORMS]
  args = {
    GIT_COMMIT_REV="${GIT_COMMIT_REV}",
    GIT_SCM_URL="${GIT_SCM_URL}",
    BUILD_DATE="${BUILD_DATE}",
  }
  labels = {
    "org.opencontainers.image.source"="${GIT_SCM_URL}",
    "org.label-schema.vcs-url"="${GIT_SCM_URL}",
    "org.opencontainers.image.url"="${SCM_URI}",
    "org.label-schema.url"="${SCM_URI}",
    "org.opencontainers.image.revision"="${GIT_COMMIT_REV}",
    "org.label-schema.vcs-ref"="${GIT_COMMIT_REV}",
    "org.opencontainers.image.created"="${BUILD_DATE}",
    "org.label-schema.build-date"="${BUILD_DATE}",
  }
}
