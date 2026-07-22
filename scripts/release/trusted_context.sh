#!/usr/bin/env bash

procwright_release_script_directory="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
PROCWRIGHT_TRUSTED_ROOT_RESOLVED="$(
  cd -- "${procwright_release_script_directory}/../.." >/dev/null 2>&1
  pwd -P
)"
readonly PROCWRIGHT_TRUSTED_ROOT_RESOLVED
unset procwright_release_script_directory

procwright_python() {
  local script_name="${1:?Python script name is required}"
  shift
  python3 "${PROCWRIGHT_TRUSTED_ROOT_RESOLVED}/scripts/${script_name}" "$@"
}
