#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${GITHUB_EVENT_NAME:?}"
: "${GITHUB_REF:?}"
: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_SHA:?}"
: "${GITHUB_WORKFLOW_SHA:?}"
: "${GH_TOKEN:?}"
: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_TRUSTED_ROOT:?}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"
procwright_python release_contract.py commit "${GITHUB_SHA}"
procwright_python release_contract.py commit "${GITHUB_WORKFLOW_SHA}"

# A release is eligible only at the exact current main commit whose workflow
# definition is executing. Historical ancestors are intentionally unsupported.
if [[ "${GITHUB_EVENT_NAME}" != "workflow_dispatch" \
  || "${GITHUB_REF}" != "refs/heads/main" \
  || "${GITHUB_SHA}" != "${GITHUB_WORKFLOW_SHA}" \
  || "${PROCWRIGHT_RELEASE_COMMIT}" != "${GITHUB_WORKFLOW_SHA}" ]]; then
  printf 'Release commit, main head, and trusted workflow revision must be identical\n' >&2
  exit 1
fi
if [[ ! -d "${PROCWRIGHT_TRUSTED_ROOT}" || -L "${PROCWRIGHT_TRUSTED_ROOT}" ]]; then
  printf 'Trusted control checkout must be a real directory\n' >&2
  exit 1
fi
trusted_root="$(cd -- "${PROCWRIGHT_TRUSTED_ROOT}" && pwd -P)"
if [[ "${trusted_root}" != "${PROCWRIGHT_TRUSTED_ROOT_RESOLVED}" ]]; then
  printf 'Provenance script does not belong to the declared trusted checkout\n' >&2
  exit 1
fi
trusted_repository_root="$(git -C "${trusted_root}" rev-parse --show-toplevel)"
trusted_commit="$(git -C "${trusted_root}" rev-parse 'HEAD^{commit}')"
if [[ "${trusted_repository_root}" != "${trusted_root}" \
  || "${trusted_commit}" != "${GITHUB_WORKFLOW_SHA}" ]]; then
  printf 'Trusted checkout does not match the workflow revision\n' >&2
  exit 1
fi
if ! trusted_status="$(git -C "${trusted_root}" status --porcelain=v1 --untracked-files=all)"; then
  printf 'Cannot inspect trusted control checkout status\n' >&2
  exit 1
fi
if [[ -n "${trusted_status}" ]]; then
  printf 'Trusted control checkout is not clean\n' >&2
  exit 1
fi

procwright_python verify_release_ci_run.py
