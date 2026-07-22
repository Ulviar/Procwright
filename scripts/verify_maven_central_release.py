#!/usr/bin/env python3
"""Verify the complete persistent Maven Central evidence for docs recovery."""

from __future__ import annotations

import argparse
import os
import sys
import time
import urllib.request
from collections.abc import Callable
from pathlib import Path

from maven_central_repository import (
    MAX_DOWNLOAD_ATTEMPTS,
    RepositoryEvidenceError,
    UrlOpener,
    canonical_artifact_url,
    download_release,
)
from maven_release_evidence import (
    EvidenceError,
    artifact_size_limit,
    validate_release_payloads,
)
from openpgp_verification import (
    OpenPgpVerificationError,
    verify_release_signatures,
)
from release_contract import (
    ReleaseContractError,
    artifact_identity,
    expected_release_artifacts,
    expected_release_paths,
    require_canonical_version,
)


class ReleaseEvidenceError(RuntimeError):
    """Maven Central did not provide valid manual-recovery evidence."""


def validate_version(version: str) -> str:
    try:
        return require_canonical_version(version)
    except ReleaseContractError as error:
        raise ReleaseEvidenceError(str(error)) from error


def artifact_url(path: str, version: str) -> str:
    try:
        return canonical_artifact_url(path, version)
    except (ReleaseContractError, RepositoryEvidenceError) as error:
        raise ReleaseEvidenceError(str(error)) from error


def verify_release(
    version: str,
    *,
    approved_public_key: bytes,
    approved_fingerprint: str,
    opener: UrlOpener = urllib.request.urlopen,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
    retry_delay_seconds: float = 5.0,
    timeout_seconds: float = 30.0,
    overall_deadline_seconds: float = 10 * 60.0,
    sleeper: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> dict[str, bytes]:
    """Download and semantically validate every file in the closed release set."""
    try:
        version = require_canonical_version(version)
        payloads = download_release(
            version,
            size_limit=lambda path: artifact_size_limit(
                artifact_identity(path, version)
            ),
            opener=opener,
            attempts=attempts,
            retry_delay_seconds=retry_delay_seconds,
            request_timeout_seconds=timeout_seconds,
            overall_deadline_seconds=overall_deadline_seconds,
            sleeper=sleeper,
            clock=clock,
        )
        validate_release_payloads(payloads, version)
        if tuple(sorted(payloads)) != expected_release_paths(version):
            raise ReleaseEvidenceError(
                "recovery evidence does not equal the closed release artifact set"
            )
        base_paths = tuple(
            artifact.path
            for artifact in expected_release_artifacts(version)
            if artifact.role == "base"
        )
        verify_release_signatures(
            payloads,
            base_paths,
            approved_public_key,
            approved_fingerprint,
        )
        return payloads
    except (
        EvidenceError,
        OpenPgpVerificationError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        raise ReleaseEvidenceError(str(error)) from error


def _safe_reason(error: BaseException) -> str:
    reason = "".join(
        character if 32 <= ord(character) < 127 else "?" for character in str(error)
    )
    return reason[:240] or "verification failed"


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="released SemVer version without a leading v")
    parser.add_argument("--attempts", type=int, default=MAX_DOWNLOAD_ATTEMPTS)
    parser.add_argument("--retry-delay-seconds", type=float, default=5.0)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--deadline-seconds", type=float, default=10 * 60.0)
    parser.add_argument(
        "--signing-public-key",
        type=Path,
        help="approved armored public signing key (defaults to PROCWRIGHT_SIGNING_PUBLIC_KEY)",
    )
    parser.add_argument(
        "--signing-fingerprint",
        default=os.environ.get("PROCWRIGHT_SIGNING_FINGERPRINT", ""),
        help="approved uppercase primary-key fingerprint",
    )
    options = parser.parse_args(arguments)
    try:
        approved_public_key = (
            options.signing_public_key.read_bytes()
            if options.signing_public_key is not None
            else os.environ.get("PROCWRIGHT_SIGNING_PUBLIC_KEY", "").encode("utf-8")
        )
        payloads = verify_release(
            options.version,
            approved_public_key=approved_public_key,
            approved_fingerprint=options.signing_fingerprint,
            attempts=options.attempts,
            retry_delay_seconds=options.retry_delay_seconds,
            timeout_seconds=options.timeout_seconds,
            overall_deadline_seconds=options.deadline_seconds,
        )
    except (OSError, ReleaseEvidenceError) as error:
        print(
            f"Maven Central release verification failed: {_safe_reason(error)}",
            file=sys.stderr,
        )
        return 1
    print(
        f"Verified all {len(payloads)} published Procwright files, including "
        "classifiers, signatures, checksums, POMs, and Gradle metadata."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
