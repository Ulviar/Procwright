#!/usr/bin/env bash
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/trusted_context.sh"

: "${PROCWRIGHT_CONSUMER_VERSION:?}"
: "${RUNNER_TEMP:?}"
procwright_python release_contract.py version "${PROCWRIGHT_CONSUMER_VERSION}"

GRADLE_USER_HOME="${RUNNER_TEMP}/gradle-strict-normal" \
  ./gradlew \
    :procwright-consumer-examples:test \
    :procwright-integrations-consumer-example:check \
    :procwright-kotlin-consumer-example:check \
    --project-prop=procwright.javaRelease=17 \
    --project-prop=procwright.consumerVersion="${PROCWRIGHT_CONSUMER_VERSION}" \
    --dependency-verification=strict \
    --refresh-dependencies \
    --rerun-tasks \
    --no-daemon

GRADLE_USER_HOME="${RUNNER_TEMP}/gradle-strict-pom" \
  ./gradlew \
    :procwright-consumer-examples:test \
    :procwright-integrations-consumer-example:check \
    :procwright-kotlin-consumer-example:check \
    --project-prop=procwright.javaRelease=17 \
    --project-prop=procwright.consumerVersion="${PROCWRIGHT_CONSUMER_VERSION}" \
    --project-prop=procwright.consumerPomOnly=true \
    --dependency-verification=strict \
    --refresh-dependencies \
    --rerun-tasks \
    --no-daemon
