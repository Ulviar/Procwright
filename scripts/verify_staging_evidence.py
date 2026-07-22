#!/usr/bin/env python3
"""Validate downloaded staging evidence before any release consumer uses it."""

from __future__ import annotations

import argparse
import hmac
import os
import re
import stat
import sys
from pathlib import Path

from maven_central_portal import (
    MAX_RESPONSE_BYTES,
    PortalError,
    PortalIdentity,
    deployment_id_from_evidence,
    parse_deployment_evidence,
)
from maven_release_evidence import (
    MAX_BUNDLE_BYTES,
    MAX_MANIFEST_BYTES,
    EvidenceError,
    inspect_open_bundle,
    manifest_bytes,
    open_stable_regular_file,
    parse_manifest,
    read_stable_file,
    snapshot_replace,
)
from release_contract import (
    ReleaseContractError,
    require_canonical_version,
    require_commit_sha,
)
from release_handoff import (
    MAX_MANIFEST_BYTES as MAX_HANDOFF_MANIFEST_BYTES,
    MAX_SIGNATURE_BYTES,
    ReleaseHandoffError,
    verify_persisted_signing_evidence,
)


MAX_PROVENANCE_BYTES = 8 * 1024
MAX_CHECKSUM_BYTES = 256


class StagingEvidenceError(RuntimeError):
    """Downloaded staging evidence is incomplete, unsafe, or inconsistent."""


def evidence_schema(version: str) -> dict[str, tuple[str, int]]:
    version = require_canonical_version(version)
    prefix = f"procwright-{version}"
    return {
        "bundle": (f"{prefix}-maven-central-bundle.zip", MAX_BUNDLE_BYTES),
        "checksum": (
            f"{prefix}-maven-central-bundle.zip.sha256",
            MAX_CHECKSUM_BYTES,
        ),
        "manifest": (
            f"{prefix}-staged-bundle-payload-manifest.json",
            MAX_MANIFEST_BYTES,
        ),
        "signing_evidence": (
            f"{prefix}-signing-evidence.json",
            MAX_HANDOFF_MANIFEST_BYTES,
        ),
        "public_key": (f"{prefix}-signing-public-key.asc", MAX_SIGNATURE_BYTES),
        "verified_handoff": (
            f"{prefix}-verified-handoff.json",
            MAX_HANDOFF_MANIFEST_BYTES,
        ),
        "provenance": (f"{prefix}-provenance.txt", MAX_PROVENANCE_BYTES),
        "deployment": (f"{prefix}-central-deployment.json", MAX_RESPONSE_BYTES),
    }


def evidence_names(version: str) -> dict[str, str]:
    return {role: spec[0] for role, spec in evidence_schema(version).items()}


def _directory_identity(path: Path) -> tuple[int, int, int, int]:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise StagingEvidenceError("cannot inspect staging evidence directory") from error
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise StagingEvidenceError("staging evidence path must be a real directory")
    if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
        raise StagingEvidenceError("staging evidence directory has the wrong owner")
    if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        raise StagingEvidenceError(
            "staging evidence directory must not be group/world writable"
        )
    return metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_uid


def _require_exact_files(directory: Path, expected: set[str]) -> None:
    try:
        entries = list(os.scandir(directory))
    except OSError as error:
        raise StagingEvidenceError("cannot enumerate staging evidence") from error
    names = {entry.name for entry in entries}
    if len(names) != len(entries) or names != expected:
        raise StagingEvidenceError("staging evidence file set is not exact")
    for entry in entries:
        if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
            raise StagingEvidenceError("staging evidence contains a non-regular file")


