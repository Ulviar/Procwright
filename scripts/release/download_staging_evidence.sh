#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${GITHUB_OUTPUT:?}"
: "${GITHUB_ENV:?}"
: "${GITHUB_REPOSITORY:?}"
: "${PROCWRIGHT_CONSUMER_VERSION:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_CONSUMER_VERSION}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"

expected_artifact="procwright-${PROCWRIGHT_CONSUMER_VERSION}-${PROCWRIGHT_RELEASE_COMMIT}-central-bundle"
umask 077
temporary="$(mktemp -d "${RUNNER_TEMP}/procwright-staging-evidence.XXXXXX")"
cleanup() {
  rm -rf -- "${temporary}"
}
trap cleanup EXIT

runs_response="${temporary}/workflow-runs.json"
artifacts_response="${temporary}/artifacts.json"
raw_artifact="${temporary}/staging-artifact.zip"
gh api --method GET \
  "repos/${GITHUB_REPOSITORY}/actions/workflows/publish-maven-central.yml/runs" \
  -f branch=main \
  -f event=workflow_dispatch \
  -f status=success \
  -f head_sha="${PROCWRIGHT_RELEASE_COMMIT}" \
  -f per_page=100 > "${runs_response}"
stage_run_id="$(
  procwright_python staging_release_artifact.py select-run \
    --response "${runs_response}" \
    --repository "${GITHUB_REPOSITORY}" \
    --commit "${PROCWRIGHT_RELEASE_COMMIT}"
)"
if [[ ! "${stage_run_id}" =~ ^[1-9][0-9]{0,19}$ ]]; then
  printf 'Validated staging run ID is malformed\n' >&2
  exit 1
fi

gh api --method GET \
  "repos/${GITHUB_REPOSITORY}/actions/runs/${stage_run_id}/artifacts" \
  -f per_page=100 > "${artifacts_response}"
artifact_identity="$(
  procwright_python staging_release_artifact.py select-artifact \
    --response "${artifacts_response}" \
    --repository "${GITHUB_REPOSITORY}" \
    --version "${PROCWRIGHT_CONSUMER_VERSION}" \
    --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
    --run-id "${stage_run_id}"
)"
IFS=$'\t' read -r artifact_id artifact_name artifact_digest <<< "${artifact_identity}"
if [[ ! "${artifact_id}" =~ ^[1-9][0-9]{0,19}$ \
  || "${artifact_name}" != "${expected_artifact}" \
  || ! "${artifact_digest}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Validated staging artifact identity is malformed\n' >&2
  exit 1
fi

gh api --method GET \
  -H "Accept: application/vnd.github+json" \
  "repos/${GITHUB_REPOSITORY}/actions/artifacts/${artifact_id}/zip" \
  > "${raw_artifact}"
procwright_python staging_release_artifact.py extract \
  --archive "${raw_artifact}" \
  --directory build/maven-central \
  --version "${PROCWRIGHT_CONSUMER_VERSION}" \
  --digest "${artifact_digest}"

procwright_python verify_staging_evidence.py \
  --directory build/maven-central \
  --version "${PROCWRIGHT_CONSUMER_VERSION}" \
  --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
  --repository "${GITHUB_REPOSITORY}" \
  --stage-run-id "${stage_run_id}" \
  --artifact-id "${artifact_id}" \
  --artifact-name "${artifact_name}" \
  --artifact-digest "${artifact_digest}" \
  --github-output "${GITHUB_OUTPUT}" \
  --github-env "${GITHUB_ENV}"
