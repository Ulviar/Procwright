#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_PAGES_OUTPUT:?}"
: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${PROCWRIGHT_TARGET_ROOT:?}"
procwright_python release_contract.py version "${PROCWRIGHT_RELEASE_VERSION}"

workspace_root="$(pwd -P)"
if [[ ! -d "${PROCWRIGHT_TARGET_ROOT}" || -L "${PROCWRIGHT_TARGET_ROOT}" ]]; then
  printf 'Documentation target must be a real directory\n' >&2
  exit 1
fi
target_root="$(cd -- "${PROCWRIGHT_TARGET_ROOT}" && pwd -P)"
pages_output="${workspace_root}/${PROCWRIGHT_PAGES_OUTPUT}"
case "${pages_output}" in
  "${workspace_root}/build/public-docs") ;;
  *)
    printf 'Pages output must resolve to build/public-docs\n' >&2
    exit 1
    ;;
esac
if [[ -e "${pages_output}" || -L "${pages_output}" ]]; then
  printf 'Pages output already exists before the trusted build wrapper runs\n' >&2
  exit 1
fi

(
  cd -- "${target_root}"
  ./gradlew publicDocsCheck \
    --project-prop=procwright.javaRelease=17 \
    --project-prop=procwright.version="${PROCWRIGHT_RELEASE_VERSION}" \
    --no-daemon
)
target_output="${target_root}/build/public-docs"
if [[ ! -d "${target_output}" || -L "${target_output}" ]]; then
  printf 'Strict documentation build did not produce a real output directory\n' >&2
  exit 1
fi
mkdir -p -- "${workspace_root}/build"
cp -R -- "${target_output}" "${pages_output}"
