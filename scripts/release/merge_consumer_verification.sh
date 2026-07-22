#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_CONSUMER_VERSION:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_CONSUMER_VERSION}"
./gradlew mergeCentralConsumerVerificationMetadata \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.consumerVersion="${PROCWRIGHT_CONSUMER_VERSION}" \
  --project-prop=procwright.verificationCandidateNormal="$(pwd -P)/build/verification-bootstrap/normal.xml" \
  --project-prop=procwright.verificationCandidatePom="$(pwd -P)/build/verification-bootstrap/maven-pom.xml" \
  --project-prop=procwright.verificationArtifacts="${RUNNER_TEMP}/procwright-central-downloads" \
  --no-daemon
