variable "IMAGE_DEPLOY_NAME" {}

variable "REGISTRY" {
  default = "docker.io"
}

variable "CUSTOMTAGS" {
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

function "all_tags" {
  params = [image_full_name, tags]
  result = notequal("", tags) ? formatlist("${image_full_name}:%s", compact(split(",", tags))) : [image_full_name]
}

target "default" {
  dockerfile = IMAGE_DOCKERFILE
  context = IMAGE_DIR
  tags = all_tags("${REGISTRY}/${IMAGE_DEPLOY_NAME}", CUSTOMTAGS)
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
