#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${GH_TOKEN:?}"
: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_RUN_ID:?}"
: "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST:?}"
: "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID:?}"
: "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_NAME:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_RELEASE_VERSION}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"

expected_name="procwright-${PROCWRIGHT_RELEASE_VERSION}-${PROCWRIGHT_RELEASE_COMMIT}-central-verified-bytes"
if [[ ! "${GITHUB_RUN_ID}" =~ ^[1-9][0-9]{0,19}$ \
  || ! "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID}" =~ ^[1-9][0-9]{0,19}$ \
  || "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_NAME}" != "${expected_name}" \
  || ! "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Central byte artifact identity is malformed\n' >&2
  exit 1
fi

umask 077
temporary="$(mktemp -d "${RUNNER_TEMP}/procwright-central-byte-artifact.XXXXXX")"
cleanup() {
  rm -rf -- "${temporary}"
}
trap cleanup EXIT
metadata="${temporary}/metadata.json"
archive="${temporary}/artifact.zip"

gh api --method GET \
  "repos/${GITHUB_REPOSITORY}/actions/artifacts/${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID}" \
  > "${metadata}"
procwright_python central_release_artifact.py verify-metadata \
  --response "${metadata}" \
  --repository "${GITHUB_REPOSITORY}" \
  --run-id "${GITHUB_RUN_ID}" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
  --artifact-id "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID}" \
  --artifact-name "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_NAME}" \
  --artifact-digest "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST}"

gh api --method GET \
  -H "Accept: application/vnd.github+json" \
  "repos/${GITHUB_REPOSITORY}/actions/artifacts/${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID}/zip" \
  > "${archive}"
procwright_python central_release_artifact.py extract \
  --archive "${archive}" \
  --directory "${RUNNER_TEMP}/procwright-central-downloads" \
  --bundle "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip" \
  --manifest "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-staged-bundle-payload-manifest.json" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --digest "${PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST}"