def _parse_provenance(
    payload: bytes,
    *,
    version: str,
    commit: str,
    repository: str,
    bundle_sha256: str,
) -> None:
    try:
        text = payload.decode("ascii")
    except UnicodeDecodeError as error:
        raise StagingEvidenceError("staging provenance is not ASCII") from error
    if not text.endswith("\n") or "\r" in text or any(
        ord(character) < 32 and character != "\n" for character in text
    ):
        raise StagingEvidenceError("staging provenance contains unsafe control data")
    lines = text.splitlines()
    if len(lines) != 10:
        raise StagingEvidenceError("staging provenance line set is not exact")
    expected_prefixes = (
        "version=",
        "commit=",
        "workflow_ref=",
        "workflow_sha=",
        "unsigned_handoff_sha256=",
        "staged_bundle_sha256=",
        "runner_os=",
        "runner_image_version=",
    )
    if any(not lines[index].startswith(prefix) for index, prefix in enumerate(expected_prefixes)):
        raise StagingEvidenceError("staging provenance fields are missing or reordered")
    values = dict(line.split("=", 1) for line in lines[:8])
    expected_ref = (
        f"{repository}/.github/workflows/publish-maven-central.yml@refs/heads/main"
    )
    if (
        values.get("version") != version
        or values.get("commit") != commit
        or values.get("workflow_ref") != expected_ref
        or values.get("workflow_sha") != commit
        or values.get("staged_bundle_sha256") != bundle_sha256
        or re.fullmatch(r"[0-9a-f]{64}", values.get("unsigned_handoff_sha256", ""))
        is None
        or re.fullmatch(r"[0-9A-Za-z._+-]{1,100}", values.get("runner_os", ""))
        is None
        or re.fullmatch(
            r"[0-9A-Za-z._+-]{1,100}", values.get("runner_image_version", "")
        )
        is None
        or re.fullmatch(r"Python 3\.[0-9]+\.[0-9]+", lines[8]) is None
        or re.fullmatch(r"gpg \(GnuPG\) [0-9]+(?:\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?", lines[9])
        is None
    ):
        raise StagingEvidenceError("staging provenance identity is incorrect")


def verify_staging_evidence(
    directory_path: Path | str,
    version: str,
    commit: str,
    repository: str,
    stage_run_id: str,
    artifact_id: str,
    artifact_name: str,
    artifact_digest: str,
) -> tuple[str, str]:
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise StagingEvidenceError("GitHub repository identity is malformed")
    if re.fullmatch(r"[1-9][0-9]{0,19}", stage_run_id) is None:
        raise StagingEvidenceError("staging workflow run ID is malformed")
    if re.fullmatch(r"[1-9][0-9]{0,19}", artifact_id) is None:
        raise StagingEvidenceError("staging artifact ID is malformed")
    expected_artifact_name = f"procwright-{version}-{commit}-central-bundle"
    if artifact_name != expected_artifact_name:
        raise StagingEvidenceError("staging artifact name does not match release identity")
    if re.fullmatch(r"[0-9a-f]{64}", artifact_digest) is None:
        raise StagingEvidenceError("staging artifact service digest is malformed")
    directory = Path(directory_path)
    schema = evidence_schema(version)
    names = {role: spec[0] for role, spec in schema.items()}
    expected_names = set(names.values())
    before = _directory_identity(directory)
    _require_exact_files(directory, expected_names)
    limits = {role: spec[1] for role, spec in schema.items()}
    snapshots = {
        role: snapshot_replace(directory / filename, limits[role])
        for role, filename in names.items()
    }
    _require_exact_files(directory, expected_names)
    manifest_payload = read_stable_file(
        directory / names["manifest"], "staged bundle manifest", MAX_MANIFEST_BYTES
    )
    parse_manifest(manifest_payload, version)
    bundle = directory / names["bundle"]
    with open_stable_regular_file(bundle, "staged bundle", MAX_BUNDLE_BYTES) as source:
        size = os.fstat(source.fileno()).st_size
        inspected, _payloads = inspect_open_bundle(source, size, version)
        if not hmac.compare_digest(manifest_payload, manifest_bytes(inspected)):
            raise StagingEvidenceError(
                "staged manifest does not match the downloaded bundle"
            )
        digest = inspected["bundle"]["sha256"]
        if not hmac.compare_digest(digest, snapshots["bundle"]):
            raise StagingEvidenceError("staged bundle snapshot digest is inconsistent")
        checksum = read_stable_file(
            directory / names["checksum"], "bundle checksum", MAX_CHECKSUM_BYTES
        )
        expected_checksum = f"{digest}  {names['bundle']}\n".encode("ascii")
        if not hmac.compare_digest(checksum, expected_checksum):
            raise StagingEvidenceError("bundle checksum sidecar identity is incorrect")
        provenance = read_stable_file(
            directory / names["provenance"],
            "staging provenance",
            MAX_PROVENANCE_BYTES,
        )
        _parse_provenance(
            provenance,
            version=version,
            commit=commit,
            repository=repository,
            bundle_sha256=digest,
        )
        deployment_payload = read_stable_file(
            directory / names["deployment"],
            "Central deployment evidence",
            MAX_RESPONSE_BYTES,
        )
        deployment_id = deployment_id_from_evidence(deployment_payload)
        parse_deployment_evidence(
            deployment_payload,
            PortalIdentity(version, commit),
            expected_deployment_id=deployment_id,
            expected_state="VALIDATED",
            expected_bundle_sha256=digest,
        )
    signing_digest, _fingerprint = verify_persisted_signing_evidence(
        directory / names["bundle"],
        directory / names["signing_evidence"],
        directory / names["public_key"],
        directory / names["verified_handoff"],
        version,
        commit,
    )
    if not hmac.compare_digest(signing_digest, digest):
        raise StagingEvidenceError(
            "cryptographic signing evidence identifies another staged bundle"
        )
    _require_exact_files(directory, expected_names)
    if _directory_identity(directory) != before:
        raise StagingEvidenceError("staging evidence directory was replaced")
    return deployment_id, digest


