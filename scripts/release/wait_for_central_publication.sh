#!/usr/bin/env bash
set -euo pipefail

: "${CENTRAL_DEPLOYMENT_ID:?}"
: "${CENTRAL_PASSWORD:?}"
: "${CENTRAL_USERNAME:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${PROCWRIGHT_STAGED_BUNDLE_SHA256:?}"
: "${PROCWRIGHT_STAGING_ARTIFACT_DIGEST:?}"
: "${PROCWRIGHT_STAGING_ARTIFACT_ID:?}"
: "${PROCWRIGHT_STAGING_ARTIFACT_NAME:?}"
: "${PROCWRIGHT_STAGING_RUN_ID:?}"
: "${RUNNER_TEMP:?}"

expected_artifact="procwright-${PROCWRIGHT_RELEASE_VERSION}-${PROCWRIGHT_RELEASE_COMMIT}-central-bundle"
if [[ ! "${PROCWRIGHT_STAGED_BUNDLE_SHA256}" =~ ^[0-9a-f]{64}$ \
  || ! "${PROCWRIGHT_STAGING_ARTIFACT_DIGEST}" =~ ^[0-9a-f]{64}$ \
  || ! "${PROCWRIGHT_STAGING_ARTIFACT_ID}" =~ ^[1-9][0-9]{0,19}$ \
  || "${PROCWRIGHT_STAGING_ARTIFACT_NAME}" != "${expected_artifact}" \
  || ! "${PROCWRIGHT_STAGING_RUN_ID}" =~ ^[1-9][0-9]{0,19}$ ]]; then
  printf 'Carried staging artifact identity is malformed\n' >&2
  exit 1
fi

credential_file="${RUNNER_TEMP}/procwright-central-wait-credentials"
cleanup() {
  unset CENTRAL_USERNAME CENTRAL_PASSWORD
  rm -f -- "${credential_file}"
}
umask 077
set -o noclobber
if ! exec 9> "${credential_file}"; then
  set +o noclobber
  printf 'Temporary Central credential file already exists\n' >&2
  exit 1
fi
set +o noclobber
trap cleanup EXIT
printf '%s\0%s\0' "${CENTRAL_USERNAME}" "${CENTRAL_PASSWORD}" >&9
exec 9>&-
unset CENTRAL_USERNAME CENTRAL_PASSWORD

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"
procwright_python verify_maven_central_staged_bundle.py wait-and-verify-central \
  --bundle "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip" \
  --manifest "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-staged-bundle-payload-manifest.json" \
  --checksum "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip.sha256" \
  --deployment "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-central-deployment.json" \
  --credentials-file "${credential_file}" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
  --deployment-id "${CENTRAL_DEPLOYMENT_ID}" \
  --expected-bundle-sha256 "${PROCWRIGHT_STAGED_BUNDLE_SHA256}" \
  --destination "${RUNNER_TEMP}/procwright-central-downloads" \
  --attempts 12 \
  --retry-delay-seconds 5 \
  --timeout-seconds 30 \
  --deadline-seconds 1800
