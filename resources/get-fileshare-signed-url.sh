#!/bin/bash
# Purpose: Shell script to get a file share URL signed with a short-lived SAS token
# --
# Description: This script uses either a service principal or either a storage account access key to generate a SAS token
# and returns the file share URL composed of the storage resource URI and the SAS token.
# Ref: https://learn.microsoft.com/en-us/azure/storage/common/storage-sas-overview
# This service principal can come from a credentials, or from a VM agent's user assigned identity
# --
# Usage:
# - Return a file share signed URL: ./get-fileshare-signed-url.sh
# - Interact with a file share and azcopy: azcopy list "$(./get-fileshare-signed-url.sh)"
# --
# Required parameters defined as environment variables:
# - STORAGE_FILESHARE: the file share name
# - STORAGE_NAME: the storage account name where the file share is located
# - STORAGE_DURATION_IN_MINUTE: lifetime of the short-lived SAS token, in minute
# - STORAGE_PERMISSIONS: the permission(s) granted on the file share, any of "dlrw" (note: the order matters)
#
# Depending on wether you want to use a service principal or an access key to generate the SAS token, you'll also need either:
# - AZURE_STORAGE_KEY: the storage account access key
# or
# - JENKINS_INFRA_FILESHARE_CLIENT_ID: the service principal app registration client id
# - JENKINS_INFRA_FILESHARE_CLIENT_SECRET: the service principal client secret. If not set, login into Azure using user assigned identity
# - JENKINS_INFRA_FILESHARE_TENANT_ID: the file share tenant id
# --------------------------------------------------------------------------------
set -Eeu -o pipefail

# Don't print any trace
set +x

: "${STORAGE_FILESHARE?}" "${STORAGE_NAME?}" "${STORAGE_DURATION_IN_MINUTE?}" "${STORAGE_PERMISSIONS?}"

# Ensure the script is re-entrant by using unique temporary `az` configuration directory for each call
# Ref. https://learn.microsoft.com/en-us/cli/azure/use-azure-cli-successfully?tabs=bash%2Cbash2#concurrent-execution
AZURE_CONFIG_DIR="$(mktemp -d)"
export AZURE_CONFIG_DIR

secret="${JENKINS_INFRA_FILESHARE_CLIENT_SECRET:-}"

accountKeyArg=()
shouldLogout="true"
generate_sas_token="false"
# If a storage account key env var exists, use it instead of a service principal to generate a file share SAS token
if  [[ -n "${AZURE_STORAGE_KEY:-}" ]]; then
    accountKeyArg=("--account-key" "${AZURE_STORAGE_KEY}")
    shouldLogout="false"
    generate_sas_token="true"
fi

# If a fileshare client secret is defined, use it to login
if [[ -n "${secret}" ]]; then
    # Env vars needed to use a service principal
    : "${JENKINS_INFRA_FILESHARE_CLIENT_ID?}" "${JENKINS_INFRA_FILESHARE_TENANT_ID?}"

    # Login without the JSON output from az
    az login --service-principal \
    --user "${JENKINS_INFRA_FILESHARE_CLIENT_ID}" \
    --password="${secret}" \
    --tenant "${JENKINS_INFRA_FILESHARE_TENANT_ID}" > /dev/null

    generate_sas_token="true"
fi

# If using an Azure storage key or an Azure credentials, we need to generate a Service SAS token to get a signed File Share URL
token_as_query_string=""
if [[ "${generate_sas_token}" == "true" ]]; then
    # date(1) isn't GNU compliant on MacOS, using gdate(1) in that case
    [[ "$(uname  || true)" == "Darwin" ]] && dateCmd="gdate" || dateCmd="date"
    expiry="$("${dateCmd}" --utc --date "+ ${STORAGE_DURATION_IN_MINUTE} minutes" +"%Y-%m-%dT%H:%MZ")"

    # Generate a SAS token, remove double quotes around it and replace potential '/' by '%2F'
    token_as_query_string="$(az storage share generate-sas "${accountKeyArg[@]}" \
    --name "${STORAGE_FILESHARE}" \
    --account-name "${STORAGE_NAME}" \
    --https-only \
    --permissions "${STORAGE_PERMISSIONS}" \
    --expiry "${expiry}" \
    --only-show-errors \
    | sed 's/\"//g' \
    | sed 's|/|%2F|g')"
else
    # If using user assigned identity, we just have to use it to login with azcopy
    azcopy login --identity > /dev/null
fi

[[ "${shouldLogout}" == "true" ]] && az logout

echo "https://${STORAGE_NAME}.file.core.windows.net/${STORAGE_FILESHARE}/?${token_as_query_string}"
