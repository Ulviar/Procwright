#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${GITHUB_OUTPUT:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_RELEASE_VERSION}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"

procwright_python central_release_artifact.py seal \
  --directory "${RUNNER_TEMP}/procwright-central-downloads" \
  --bundle "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip" \
  --manifest "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-staged-bundle-payload-manifest.json" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --commit "${PROCWRIGHT_RELEASE_COMMIT}" \
  --github-output "${GITHUB_OUTPUT}"
