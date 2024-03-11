#!/bin/bash
set -eu -o pipefail

: "${JENKINS_INFRA_FILESHARE_CLIENT_ID?}" "${JENKINS_INFRA_FILESHARE_CLIENT_SECRET?}" "${JENKINS_INFRA_FILESHARE_TENANT_ID?}" "${STORAGE_FILESHARE?}" "${STORAGE_NAME?}" "${STORAGE_DURATION_IN_MINUTE?}" "${STORAGE_PERMISSIONS?}"

# Don't print any command
set +x

# Login without the JSON output from az
az login --service-principal --user "${JENKINS_INFRA_FILESHARE_CLIENT_ID}" --password "${JENKINS_INFRA_FILESHARE_CLIENT_SECRET}" --tenant "${JENKINS_INFRA_FILESHARE_TENANT_ID}" > /dev/null

# Generate a SAS token, remove double quotes around it and replace potential '/' by '%2F'
expiry=$(date --utc --date "+ ${STORAGE_DURATION_IN_MINUTE} minutes" +"%Y-%m-%dT%H:%MZ")
token=$(az storage share generate-sas \
--name "${STORAGE_FILESHARE}" \
--account-name "${STORAGE_NAME}" \
--https-only \
--permissions "${STORAGE_PERMISSIONS}" \
--expiry "${expiry}" \
--only-show-errors \
| sed 's/\"//g' \
| sed 's|/|%2F|g')

az logout

echo "https://${STORAGE_NAME}.file.core.windows.net/${STORAGE_FILESHARE}/?${token}"
