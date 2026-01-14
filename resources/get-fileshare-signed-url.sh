#!/bin/bash
# Purpose: Shell script to get a file share URL signed or not with a short-lived SAS token
# --
# Description: This script uses a service principal or a storage account access key to generate a SAS token
# and returns a signed file share URL composed of the storage resource URI and the SAS token,
# or login with azcopy and the service principal from a VM agent's user assigned identity (credential-less) and returns an unsigned file share URL if no service principal secret is passed.
# Ref: https://learn.microsoft.com/en-us/azure/storage/common/storage-sas-overview
# --
# Usage:
# - Returns a file share URL which can be passed to 'azcopy' (URI always ends with a trailing slash, and a querystring may be present if a short-time lived token is needed): 
#    - With a token (e.g. with Azure SP credential): https://<storage_name>.file.core.windows.net/<storage_fileshare>/?<token>
#    - Without a token (e.g. with credential-less authentication such as workload identity): https://<storage_name>.file.core.windows.net/<storage_fileshare>/
# - Interact with a file share and azcopy: azcopy list "$(./get-fileshare-signed-url.sh)"
# --
# Required parameters defined as environment variables:
# - STORAGE_FILESHARE: the file share name
# - STORAGE_NAME: the storage account name where the file share is located
#
# To use this script the credential-less way returning the file share URL without SAS token:
# - Ensure JENKINS_INFRA_FILESHARE_CLIENT_SECRET is unset or empty
#
# Otherwise and depending on wether you want to use a service principal or an access key to generate the SAS token, you'll also need:
# - AZURE_STORAGE_KEY: the storage account access key
# or
# - JENKINS_INFRA_FILESHARE_CLIENT_ID: the service principal app registration client id
# - JENKINS_INFRA_FILESHARE_CLIENT_SECRET: the service principal client secret
# - JENKINS_INFRA_FILESHARE_TENANT_ID: the file share tenant id
# And for both, you'll need those additional required parameters:
# - STORAGE_DURATION_IN_MINUTE: lifetime of the short-lived SAS token, in minute. Note: not taken in account in credential-less case
# - STORAGE_PERMISSIONS: the permission(s) granted on the file share, any of "dlrw" (note: the order matters). Note: not taken in account in credential-less case
# --------------------------------------------------------------------------------
set -Eeu -o pipefail

# Don't print any trace
set +x

: "${STORAGE_FILESHARE?}" "${STORAGE_NAME?}" "${STORAGE_DURATION_IN_MINUTE?}" "${STORAGE_PERMISSIONS?}"

# Ensure the script is re-entrant by using unique temporary `az` configuration directory for each call
# Ref. https://learn.microsoft.com/en-us/cli/azure/use-azure-cli-successfully?tabs=bash%2Cbash2#concurrent-execution
AZURE_CONFIG_DIR="$(mktemp -d)"
export AZURE_CONFIG_DIR


# Consumers expects a trailing slash, whether or not a token is appended
fileshare_url="https://${STORAGE_NAME}.file.core.windows.net/${STORAGE_FILESHARE}/"

secret="${JENKINS_INFRA_FILESHARE_CLIENT_SECRET:-}"
# Credential-less using user assigned identity, no need for any SAS token in the returned URL
if [[ -z "${secret}" ]]; then
    # Login without the JSON output from azcopy
    azcopy login --identity > /dev/null
    echo "${fileshare_url}"
    exit 0
fi

accountKeyArg=()
shouldLogout="false"
# If a storage account key env var exists, use it instead of a service principal to generate the file share SAS token
if  [[ -n "${AZURE_STORAGE_KEY:-}" ]]; then
    accountKeyArg=("--account-key" "${AZURE_STORAGE_KEY}")
else
    # Env vars needed to use a service principal
    : "${JENKINS_INFRA_FILESHARE_CLIENT_ID?}" "${JENKINS_INFRA_FILESHARE_TENANT_ID?}"

    # Login without the JSON output from az
    az login --service-principal \
    --user "${JENKINS_INFRA_FILESHARE_CLIENT_ID}" \
    --password="${secret}" \
    --tenant "${JENKINS_INFRA_FILESHARE_TENANT_ID}" > /dev/null

    shouldLogout="true"
fi

# date(1) isn't GNU compliant on MacOS, using gdate(1) in that case
[[ "$(uname  || true)" == "Darwin" ]] && dateCmd="gdate" || dateCmd="date"
expiry="$("${dateCmd}" --utc --date "+ ${STORAGE_DURATION_IN_MINUTE} minutes" +"%Y-%m-%dT%H:%MZ")"

# Generate a SAS token, remove double quotes around it and replace potential '/' by '%2F'
token="$(az storage share generate-sas "${accountKeyArg[@]}" \
--name "${STORAGE_FILESHARE}" \
--account-name "${STORAGE_NAME}" \
--https-only \
--permissions "${STORAGE_PERMISSIONS}" \
--expiry "${expiry}" \
--only-show-errors \
| sed 's/\"//g' \
| sed 's|/|%2F|g')"

[[ "${shouldLogout}" == "true" ]] && az logout

# Return signed URL
echo "${fileshare_url}?${token}"
