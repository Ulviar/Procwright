#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_CONSUMER_VERSION:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_CONSUMER_VERSION}"

normal_root="${RUNNER_TEMP}/verification-bootstrap-normal"
pom_root="${RUNNER_TEMP}/verification-bootstrap-pom"
candidates="$(pwd -P)/build/verification-bootstrap"
mkdir -p "${normal_root}" "${pom_root}" "${candidates}"
git archive --format=tar HEAD | tar -xf - --directory="${normal_root}"
git archive --format=tar HEAD | tar -xf - --directory="${pom_root}"

(
  cd "${normal_root}"
  GRADLE_USER_HOME="${RUNNER_TEMP}/gradle-verification-normal" \
    ./gradlew \
      :procwright-consumer-examples:test \
      :procwright-integrations-consumer-example:check \
      :procwright-kotlin-consumer-example:check \
      --project-prop=procwright.javaRelease=17 \
      --project-prop=procwright.consumerVersion="${PROCWRIGHT_CONSUMER_VERSION}" \
      --write-verification-metadata sha256 \
      --refresh-dependencies \
      --rerun-tasks \
      --no-daemon
)
cp "${normal_root}/gradle/verification-metadata.xml" "${candidates}/normal.xml"

(
  cd "${pom_root}"
  GRADLE_USER_HOME="${RUNNER_TEMP}/gradle-verification-pom" \
    ./gradlew \
      :procwright-consumer-examples:test \
      :procwright-integrations-consumer-example:check \
      :procwright-kotlin-consumer-example:check \
      --project-prop=procwright.javaRelease=17 \
      --project-prop=procwright.consumerVersion="${PROCWRIGHT_CONSUMER_VERSION}" \
      --project-prop=procwright.consumerPomOnly=true \
      --write-verification-metadata sha256 \
      --refresh-dependencies \
      --rerun-tasks \
      --no-daemon
)
cp "${pom_root}/gradle/verification-metadata.xml" "${candidates}/maven-pom.xml"
