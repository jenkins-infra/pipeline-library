#!/bin/bash
#
#
# It expects the following environment variables to be set:
#   ENV_CONTROLLER_HOSTNAME
#   ENV_JOB_NAME
#   ENV_BUILD_NUMBER
#   ENV_BUILD_STATUS
#   ENV_TARGET_JSON_FILE_PATH

set -euo pipefail # Exit on error, treat unset variables as an error, and fail on pipe errors.
                  # 'set -u' will cause an error if any ENV_ var below is not set.

# Extract the directory part from the target file path.
# Example: if ENV_TARGET_JSON_FILE_PATH is "foo/bar/status.json", TARGET_DIR will be "foo/bar"
TARGET_DIR=$(dirname "$ENV_TARGET_JSON_FILE_PATH")

# Create the target directory and any parent directories if they don't exist.
mkdir -p "$TARGET_DIR"

# Generate timestamp.
REPORT_TIMESTAMP=$(date -u +'%Y-%m-%dT%H:%M:%SZ')

# Construct JSON using cat and heredoc, redirecting output directly to the target file.
# The DELIMITER (EOF) is unquoted, so shell variables ($VAR) inside will be expanded.
# This version assumes input ENV_... variables are "clean" for JSON string values.
cat > "$ENV_TARGET_JSON_FILE_PATH" << EOF
{
  "controller_hostname": "$ENV_CONTROLLER_HOSTNAME",
  "job_name": "$ENV_JOB_NAME",
  "build_number": "$ENV_BUILD_NUMBER",
  "build_status": "$ENV_BUILD_STATUS",
  "report_timestamp": "$REPORT_TIMESTAMP"
}
EOF

# Optional: echo to stderr for debugging when running manually
# echo "Debug: Report written to $ENV_TARGET_JSON_FILE_PATH" >&2
