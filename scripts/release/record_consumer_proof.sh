#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${CENTRAL_DEPLOYMENT_ID:?}"
: "${GITHUB_WORKFLOW_SHA:?}"
: "${PROCWRIGHT_CONSUMER_VERSION:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${STAGE_RUN_ID:?}"
procwright_python release_contract.py version "${PROCWRIGHT_CONSUMER_VERSION}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"
procwright_python release_contract.py commit "${GITHUB_WORKFLOW_SHA}"
if [[ ! "${CENTRAL_DEPLOYMENT_ID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
  || [[ ! "${STAGE_RUN_ID}" =~ ^[0-9]+$ ]]; then
  printf 'Consumer proof identifiers are malformed\n' >&2
  exit 1
fi

proof_directory="build/central-consumer-smoke"
mkdir -p "${proof_directory}"
bundle_checksum="$(
  awk '{print $1}' \
    "build/maven-central/procwright-${PROCWRIGHT_CONSUMER_VERSION}-maven-central-bundle.zip.sha256"
)"
verification_metadata_checksum="$(sha256sum gradle/verification-metadata.xml | awk '{print $1}')"
if [[ ! "${bundle_checksum}" =~ ^[0-9a-f]{64}$ ]] \
  || [[ ! "${verification_metadata_checksum}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Consumer proof checksum is malformed\n' >&2
  exit 1
fi
{
  printf 'version=%s\n' "${PROCWRIGHT_CONSUMER_VERSION}"
  printf 'commit=%s\n' "${PROCWRIGHT_RELEASE_COMMIT}"
  printf 'bundle_sha256=%s\n' "${bundle_checksum}"
  printf 'verification_metadata_sha256=%s\n' "${verification_metadata_checksum}"
  printf 'central_deployment_id=%s\n' "${CENTRAL_DEPLOYMENT_ID}"
  printf 'central_state=PUBLISHED\n'
  printf 'stage_run_id=%s\n' "${STAGE_RUN_ID}"
  printf 'workflow_sha=%s\n' "${GITHUB_WORKFLOW_SHA}"
} > "${proof_directory}/provenance.txt"
