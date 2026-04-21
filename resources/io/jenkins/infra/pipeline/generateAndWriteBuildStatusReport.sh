#!/bin/bash
set -euxo pipefail

# Required parameters set by Jenkins controller
: "${JENKINS_URL:?JENKINS_URL is not set}"
: "${WORKSPACE:?WORKSPACE is not set}"

# Required parameters that must be set by the caller
: "${JOB_NAME:?JOB_NAME is not set}"
: "${BUILD_NUMBER:?BUILD_NUMBER is not set}"

# Extract hostname from JENKINS_URL
controller_hostname=$(echo "${JENKINS_URL}" | sed 's|https\?://||' | cut -d'/' -f1)

# Sanitize JOB_NAME for filesystem and URL safety
sanitized_job_name=$(echo "${JOB_NAME}" | tr ' ' '-')

# Build file path
report_dir="${WORKSPACE}/build_status_reports/${controller_hostname}/${sanitized_job_name}"
report_file="${report_dir}/status.json"

# Create directory
mkdir -p "${report_dir}"

# Generate timestamp
report_timestamp=$(date -u +%s)

# Generate JSON report
cat > "${report_file}" << EOF
{
  "controller_url": "${JENKINS_URL}",
  "job_name": "${JOB_NAME}",
  "build_number": "${BUILD_NUMBER}",
  "report_timestamp": "${report_timestamp}"
}
EOF

# Upload with azcopy
destination_url="https://buildsreportsjenkinsio.file.core.windows.net/builds-reports-jenkins-io/build_status_reports/${controller_hostname}/${sanitized_job_name}/"

cd "${report_dir}"
azcopy logout >/dev/null 2>&1 || true
test -z "${AZURE_FEDERATED_TOKEN_FILE:-}" || export AZCOPY_AUTO_LOGIN_TYPE=WORKLOAD
if [[ -n "${AZCOPY_LOGIN_IDENTITY_RESOURCE_ID:-}" ]]; then
  azcopy login --identity --identity-resource-id "${AZCOPY_LOGIN_IDENTITY_RESOURCE_ID}"
else
  azcopy login --identity
fi
azcopy copy "status.json" "${destination_url}" --recursive
