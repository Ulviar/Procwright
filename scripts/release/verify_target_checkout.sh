#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_RELEASE_COMMIT:?}"
: "${PROCWRIGHT_TARGET_ROOT:?}"
procwright_python release_contract.py commit "${PROCWRIGHT_RELEASE_COMMIT}"

if [[ ! -d "${PROCWRIGHT_TARGET_ROOT}" || -L "${PROCWRIGHT_TARGET_ROOT}" ]]; then
  printf 'Target checkout must be a real directory: %s\n' "${PROCWRIGHT_TARGET_ROOT}" >&2
  exit 1
fi
target_root="$(cd -- "${PROCWRIGHT_TARGET_ROOT}" && pwd -P)"
if [[ "${target_root}" == "${PROCWRIGHT_TRUSTED_ROOT_RESOLVED}" ]]; then
  printf 'Trusted control and target checkout must be separate directories\n' >&2
  exit 1
fi
repository_root="$(git -C "${target_root}" rev-parse --show-toplevel)"
checked_out_commit="$(git -C "${target_root}" rev-parse 'HEAD^{commit}')"
if [[ "${repository_root}" != "${target_root}" ]]; then
  printf 'Target path is not the repository root: %s\n' "${target_root}" >&2
  exit 1
fi
if [[ "${checked_out_commit}" != "${PROCWRIGHT_RELEASE_COMMIT}" ]]; then
  printf 'Target checkout %s does not match requested %s\n' \
    "${checked_out_commit}" "${PROCWRIGHT_RELEASE_COMMIT}" >&2
  exit 1
fi
if ! target_status="$(git -C "${target_root}" status --porcelain=v1 --untracked-files=all)"; then
  printf 'Cannot inspect target checkout status\n' >&2
  exit 1
fi
if [[ -n "${target_status}" ]]; then
  printf 'Target checkout is not clean before execution\n' >&2
  exit 1
fi