def _write_output(
    path: Path,
    deployment_id: str,
    bundle_sha256: str,
    stage_run_id: str,
    artifact_id: str,
    artifact_name: str,
    artifact_digest: str,
) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"deployment_id={deployment_id}\n")
        output.write(f"bundle_sha256={bundle_sha256}\n")
        output.write(f"stage_run_id={stage_run_id}\n")
        output.write(f"stage_artifact_id={artifact_id}\n")
        output.write(f"stage_artifact_name={artifact_name}\n")
        output.write(f"stage_artifact_digest={artifact_digest}\n")


def _write_environment(
    path: Path,
    bundle_sha256: str,
    stage_run_id: str,
    artifact_id: str,
    artifact_name: str,
    artifact_digest: str,
) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"PROCWRIGHT_STAGED_BUNDLE_SHA256={bundle_sha256}\n")
        output.write(f"PROCWRIGHT_STAGING_RUN_ID={stage_run_id}\n")
        output.write(f"PROCWRIGHT_STAGING_ARTIFACT_ID={artifact_id}\n")
        output.write(f"PROCWRIGHT_STAGING_ARTIFACT_NAME={artifact_name}\n")
        output.write(f"PROCWRIGHT_STAGING_ARTIFACT_DIGEST={artifact_digest}\n")


def _safe_reason(error: BaseException) -> str:
    value = "".join(
        character if 32 <= ord(character) < 127 else "?" for character in str(error)
    )
    return value[:240] or "verification failed"


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--stage-run-id", required=True)
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--artifact-digest", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--github-env", type=Path)
    options = parser.parse_args(arguments)
    try:
        deployment_id, bundle_sha256 = verify_staging_evidence(
            options.directory,
            options.version,
            options.commit,
            options.repository,
            options.stage_run_id,
            options.artifact_id,
            options.artifact_name,
            options.artifact_digest,
        )
        _write_output(
            options.github_output,
            deployment_id,
            bundle_sha256,
            options.stage_run_id,
            options.artifact_id,
            options.artifact_name,
            options.artifact_digest,
        )
        if options.github_env is not None:
            _write_environment(
                options.github_env,
                bundle_sha256,
                options.stage_run_id,
                options.artifact_id,
                options.artifact_name,
                options.artifact_digest,
            )
    except (
        EvidenceError,
        PortalError,
        ReleaseContractError,
        ReleaseHandoffError,
        StagingEvidenceError,
    ) as error:
        print(f"Staging evidence failed: {_safe_reason(error)}", file=sys.stderr)
        return 1
    print("Validated exact bounded staging evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
