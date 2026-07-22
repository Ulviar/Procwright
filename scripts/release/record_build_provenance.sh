#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${GITHUB_WORKFLOW_REF:?}"
: "${GITHUB_WORKFLOW_SHA:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${PROCWRIGHT_STAGED_BUNDLE_SHA256:?}"
: "${PROCWRIGHT_UNSIGNED_HANDOFF_SHA256:?}"
procwright_python release_contract.py version "${PROCWRIGHT_RELEASE_VERSION}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"
if [[ ! "${PROCWRIGHT_UNSIGNED_HANDOFF_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Unsigned handoff digest is malformed\n' >&2
  exit 1
fi
if [[ ! "${PROCWRIGHT_STAGED_BUNDLE_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Staged bundle digest is malformed\n' >&2
  exit 1
fi

provenance="build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-provenance.txt"
{
  printf 'version=%s\n' "${PROCWRIGHT_RELEASE_VERSION}"
  printf 'commit=%s\n' "${PROCWRIGHT_RELEASE_COMMIT}"
  printf 'workflow_ref=%s\n' "${GITHUB_WORKFLOW_REF}"
  printf 'workflow_sha=%s\n' "${GITHUB_WORKFLOW_SHA}"
  printf 'unsigned_handoff_sha256=%s\n' "${PROCWRIGHT_UNSIGNED_HANDOFF_SHA256}"
  printf 'staged_bundle_sha256=%s\n' "${PROCWRIGHT_STAGED_BUNDLE_SHA256}"
  printf 'runner_os=%s\n' "${ImageOS:-unknown}"
  printf 'runner_image_version=%s\n' "${ImageVersion:-unknown}"
  python3 --version
  gpg --version | sed -n '1p'
} > "${provenance}"
