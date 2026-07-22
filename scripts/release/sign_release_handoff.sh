#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_RELEASE_VERSION:?}"
: "${PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT:?}"
: "${RUNNER_TEMP:?}"
: "${SIGNING_KEY:?}"
: "${SIGNING_PASSWORD:?}"
procwright_python release_contract.py version "${PROCWRIGHT_RELEASE_VERSION}"
if [[ ! "${PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT}" =~ ^[0-9A-F]{40}$ ]]; then
  printf 'Approved signing fingerprint must be 40 uppercase hexadecimal characters\n' >&2
  exit 1
fi

repository="build/maven-central/repository"
bundle="build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip"
gnupg_home="${RUNNER_TEMP}/procwright-release-gnupg"
key_file="${RUNNER_TEMP}/procwright-release-signing-key.asc"
passphrase_file="${RUNNER_TEMP}/procwright-release-signing-passphrase"

cleanup() {
  rm -rf -- "${gnupg_home}" "${key_file}" "${passphrase_file}"
}
trap cleanup EXIT

if [[ -e "${gnupg_home}" || -L "${gnupg_home}" ]]; then
  printf 'Temporary signing home already exists\n' >&2
  exit 1
fi
umask 077
mkdir -- "${gnupg_home}"
printf '%s\n' "${SIGNING_KEY}" > "${key_file}"
printf '%s' "${SIGNING_PASSWORD}" > "${passphrase_file}"
unset SIGNING_KEY SIGNING_PASSWORD
export GNUPGHOME="${gnupg_home}"
gpg --batch --quiet --import "${key_file}"

secret_key_count="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "sec" { count++ } END { print count + 0 }')"
fingerprint="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "sec" { primary = 1; next } primary && $1 == "fpr" { print $10; exit }')"
if [[ "${secret_key_count}" != "1" || ! "${fingerprint}" =~ ^[0-9A-F]{40}$ ]]; then
  printf 'Signing material must contain exactly one 40-character secret-key fingerprint\n' >&2
  exit 1
fi
if [[ "${fingerprint}" != "${PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT}" ]]; then
  printf 'Signing key does not match the independently approved fingerprint\n' >&2
  exit 1
fi

procwright_python release_handoff.py sign-finalize \
  --repository "${repository}" \
  --manifest "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-verified-unsigned-manifest.json" \
  --bundle "${bundle}" \
  --version "${PROCWRIGHT_RELEASE_VERSION}" \
  --gnupg-home "${gnupg_home}" \
  --fingerprint "${PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT}" \
  --passphrase-file "${passphrase_file}"
