#!/bin/bash
set -euo pipefail
set -x

# Extract hostname from JENKINS_URL
CONTROLLER_HOSTNAME=$(echo "$JENKINS_URL" | sed 's|https\?://||' | cut -d'/' -f1)

# Build file path
REPORT_DIR="$WORKSPACE/build_status_reports/$CONTROLLER_HOSTNAME/$JOB_NAME"
REPORT_FILE="$REPORT_DIR/status.json"

# Create directory
mkdir -p "$REPORT_DIR"

# Generate timestamp
REPORT_TIMESTAMP=$(date -u +%s)

# Generate JSON report
cat > "$REPORT_FILE" << EOF
{
  "controller_url": "$JENKINS_URL",
  "job_name": "$JOB_NAME",
  "build_number": "$BUILD_NUMBER",
  "build_status": "$BUILD_STATUS",
  "report_timestamp": "$REPORT_TIMESTAMP"
}
EOF

# Upload with azcopy
DESTINATION_URL="https://buildsreportsjenkinsio.file.core.windows.net/builds-reports-jenkins-io/build_status_reports/$CONTROLLER_HOSTNAME/$JOB_NAME/"

cd "$REPORT_DIR"
azcopy logout >/dev/null 2>&1 || true
test -z "${AZURE_FEDERATED_TOKEN_FILE:-}" || export AZCOPY_AUTO_LOGIN_TYPE=WORKLOAD
azcopy login --identity
azcopy copy "status.json" "$DESTINATION_URL" --recursive
