#!/usr/bin/env bash
set -euo pipefail

: "${CENTRAL_PASSWORD:?}"
: "${CENTRAL_USERNAME:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${RUNNER_TEMP:?}"

credential_file="${RUNNER_TEMP}/procwright-central-stage-credentials"
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
procwright_python verify_maven_central_staged_bundle.py stage-central \
  --bundle "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip" \
  --manifest "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-staged-bundle-payload-manifest.json" \
  --checksum "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip.sha256" \
  --deployment "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-central-deployment.json" \
  --credentials-file "${credential_file}" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
  --deadline-seconds 1800
