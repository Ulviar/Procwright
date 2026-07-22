#!/usr/bin/env python3
"""Create and independently verify the unprivileged-to-privileged release handoff."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Callable

from maven_release_evidence import (
    MAX_CENTRAL_DIRECTORY_BYTES,
    EvidenceError,
    atomic_write,
    inspect_bundle,
    open_stable_regular_file,
    raw_central_directory,
    read_stable_file,
    validate_armored_signature,
    validate_base_payloads,
    validate_release_payloads,
)
from release_contract import (
    CHECKSUMS as RELEASE_CHECKSUMS,
    ReleaseContractError,
    artifact_identity,
    expected_base_paths,
    require_canonical_version,
    require_commit_sha,
)
from verify_local_maven_central_bundle import (
    LocalBundleError,
    verify_unsigned_repository,
)


CHECKSUMS = tuple((algorithm, algorithm) for algorithm, _length in RELEASE_CHECKSUMS)
SCHEMA = 2
VERIFIED_HANDOFF_SCHEMA = "procwright-verified-handoff/v1"
SIGNING_EVIDENCE_SCHEMA = "procwright-signing-evidence/v1"
MAX_FILES = 256
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_BYTES = 300 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_SIGNATURE_BYTES = 1024 * 1024
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


class ReleaseHandoffError(RuntimeError):
    """The release handoff violates its closed structural or byte contract."""


@dataclass(frozen=True)
class VerifiedGithubHandoff:
    """Exact bytes and identities recovered from one raw GitHub artifact ZIP."""

    artifact_digest: str
    artifact_size: int
    manifest: dict
    manifest_bytes: bytes
    payloads: dict[str, bytes]
    receipt: dict


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseHandoffError(f"JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def _parse_manifest(path: Path) -> dict:
    payload = _read_regular_file(path, MAX_MANIFEST_BYTES, "handoff manifest")
    try:
        value = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseHandoffError(f"handoff manifest is not valid UTF-8 JSON: {error}") from error
    if not isinstance(value, dict):
        raise ReleaseHandoffError("handoff manifest must be a JSON object")
    canonical = (
        json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8")
    if payload != canonical:
        raise ReleaseHandoffError("handoff manifest is not canonical deterministic JSON")
    return value


def _require_fixed_path(path: Path, relative: str, description: str) -> Path:
    expected = (Path.cwd() / relative).resolve(strict=False)
    actual = path.resolve(strict=False)
    if actual != expected:
        raise ReleaseHandoffError(f"{description} must resolve exactly to {relative}")
    return actual


def _require_real_directory(path: Path, description: str) -> Path:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ReleaseHandoffError(f"cannot inspect {description}: {error}") from error
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise ReleaseHandoffError(f"{description} must be a real directory")
    return path.resolve(strict=True)


def _read_regular_file(path: Path, limit: int, description: str) -> bytes:
    try:
        return read_stable_file(path, description, limit)
    except EvidenceError as error:
        raise ReleaseHandoffError(str(error)) from error


def expected_payload_names(version: str) -> tuple[str, ...]:
    return expected_base_paths(version)


def _walk_files(root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}

    def visit(directory: Path) -> None:
        try:
            entries = sorted(os.scandir(directory), key=lambda entry: entry.name)
        except OSError as error:
            raise ReleaseHandoffError(f"cannot scan release repository: {error}") from error
        for entry in entries:
            path = Path(entry.path)
            try:
                if entry.is_symlink():
                    raise ReleaseHandoffError(f"release repository contains symlink: {path}")
                if entry.is_dir(follow_symlinks=False):
                    visit(path)
                    continue
                if not entry.is_file(follow_symlinks=False):
                    raise ReleaseHandoffError(f"release repository contains special file: {path}")
            except OSError as error:
                raise ReleaseHandoffError(f"cannot inspect release repository entry: {error}") from error
            relative = path.relative_to(root).as_posix()
            if relative in result:
                raise ReleaseHandoffError(f"duplicate release repository path: {relative}")
            result[relative] = path
            if len(result) > MAX_FILES:
                raise ReleaseHandoffError("release repository exceeds its file-count limit")

    visit(root)
    return result


def _collect_payloads(root: Path, version: str, signatures: bool = False) -> dict[str, bytes]:
    root = _require_real_directory(root, "release repository")
    expected = set(expected_payload_names(version))
    expected_files = expected | ({name + ".asc" for name in expected} if signatures else set())
    files = _walk_files(root)
    observed = set(files)
    if observed != expected_files:
        raise ReleaseHandoffError(
            f"release repository file set mismatch; missing={sorted(expected_files - observed)}, "
            f"extra={sorted(observed - expected_files)}"
        )
    payloads: dict[str, bytes] = {}
    total = 0
    for name in sorted(observed):
        limit = MAX_SIGNATURE_BYTES if name.endswith(".asc") else MAX_FILE_BYTES
        payload = _read_regular_file(files[name], limit, f"release payload {name}")
        if not payload:
            raise ReleaseHandoffError(f"release payload must not be empty: {name}")
        if name.endswith(".asc"):
            try:
                validate_armored_signature(payload)
            except EvidenceError as error:
                raise ReleaseHandoffError(
                    f"release signature is structurally invalid: {name}"
                ) from error
        total += len(payload)
        if total > MAX_TOTAL_BYTES:
            raise ReleaseHandoffError("release repository exceeds its total-size limit")
        payloads[name] = payload
    try:
        validate_base_payloads(
            {name: payload for name, payload in payloads.items() if not name.endswith(".asc")},
            version,
        )
    except EvidenceError as error:
        raise ReleaseHandoffError(f"release semantic identity failed: {error}") from error
    return payloads


def _entry(name: str, payload: bytes, version: str) -> dict:
    identity = artifact_identity(name, version)
    return {
        "baseSuffix": identity.base_suffix,
        "module": identity.module,
        "path": name,
        "role": "base",
        "sha256": hashlib.sha256(payload).hexdigest(),
        "size": len(payload),
    }


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    return info


def _write_archive(path: Path, payloads: dict[str, bytes]) -> None:
    if path.exists() or path.is_symlink():
        raise ReleaseHandoffError(f"archive output already exists: {path}")
    try:
        with zipfile.ZipFile(path, "x", allowZip64=False) as archive:
            for name, payload in sorted(payloads.items()):
                archive.writestr(_zip_info(name), payload)
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        raise ReleaseHandoffError(f"cannot create release archive: {error}") from error
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ReleaseHandoffError("release archive exceeds its size limit")


def _inspect_archive_bytes(archive_bytes: bytes, version: str) -> dict[str, bytes]:
    expected = set(expected_payload_names(version))
    payloads: dict[str, bytes] = {}
    source = io.BytesIO(archive_bytes)
    try:
        raw_entries = raw_central_directory(
            source,
            len(archive_bytes),
            maximum_entries=len(expected),
            maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
            maximum_entry_bytes=MAX_FILE_BYTES,
            maximum_total_compressed_bytes=MAX_TOTAL_BYTES,
            maximum_total_bytes=MAX_TOTAL_BYTES,
            allow_directories=False,
        )
        source.seek(0)
        with zipfile.ZipFile(source) as archive:
            if archive.comment:
                raise ReleaseHandoffError("unsigned release archive must not have a comment")
            infos = archive.infolist()
            if len(infos) != len(raw_entries) or len(infos) != len(expected):
                raise ReleaseHandoffError("unsigned release archive has an unexpected entry count")
            names = [raw.name for raw in raw_entries]
            if len(names) != len(set(names)) or set(names) != expected:
                raise ReleaseHandoffError("unsigned release archive has unexpected or duplicate paths")
            total = 0
            for raw, info in zip(raw_entries, infos, strict=True):
                if info.orig_filename != raw.name or info.filename != raw.name:
                    raise ReleaseHandoffError(
                        "zipfile changed or truncated a raw unsigned entry name"
                    )
                pure = PurePosixPath(info.filename)
                mode = (info.external_attr >> 16) & 0o170000
                if (
                    pure.is_absolute()
                    or ".." in pure.parts
                    or "\\" in info.filename
                    or info.is_dir()
                    or info.flag_bits & 0x1
                    or info.compress_type != zipfile.ZIP_STORED
                    or info.compress_size != info.file_size
                    or info.file_size > MAX_FILE_BYTES
                    or info.extra
                    or mode != stat.S_IFREG
                ):
                    raise ReleaseHandoffError(f"unsafe unsigned release archive entry: {info.filename}")
                with archive.open(info) as source:
                    payload = source.read(MAX_FILE_BYTES + 1)
                if len(payload) != info.file_size or len(payload) > MAX_FILE_BYTES or not payload:
                    raise ReleaseHandoffError(f"invalid unsigned release payload: {info.filename}")
                total += len(payload)
                if total > MAX_TOTAL_BYTES:
                    raise ReleaseHandoffError("unsigned release archive exceeds its total-size limit")
                payloads[info.filename] = payload
    except (EvidenceError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        if isinstance(error, ReleaseHandoffError):
            raise
        raise ReleaseHandoffError(f"cannot inspect unsigned release archive: {error}") from error
    try:
        validate_base_payloads(payloads, version)
    except EvidenceError as error:
        raise ReleaseHandoffError(f"release semantic identity failed: {error}") from error
    return payloads


def prepare(
    repository: Path,
    output: Path,
    version: str,
    commit: str,
    workflow_sha: str,
) -> None:
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    workflow_sha = require_commit_sha(workflow_sha)
    if commit != workflow_sha:
        raise ReleaseHandoffError("release commit and workflow SHA must be identical")
    repository = _require_fixed_path(
        repository,
        ".procwright-target/build/maven-central/repository",
        "unsigned repository",
    )
    output = _require_fixed_path(
        output, ".procwright-target/build/release-handoff", "handoff output"
    )
    try:
        payloads = verify_unsigned_repository(repository, version)
    except LocalBundleError as error:
        raise ReleaseHandoffError(
            f"unsigned Gradle repository failed its exact postcondition: {error}"
        ) from error
    if output.exists() or output.is_symlink():
        raise ReleaseHandoffError("handoff output must not exist before packaging")
    output.mkdir(parents=True)
    archive = output / f"procwright-{version}-unsigned.zip"
    _write_archive(archive, payloads)
    archive_bytes = _read_regular_file(archive, MAX_ARCHIVE_BYTES, "unsigned release archive")
    digest = hashlib.sha256(archive_bytes).hexdigest()
    manifest = {
        "archive": {"name": archive.name, "sha256": digest, "size": len(archive_bytes)},
        "commit": commit,
        "entries": [
            _entry(name, payload, version) for name, payload in sorted(payloads.items())
        ],
        "schema": SCHEMA,
        "version": version,
        "workflow_sha": workflow_sha,
    }
    atomic_write(
        output / f"procwright-{version}-unsigned-manifest.json",
        (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode(
            "utf-8"
        ),
        maximum_size=MAX_MANIFEST_BYTES,
    )
    atomic_write(
        output / f"procwright-{version}-unsigned.zip.sha256",
        f"{digest}  {archive.name}\n".encode("ascii"),
        maximum_size=256,
    )


def _safe_create_parents(root: Path, relative: PurePosixPath) -> Path:
    current = root
    for part in relative.parts[:-1]:
        current /= part
        if current.exists() or current.is_symlink():
            metadata = current.lstat()
            if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                raise ReleaseHandoffError(f"unsafe extraction parent: {current}")
        else:
            current.mkdir()
    return current / relative.name


def verified_manifest_path(version: str) -> Path:
    version = require_canonical_version(version)
    return Path(
        f"build/maven-central/procwright-{version}-verified-unsigned-manifest.json"
    )


def verified_receipt_path(version: str) -> Path:
    version = require_canonical_version(version)
    return Path(f"build/maven-central/procwright-{version}-verified-handoff.json")


def signing_evidence_path(version: str) -> Path:
    version = require_canonical_version(version)
    return Path(f"build/maven-central/procwright-{version}-signing-evidence.json")


def signing_public_key_path(version: str) -> Path:
    version = require_canonical_version(version)
    return Path(f"build/maven-central/procwright-{version}-signing-public-key.asc")


def _require_github_artifact_identity(
    version: str,
    commit: str,
    digest: str,
    artifact_id: str,
    artifact_name: str,
) -> tuple[str, str, str]:
    if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise ReleaseHandoffError(
            "GitHub artifact digest must be a canonical lowercase SHA-256 value"
        )
    if re.fullmatch(r"[1-9][0-9]{0,19}", artifact_id) is None:
        raise ReleaseHandoffError("GitHub artifact ID must be a canonical positive integer")
    expected_name = f"procwright-{version}-{commit}-unsigned-release"
    if artifact_name != expected_name:
        raise ReleaseHandoffError("GitHub artifact name does not match release identity")
    return digest, artifact_id, artifact_name


def _handoff_component_names(version: str) -> tuple[str, str, str]:
    return (
        f"procwright-{version}-unsigned.zip",
        f"procwright-{version}-unsigned.zip.sha256",
        f"procwright-{version}-unsigned-manifest.json",
    )


def _resolve_raw_github_artifact(path: Path) -> Path:
    directory = _require_fixed_path(
        path, ".procwright-handoff", "raw GitHub handoff artifact directory"
    )
    directory = _require_real_directory(
        directory, "raw GitHub handoff artifact directory"
    )
    files = _walk_files(directory)
    if len(files) != 1:
        raise ReleaseHandoffError(
            "raw GitHub handoff artifact directory must contain exactly one file"
        )
    relative, artifact = next(iter(files.items()))
    if PurePosixPath(relative).parent != PurePosixPath("."):
        raise ReleaseHandoffError(
            "raw GitHub handoff artifact must be a direct child of its directory"
        )
    return artifact


def _verify_handoff_components(
    components: dict[str, bytes],
    version: str,
    commit: str,
    workflow_sha: str,
) -> tuple[dict, bytes, dict[str, bytes], str]:
    archive_name, checksum_name, manifest_name = _handoff_component_names(version)
    if set(components) != {archive_name, checksum_name, manifest_name}:
        raise ReleaseHandoffError("GitHub artifact has an unexpected handoff file set")
    archive_bytes = components[archive_name]
    digest = hashlib.sha256(archive_bytes).hexdigest()
    if components[checksum_name] != f"{digest}  {archive_name}\n".encode("ascii"):
        raise ReleaseHandoffError("unsigned release checksum does not match archive bytes")
    manifest_bytes = components[manifest_name]
    try:
        manifest = json.loads(manifest_bytes, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseHandoffError(
            f"handoff manifest is not valid UTF-8 JSON: {error}"
        ) from error
    if not isinstance(manifest, dict) or manifest_bytes != (
        json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8"):
        raise ReleaseHandoffError("handoff manifest is not canonical deterministic JSON")
    payloads = _inspect_archive_bytes(archive_bytes, version)
    expected_manifest = {
        "archive": {
            "name": archive_name,
            "sha256": digest,
            "size": len(archive_bytes),
        },
        "commit": commit,
        "entries": [
            _entry(name, payload, version) for name, payload in sorted(payloads.items())
        ],
        "schema": SCHEMA,
        "version": version,
        "workflow_sha": workflow_sha,
    }
    if manifest != expected_manifest:
        raise ReleaseHandoffError("unsigned release manifest does not match verified archive bytes")
    return manifest, manifest_bytes, payloads, digest


def _inspect_github_handoff(
    artifact_path: Path,
    version: str,
    commit: str,
    workflow_sha: str,
    artifact_digest: str,
    artifact_id: str,
    artifact_name: str,
) -> VerifiedGithubHandoff:
    artifact_digest, artifact_id, artifact_name = _require_github_artifact_identity(
        version, commit, artifact_digest, artifact_id, artifact_name
    )
    artifact_path = _resolve_raw_github_artifact(artifact_path)
    artifact_bytes = _read_regular_file(
        artifact_path, MAX_ARCHIVE_BYTES, "raw GitHub handoff artifact"
    )
    observed_digest = hashlib.sha256(artifact_bytes).hexdigest()
    if not hmac.compare_digest(observed_digest, artifact_digest):
        raise ReleaseHandoffError("raw GitHub artifact digest does not match job output")
    expected_names = set(_handoff_component_names(version))
    components: dict[str, bytes] = {}
    source = io.BytesIO(artifact_bytes)
    try:
        raw_entries = raw_central_directory(
            source,
            len(artifact_bytes),
            maximum_entries=len(expected_names),
            maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
            maximum_entry_bytes=MAX_ARCHIVE_BYTES,
            maximum_total_compressed_bytes=MAX_ARCHIVE_BYTES,
            maximum_total_bytes=MAX_ARCHIVE_BYTES,
            allow_directories=False,
            allow_signed_data_descriptors=True,
        )
        source.seek(0)
        with zipfile.ZipFile(source) as archive:
            infos = archive.infolist()
            if len(infos) != len(raw_entries) or {
                entry.name for entry in raw_entries
            } != expected_names:
                raise ReleaseHandoffError(
                    "raw GitHub artifact has unexpected or duplicate paths"
                )
            for raw, info in zip(raw_entries, infos, strict=True):
                file_type = stat.S_IFMT(info.external_attr >> 16)
                if (
                    info.filename != raw.name
                    or info.orig_filename != raw.name
                    or info.is_dir()
                    or file_type not in (0, stat.S_IFREG)
                    or info.flag_bits & 0x1
                ):
                    raise ReleaseHandoffError("raw GitHub artifact contains an unsafe entry")
                limit = (
                    MAX_ARCHIVE_BYTES
                    if raw.name.endswith("-unsigned.zip")
                    else MAX_MANIFEST_BYTES
                )
                with archive.open(info) as entry:
                    payload = entry.read(limit + 1)
                if not payload or len(payload) > limit or len(payload) != info.file_size:
                    raise ReleaseHandoffError("raw GitHub artifact entry has invalid bounds")
                components[raw.name] = payload
    except (EvidenceError, OSError, RuntimeError, zipfile.BadZipFile) as error:
        if isinstance(error, ReleaseHandoffError):
            raise
        raise ReleaseHandoffError(f"cannot inspect raw GitHub artifact: {error}") from error
    manifest, manifest_bytes, payloads, unsigned_digest = _verify_handoff_components(
        components, version, commit, workflow_sha
    )
    receipt = {
        "commit": commit,
        "entries": manifest["entries"],
        "githubArtifact": {
            "id": artifact_id,
            "name": artifact_name,
            "sha256": artifact_digest,
            "size": len(artifact_bytes),
        },
        "schema": VERIFIED_HANDOFF_SCHEMA,
        "unsignedArchive": {
            "name": manifest["archive"]["name"],
            "sha256": unsigned_digest,
            "size": manifest["archive"]["size"],
        },
        "unsignedManifest": {
            "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "size": len(manifest_bytes),
        },
        "version": version,
        "workflowSha": workflow_sha,
    }
    return VerifiedGithubHandoff(
        artifact_digest,
        len(artifact_bytes),
        manifest,
        manifest_bytes,
        payloads,
        receipt,
    )


def verify(
    handoff: Path,
    output_repository: Path,
    version: str,
    commit: str,
    workflow_sha: str,
    *,
    github_artifact_digest: str | None = None,
    github_artifact_id: str | None = None,
    github_artifact_name: str | None = None,
) -> str:
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    workflow_sha = require_commit_sha(workflow_sha)
    if commit != workflow_sha:
        raise ReleaseHandoffError("release commit and workflow SHA must be identical")
    output_repository = _require_fixed_path(
        output_repository, "build/maven-central/repository", "privileged repository output"
    )
    receipt: dict | None = None
    github_identity = (
        github_artifact_digest,
        github_artifact_id,
        github_artifact_name,
    )
    if all(value is None for value in github_identity):
        handoff = _require_fixed_path(handoff, ".procwright-handoff", "downloaded handoff")
        handoff = _require_real_directory(handoff, "downloaded handoff")
        expected_names = set(_handoff_component_names(version))
        files = _walk_files(handoff)
        if set(files) != expected_names:
            raise ReleaseHandoffError(
                "downloaded handoff file set mismatch; "
                f"expected={sorted(expected_names)}, got={sorted(files)}"
            )
        components = {
            name: _read_regular_file(
                files[name],
                MAX_ARCHIVE_BYTES if name.endswith("-unsigned.zip") else MAX_MANIFEST_BYTES,
                f"downloaded handoff component {name}",
            )
            for name in expected_names
        }
        manifest, manifest_bytes, payloads, digest = _verify_handoff_components(
            components, version, commit, workflow_sha
        )
    else:
        if any(value is None for value in github_identity):
            raise ReleaseHandoffError(
                "raw GitHub artifact verification requires digest, ID, and name"
            )
        handoff = _require_fixed_path(
            handoff, ".procwright-handoff", "raw GitHub handoff artifact directory"
        )
        verified = _inspect_github_handoff(
            handoff,
            version,
            commit,
            workflow_sha,
            github_artifact_digest,
            github_artifact_id,
            github_artifact_name,
        )
        manifest = verified.manifest
        manifest_bytes = verified.manifest_bytes
        payloads = verified.payloads
        digest = manifest["archive"]["sha256"]
        receipt = verified.receipt
    if output_repository.exists() or output_repository.is_symlink():
        raise ReleaseHandoffError("privileged repository output must not exist before verification")
    try:
        output_repository.mkdir(parents=True, mode=0o700)
        for name, payload in sorted(payloads.items()):
            destination = _safe_create_parents(output_repository, PurePosixPath(name))
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            destination.chmod(0o400)
        manifest_output = verified_manifest_path(version)
        atomic_write(
            manifest_output,
            manifest_bytes,
            maximum_size=MAX_MANIFEST_BYTES,
        )
        manifest_output.chmod(0o400)
        if receipt is not None:
            receipt_output = verified_receipt_path(version)
            atomic_write(
                receipt_output,
                (json.dumps(receipt, sort_keys=True, separators=(",", ":")) + "\n").encode(
                    "utf-8"
                ),
                maximum_size=MAX_MANIFEST_BYTES,
            )
            receipt_output.chmod(0o400)
    except (EvidenceError, OSError, ReleaseHandoffError) as error:
        shutil.rmtree(output_repository, ignore_errors=True)
        for evidence_output in (
            verified_manifest_path(version),
            verified_receipt_path(version),
        ):
            if (
                evidence_output.exists()
                and evidence_output.is_file()
                and not evidence_output.is_symlink()
            ):
                evidence_output.chmod(0o600)
                evidence_output.unlink()
        if isinstance(error, ReleaseHandoffError):
            raise
        raise ReleaseHandoffError(
            "cannot persist immutable verified unsigned handoff snapshots"
        ) from error
    return digest


def _filesystem_identity(path: Path) -> tuple[int, ...]:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ReleaseHandoffError(f"cannot inspect captured release path: {path}") from error
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_ISLNK(metadata.st_mode)
        or metadata.st_nlink != 1
    ):
        raise ReleaseHandoffError(f"captured release path is not a private regular file: {path}")
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _seal_snapshot(path: Path) -> tuple[int, ...]:
    try:
        path.chmod(0o400)
    except OSError as error:
        raise ReleaseHandoffError("cannot seal a private signing snapshot") from error
    identity = _filesystem_identity(path)
    if stat.S_IMODE(identity[3]) != 0o400:
        raise ReleaseHandoffError("signing snapshot is not owner-only read-only")
    return identity


def _source_identities(repository: Path, payloads: dict[str, bytes]) -> dict[str, tuple[int, ...]]:
    return {
        name: _filesystem_identity(repository / PurePosixPath(name))
        for name in payloads
    }


def _guard_source_payloads(
    repository: Path,
    version: str,
    payloads: dict[str, bytes],
    identities: dict[str, tuple[int, ...]],
) -> None:
    observed = _collect_payloads(repository, version)
    if observed.keys() != payloads.keys():
        raise ReleaseHandoffError("captured release repository path set changed")
    for name, expected in payloads.items():
        path = repository / PurePosixPath(name)
        if _filesystem_identity(path) != identities[name] or not hmac.compare_digest(
            observed[name], expected
        ):
            raise ReleaseHandoffError(f"captured release payload changed: {name}")


def _private_snapshot_path(root: Path, relative: str) -> Path:
    current = root
    parts = PurePosixPath(relative).parts
    for part in parts[:-1]:
        current /= part
        if current.exists() or current.is_symlink():
            metadata = current.lstat()
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or stat.S_ISLNK(metadata.st_mode)
                or metadata.st_mode & 0o077
            ):
                raise ReleaseHandoffError("signing snapshot parent is not private")
        else:
            current.mkdir(mode=0o700)
    return current / parts[-1]


def _run_gpg(
    arguments: list[str],
    *,
    pass_fds: tuple[int, ...],
    environment: dict[str, str],
) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            arguments,
            check=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            pass_fds=pass_fds,
            env=environment,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ReleaseHandoffError("GPG execution failed") from error


def _sign_snapshot(
    snapshot: Path,
    passphrase_file: Path,
    fingerprint: str,
    environment: dict[str, str],
) -> bytes:
    try:
        with open_stable_regular_file(snapshot, "signing snapshot", MAX_FILE_BYTES) as source:
            with open_stable_regular_file(
                passphrase_file, "signing passphrase", MAX_SIGNATURE_BYTES
            ) as passphrase:
                if os.fstat(passphrase.fileno()).st_mode & 0o077:
                    raise ReleaseHandoffError("signing passphrase must be owner-only")
                result = _run_gpg(
                    [
                        "gpg",
                        "--batch",
                        "--yes",
                        "--quiet",
                        "--pinentry-mode",
                        "loopback",
                        "--passphrase-file",
                        f"/dev/fd/{passphrase.fileno()}",
                        "--local-user",
                        fingerprint,
                        "--armor",
                        "--output",
                        "-",
                        "--detach-sign",
                        f"/dev/fd/{source.fileno()}",
                    ],
                    pass_fds=(source.fileno(), passphrase.fileno()),
                    environment=environment,
                )
    except EvidenceError as error:
        raise ReleaseHandoffError(str(error)) from error
    if result.returncode != 0 or not result.stdout or len(result.stdout) > MAX_SIGNATURE_BYTES:
        raise ReleaseHandoffError("GPG could not create a bounded detached signature")
    try:
        validate_armored_signature(result.stdout)
    except EvidenceError as error:
        raise ReleaseHandoffError("GPG produced an invalid detached signature") from error
    return result.stdout


def _verify_snapshot_signature(
    snapshot: Path,
    signature: Path,
    fingerprint: str,
    environment: dict[str, str],
) -> None:
    try:
        with open_stable_regular_file(snapshot, "signing snapshot", MAX_FILE_BYTES) as source:
            with open_stable_regular_file(
                signature, "detached signature snapshot", MAX_SIGNATURE_BYTES
            ) as detached:
                result = _run_gpg(
                    [
                        "gpg",
                        "--batch",
                        "--quiet",
                        "--status-fd",
                        "1",
                        "--verify",
                        f"/dev/fd/{detached.fileno()}",
                        f"/dev/fd/{source.fileno()}",
                    ],
                    pass_fds=(detached.fileno(), source.fileno()),
                    environment=environment,
                )
    except EvidenceError as error:
        raise ReleaseHandoffError(str(error)) from error
    status = result.stdout.decode("ascii", errors="replace")[:64 * 1024]
    valid_signatures = [
        line.split()
        for line in status.splitlines()
        if line.startswith("[GNUPG:] VALIDSIG ")
    ]
    valid = valid_signatures[0] if len(valid_signatures) == 1 else ()
    if (
        result.returncode != 0
        or len(valid) != 12
        or re.fullmatch(r"[0-9A-F]{40}", valid[2]) is None
        or valid[11] != fingerprint
    ):
        raise ReleaseHandoffError("detached signature does not verify against its snapshot")


def _guard_snapshot(path: Path, payload: bytes, identity: tuple[int, ...], label: str) -> None:
    if _filesystem_identity(path) != identity:
        raise ReleaseHandoffError(f"{label} was replaced")
    observed = _read_regular_file(path, len(payload), label)
    if not hmac.compare_digest(observed, payload):
        raise ReleaseHandoffError(f"{label} bytes changed")


def _export_public_key(fingerprint: str, environment: dict[str, str]) -> bytes:
    result = _run_gpg(
        ["gpg", "--batch", "--quiet", "--armor", "--export", fingerprint],
        pass_fds=(),
        environment=environment,
    )
    if (
        result.returncode != 0
        or not result.stdout
        or len(result.stdout) > MAX_SIGNATURE_BYTES
        or b"BEGIN PGP PUBLIC KEY BLOCK" not in result.stdout
        or b"PRIVATE KEY" in result.stdout
    ):
        raise ReleaseHandoffError("GPG could not export the bounded public signing key")
    return result.stdout


def _persist_signing_evidence(
    bundle: Path,
    final_payloads: dict[str, bytes],
    signatures: dict[str, tuple[Path, bytes, tuple[int, ...]]],
    version: str,
    fingerprint: str,
    gpg_environment: dict[str, str],
    verified_handoff: VerifiedGithubHandoff,
) -> dict:
    bundle_bytes = _read_regular_file(bundle, MAX_ARCHIVE_BYTES, "final release bundle")
    manifest, observed_payloads = inspect_bundle(bundle, version)
    if observed_payloads != final_payloads:
        raise ReleaseHandoffError("final bundle differs from captured signing payloads")
    public_key = _export_public_key(fingerprint, gpg_environment)
    public_key_output = signing_public_key_path(version)
    atomic_write(
        public_key_output,
        public_key,
        maximum_size=MAX_SIGNATURE_BYTES,
    )
    public_key_output.chmod(0o400)
    signature_records = []
    for signature_path in sorted(signatures):
        base_path = signature_path.removesuffix(".asc")
        signature_payload = signatures[signature_path][1]
        signature_records.append(
            {
                "artifactPath": base_path,
                "artifactSha256": hashlib.sha256(final_payloads[base_path]).hexdigest(),
                "artifactSize": len(final_payloads[base_path]),
                "signaturePath": signature_path,
                "sha256": hashlib.sha256(signature_payload).hexdigest(),
                "size": len(signature_payload),
            }
        )
    handoff_identity = {
        "githubArtifact": verified_handoff.receipt["githubArtifact"],
        "unsignedArchive": verified_handoff.receipt["unsignedArchive"],
        "unsignedManifest": verified_handoff.receipt["unsignedManifest"],
    }
    evidence = {
        "bundle": {
            "sha256": hashlib.sha256(bundle_bytes).hexdigest(),
            "size": len(bundle_bytes),
        },
        "fingerprint": fingerprint,
        "publicKey": {
            "sha256": hashlib.sha256(public_key).hexdigest(),
            "size": len(public_key),
        },
        "schema": SIGNING_EVIDENCE_SCHEMA,
        "signatures": signature_records,
        "verifiedHandoff": handoff_identity,
        "version": version,
    }
    evidence_output = signing_evidence_path(version)
    atomic_write(
        evidence_output,
        (json.dumps(evidence, sort_keys=True, separators=(",", ":")) + "\n").encode(
            "utf-8"
        ),
        maximum_size=MAX_MANIFEST_BYTES,
    )
    evidence_output.chmod(0o400)
    if manifest["bundle"]["sha256"] != evidence["bundle"]["sha256"]:
        raise ReleaseHandoffError("signing evidence bundle identity is inconsistent")
    return evidence


def _public_key_verification_environment(
    public_key: Path, expected_fingerprint: str, root: Path
) -> dict[str, str]:
    home = root / "gnupg"
    home.mkdir(mode=0o700)
    environment = {
        "GNUPGHOME": str(home),
        "HOME": str(home),
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
    }
    public_key_payload = _read_regular_file(
        public_key, MAX_SIGNATURE_BYTES, "public signing key"
    )
    public_key_snapshot = root / "signing-public-key.asc"
    atomic_write(
        public_key_snapshot,
        public_key_payload,
        maximum_size=MAX_SIGNATURE_BYTES,
    )
    public_key_snapshot.chmod(0o400)
    imported = _run_gpg(
        ["gpg", "--batch", "--quiet", "--import", str(public_key_snapshot)],
        pass_fds=(),
        environment=environment,
    )
    if imported.returncode != 0:
        reason = imported.stderr.decode("utf-8", errors="replace").strip()[:240]
        raise ReleaseHandoffError(
            "public signing key could not be imported"
            + (f": {reason}" if reason else "")
        )
    listed = _run_gpg(
        ["gpg", "--batch", "--with-colons", "--fingerprint", "--list-keys"],
        pass_fds=(),
        environment=environment,
    )
    fingerprints = [
        line.split(":")[9]
        for line in listed.stdout.decode("ascii", errors="replace").splitlines()
        if line.startswith("fpr:")
    ]
    secrets = _run_gpg(
        ["gpg", "--batch", "--with-colons", "--list-secret-keys"],
        pass_fds=(),
        environment=environment,
    )
    if (
        listed.returncode != 0
        or not fingerprints
        or fingerprints[0] != expected_fingerprint
        or secrets.returncode not in (0, 2)
        or any(line.startswith("sec:") for line in secrets.stdout.decode("ascii", errors="replace").splitlines())
    ):
        raise ReleaseHandoffError(
            "public signing key does not match the expected fingerprint"
        )
    return environment


def verify_signing_evidence(
    bundle: Path,
    evidence_path: Path,
    public_key_path: Path,
    version: str,
    expected_fingerprint: str,
    github_artifact: Path,
    github_artifact_digest: str,
    github_artifact_id: str,
    github_artifact_name: str,
) -> str:
    """Cryptographically verify all detached signatures and their handoff origin."""
    version = require_canonical_version(version)
    if re.fullmatch(r"[0-9A-F]{40}", expected_fingerprint) is None:
        raise ReleaseHandoffError("expected signing fingerprint is not canonical")
    evidence_path = _require_fixed_path(
        evidence_path, str(signing_evidence_path(version)), "signing evidence"
    )
    public_key_path = _require_fixed_path(
        public_key_path, str(signing_public_key_path(version)), "public signing key"
    )
    bundle = _require_fixed_path(
        bundle,
        f"build/maven-central/procwright-{version}-maven-central-bundle.zip",
        "Maven Central bundle",
    )
    receipt = _parse_manifest(evidence_path)
    if set(receipt) != {
        "bundle",
        "fingerprint",
        "publicKey",
        "schema",
        "signatures",
        "verifiedHandoff",
        "version",
    } or receipt.get("schema") != SIGNING_EVIDENCE_SCHEMA:
        raise ReleaseHandoffError("signing evidence schema is not exact")
    verified_receipt = _parse_manifest(verified_receipt_path(version))
    commit = require_commit_sha(verified_receipt["commit"])
    verified = _inspect_github_handoff(
        _require_fixed_path(
            github_artifact,
            ".procwright-handoff",
            "raw GitHub handoff artifact directory",
        ),
        version,
        commit,
        commit,
        github_artifact_digest,
        github_artifact_id,
        github_artifact_name,
    )
    expected_handoff = {
        "githubArtifact": verified.receipt["githubArtifact"],
        "unsignedArchive": verified.receipt["unsignedArchive"],
        "unsignedManifest": verified.receipt["unsignedManifest"],
    }
    if verified_receipt != verified.receipt:
        raise ReleaseHandoffError(
            "verified handoff receipt differs from the original GitHub artifact"
        )
    public_key = _read_regular_file(
        public_key_path, MAX_SIGNATURE_BYTES, "public signing key"
    )
    manifest, payloads = inspect_bundle(bundle, version)
    expected_signatures = []
    for base_path in expected_payload_names(version):
        signature_path = base_path + ".asc"
        expected_signatures.append(
            {
                "artifactPath": base_path,
                "artifactSha256": hashlib.sha256(payloads[base_path]).hexdigest(),
                "artifactSize": len(payloads[base_path]),
                "signaturePath": signature_path,
                "sha256": hashlib.sha256(payloads[signature_path]).hexdigest(),
                "size": len(payloads[signature_path]),
            }
        )
    expected = {
        "bundle": manifest["bundle"],
        "fingerprint": expected_fingerprint,
        "publicKey": {
            "sha256": hashlib.sha256(public_key).hexdigest(),
            "size": len(public_key),
        },
        "schema": SIGNING_EVIDENCE_SCHEMA,
        "signatures": expected_signatures,
        "verifiedHandoff": expected_handoff,
        "version": version,
    }
    if receipt != expected:
        raise ReleaseHandoffError(
            "signing evidence does not bind the exact bundle, handoff, key, and signatures"
        )
    with tempfile.TemporaryDirectory(prefix="pw-sign-", dir="/tmp") as directory:
        root = Path(directory)
        environment = _public_key_verification_environment(
            public_key_path, expected_fingerprint, root
        )
        for record in expected_signatures:
            artifact_snapshot = root / hashlib.sha256(
                record["artifactPath"].encode("utf-8")
            ).hexdigest()
            signature_snapshot = artifact_snapshot.with_suffix(".asc")
            atomic_write(
                artifact_snapshot,
                payloads[record["artifactPath"]],
                maximum_size=MAX_FILE_BYTES,
            )
            atomic_write(
                signature_snapshot,
                payloads[record["signaturePath"]],
                maximum_size=MAX_SIGNATURE_BYTES,
            )
            artifact_snapshot.chmod(0o400)
            signature_snapshot.chmod(0o400)
            _verify_snapshot_signature(
                artifact_snapshot,
                signature_snapshot,
                expected_fingerprint,
                environment,
            )
    return manifest["bundle"]["sha256"]


def _require_digest_record(
    value: object,
    label: str,
    *,
    maximum_size: int,
    expected_name: str | None = None,
) -> dict:
    expected_keys = {"sha256", "size"} | ({"name"} if expected_name else set())
    digest = value.get("sha256") if isinstance(value, dict) else None
    size = value.get("size") if isinstance(value, dict) else None
    if (
        not isinstance(value, dict)
        or set(value) != expected_keys
        or not isinstance(digest, str)
        or re.fullmatch(r"[0-9a-f]{64}", digest) is None
        or isinstance(size, bool)
        or not isinstance(size, int)
        or not 0 < size <= maximum_size
        or expected_name is not None
        and value.get("name") != expected_name
    ):
        raise ReleaseHandoffError(f"{label} identity is not exact")
    return value


def verify_persisted_signing_evidence(
    bundle: Path,
    evidence_path: Path,
    public_key_path: Path,
    verified_receipt_path: Path,
    version: str,
    commit: str,
) -> tuple[str, str]:
    """Verify persisted signing proof without trusting the producing workspace."""
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    receipt = _parse_manifest(evidence_path)
    if set(receipt) != {
        "bundle",
        "fingerprint",
        "publicKey",
        "schema",
        "signatures",
        "verifiedHandoff",
        "version",
    } or receipt.get("schema") != SIGNING_EVIDENCE_SCHEMA:
        raise ReleaseHandoffError("persisted signing evidence schema is not exact")
    fingerprint = receipt.get("fingerprint")
    if not isinstance(fingerprint, str) or re.fullmatch(
        r"[0-9A-F]{40}", fingerprint
    ) is None:
        raise ReleaseHandoffError("persisted signing fingerprint is not canonical")

    verified_receipt = _parse_manifest(verified_receipt_path)
    if set(verified_receipt) != {
        "commit",
        "entries",
        "githubArtifact",
        "schema",
        "unsignedArchive",
        "unsignedManifest",
        "version",
        "workflowSha",
    } or verified_receipt.get("schema") != VERIFIED_HANDOFF_SCHEMA:
        raise ReleaseHandoffError("persisted verified handoff schema is not exact")
    if (
        verified_receipt.get("commit") != commit
        or verified_receipt.get("workflowSha") != commit
        or verified_receipt.get("version") != version
    ):
        raise ReleaseHandoffError("persisted verified handoff release identity is incorrect")
    github_artifact = verified_receipt.get("githubArtifact")
    github_artifact_id = (
        github_artifact.get("id") if isinstance(github_artifact, dict) else None
    )
    if (
        not isinstance(github_artifact, dict)
        or set(github_artifact) != {"id", "name", "sha256", "size"}
        or not isinstance(github_artifact_id, str)
        or re.fullmatch(r"[1-9][0-9]{0,19}", github_artifact_id) is None
        or github_artifact.get("name")
        != f"procwright-{version}-{commit}-unsigned-release"
    ):
        raise ReleaseHandoffError("persisted unsigned GitHub artifact identity is not exact")
    _require_digest_record(
        {key: github_artifact[key] for key in ("sha256", "size")},
        "persisted unsigned GitHub artifact",
        maximum_size=MAX_ARCHIVE_BYTES,
    )
    unsigned_archive = _require_digest_record(
        verified_receipt.get("unsignedArchive"),
        "persisted unsigned archive",
        maximum_size=MAX_ARCHIVE_BYTES,
        expected_name=f"procwright-{version}-unsigned.zip",
    )
    unsigned_manifest = _require_digest_record(
        verified_receipt.get("unsignedManifest"),
        "persisted unsigned manifest",
        maximum_size=MAX_MANIFEST_BYTES,
    )

    public_key = _read_regular_file(
        public_key_path, MAX_SIGNATURE_BYTES, "persisted public signing key"
    )
    manifest, payloads = inspect_bundle(bundle, version)
    expected_entries = [
        _entry(name, payloads[name], version) for name in expected_payload_names(version)
    ]
    if verified_receipt.get("entries") != expected_entries:
        raise ReleaseHandoffError(
            "persisted verified handoff entries differ from signed bundle base bytes"
        )
    expected_handoff = {
        "githubArtifact": github_artifact,
        "unsignedArchive": unsigned_archive,
        "unsignedManifest": unsigned_manifest,
    }
    expected_signatures = []
    for base_path in expected_payload_names(version):
        signature_path = base_path + ".asc"
        expected_signatures.append(
            {
                "artifactPath": base_path,
                "artifactSha256": hashlib.sha256(payloads[base_path]).hexdigest(),
                "artifactSize": len(payloads[base_path]),
                "signaturePath": signature_path,
                "sha256": hashlib.sha256(payloads[signature_path]).hexdigest(),
                "size": len(payloads[signature_path]),
            }
        )
    expected = {
        "bundle": manifest["bundle"],
        "fingerprint": fingerprint,
        "publicKey": {
            "sha256": hashlib.sha256(public_key).hexdigest(),
            "size": len(public_key),
        },
        "schema": SIGNING_EVIDENCE_SCHEMA,
        "signatures": expected_signatures,
        "verifiedHandoff": expected_handoff,
        "version": version,
    }
    if receipt != expected:
        raise ReleaseHandoffError(
            "persisted signing evidence does not bind the exact bundle, handoff, key, and signatures"
        )

    with tempfile.TemporaryDirectory(prefix="pw-persisted-sign-", dir="/tmp") as directory:
        root = Path(directory)
        environment = _public_key_verification_environment(
            public_key_path, fingerprint, root
        )
        for record in expected_signatures:
            artifact_snapshot = root / hashlib.sha256(
                record["artifactPath"].encode("utf-8")
            ).hexdigest()
            signature_snapshot = artifact_snapshot.with_suffix(".asc")
            atomic_write(
                artifact_snapshot,
                payloads[record["artifactPath"]],
                maximum_size=MAX_FILE_BYTES,
            )
            atomic_write(
                signature_snapshot,
                payloads[record["signaturePath"]],
                maximum_size=MAX_SIGNATURE_BYTES,
            )
            artifact_snapshot.chmod(0o400)
            signature_snapshot.chmod(0o400)
            _verify_snapshot_signature(
                artifact_snapshot,
                signature_snapshot,
                fingerprint,
                environment,
            )
    return manifest["bundle"]["sha256"], fingerprint


def sign_and_finalize(
    repository: Path,
    manifest_path: Path,
    bundle: Path,
    version: str,
    gnupg_home: Path,
    fingerprint: str,
    passphrase_file: Path,
    *,
    github_artifact: Path | None = None,
    github_artifact_digest: str | None = None,
    github_artifact_id: str | None = None,
    github_artifact_name: str | None = None,
    phase_hook: Callable[[str, Path], None] | None = None,
) -> dict:
    version = require_canonical_version(version)
    repository = _require_fixed_path(
        repository, "build/maven-central/repository", "privileged release repository"
    )
    manifest_path = _require_fixed_path(
        manifest_path,
        str(verified_manifest_path(version)),
        "verified unsigned manifest",
    )
    bundle = _require_fixed_path(
        bundle,
        f"build/maven-central/procwright-{version}-maven-central-bundle.zip",
        "Maven Central bundle",
    )
    if re.fullmatch(r"[0-9A-F]{40}", fingerprint) is None:
        raise ReleaseHandoffError("signing fingerprint must be 40 uppercase hexadecimal characters")
    gnupg_home = _require_real_directory(gnupg_home, "GPG home")
    if gnupg_home.stat().st_mode & 0o077:
        raise ReleaseHandoffError("GPG home must be owner-only")
    if bundle.exists() or bundle.is_symlink():
        raise ReleaseHandoffError("Maven Central bundle output must not pre-exist")
    originals = _collect_payloads(repository, version)
    source_identities = _source_identities(repository, originals)
    manifest = _parse_manifest(manifest_path)
    expected_entries = [
        _entry(name, payload, version) for name, payload in sorted(originals.items())
    ]
    if (
        set(manifest) != {
            "archive",
            "commit",
            "entries",
            "schema",
            "version",
            "workflow_sha",
        }
        or manifest.get("schema") != SCHEMA
        or manifest.get("version") != version
        or manifest.get("entries") != expected_entries
        or manifest.get("commit") != manifest.get("workflow_sha")
    ):
        raise ReleaseHandoffError(
            "verified unsigned manifest does not bind the captured base artifacts"
        )
    try:
        require_commit_sha(manifest["commit"])
    except (KeyError, ReleaseContractError) as error:
        raise ReleaseHandoffError("verified unsigned manifest commit is invalid") from error
    github_values = (
        github_artifact,
        github_artifact_digest,
        github_artifact_id,
        github_artifact_name,
    )
    if any(value is None for value in github_values):
        raise ReleaseHandoffError(
            "signing requires the complete original GitHub artifact identity"
        )
    github_artifact = _require_fixed_path(
        github_artifact,
        ".procwright-handoff",
        "raw GitHub handoff artifact directory",
    )
    verified = _inspect_github_handoff(
        github_artifact,
        version,
        manifest["commit"],
        manifest["workflow_sha"],
        github_artifact_digest,
        github_artifact_id,
        github_artifact_name,
    )
    receipt_path = verified_receipt_path(version)
    receipt = _parse_manifest(receipt_path)
    expected_receipt_bytes = (
        json.dumps(verified.receipt, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8")
    if (
        receipt != verified.receipt
        or _read_regular_file(
            receipt_path, MAX_MANIFEST_BYTES, "verified handoff receipt"
        )
        != expected_receipt_bytes
        or _read_regular_file(
            manifest_path, MAX_MANIFEST_BYTES, "verified unsigned manifest"
        )
        != verified.manifest_bytes
        or originals != verified.payloads
    ):
        raise ReleaseHandoffError(
            "verified handoff snapshots differ from the original GitHub artifact"
        )
    snapshot_root = _require_fixed_path(
        Path("build/maven-central/signing-snapshot"),
        "build/maven-central/signing-snapshot",
        "signing snapshot root",
    )
    if snapshot_root.exists() or snapshot_root.is_symlink():
        raise ReleaseHandoffError("signing snapshot root must not pre-exist")
    snapshot_root.mkdir(mode=0o700)
    snapshots: dict[str, tuple[Path, bytes, tuple[int, ...]]] = {}
    signatures: dict[str, tuple[Path, bytes, tuple[int, ...]]] = {}
    gpg_environment = {
        "GNUPGHOME": str(gnupg_home),
        "HOME": str(gnupg_home),
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
    }
    try:
        if phase_hook is not None:
            phase_hook("after-collect", repository)
        _guard_source_payloads(repository, version, originals, source_identities)
        for name, payload in sorted(originals.items()):
            snapshot = _private_snapshot_path(snapshot_root, name)
            atomic_write(snapshot, payload, maximum_size=MAX_FILE_BYTES)
            identity = _seal_snapshot(snapshot)
            snapshots[name] = (snapshot, payload, identity)
            if phase_hook is not None:
                phase_hook("after-snapshot", snapshot)
            _guard_source_payloads(repository, version, originals, source_identities)
            _guard_snapshot(snapshot, payload, identity, "signing snapshot")

            signature_payload = _sign_snapshot(
                snapshot, passphrase_file, fingerprint, gpg_environment
            )
            signature_path = _private_snapshot_path(snapshot_root, name + ".asc")
            atomic_write(
                signature_path,
                signature_payload,
                maximum_size=MAX_SIGNATURE_BYTES,
            )
            signature_identity = _seal_snapshot(signature_path)
            signatures[name + ".asc"] = (
                signature_path,
                signature_payload,
                signature_identity,
            )
            if phase_hook is not None:
                phase_hook("after-signature", signature_path)
            _guard_snapshot(snapshot, payload, identity, "signing snapshot")
            _guard_snapshot(
                signature_path,
                signature_payload,
                signature_identity,
                "detached signature snapshot",
            )
            _verify_snapshot_signature(
                snapshot, signature_path, fingerprint, gpg_environment
            )

        final_payloads = dict(originals)
        final_payloads.update(
            {name: captured[1] for name, captured in signatures.items()}
        )
        for name, payload in originals.items():
            for algorithm, extension in CHECKSUMS:
                final_payloads[f"{name}.{extension}"] = hashlib.new(
                    algorithm, payload
                ).hexdigest().encode("ascii")
        if phase_hook is not None:
            phase_hook("before-finalize", bundle)
        _guard_source_payloads(repository, version, originals, source_identities)
        for path, payload, identity in snapshots.values():
            _guard_snapshot(path, payload, identity, "signing snapshot")
        for path, payload, identity in signatures.values():
            _guard_snapshot(path, payload, identity, "detached signature snapshot")
        validate_release_payloads(final_payloads, version)
        _write_archive(bundle, final_payloads)
        signing_evidence = _persist_signing_evidence(
            bundle,
            final_payloads,
            signatures,
            version,
            fingerprint,
            gpg_environment,
            verified,
        )
    except BaseException:
        if bundle.exists() and bundle.is_file() and not bundle.is_symlink():
            bundle.unlink()
        for evidence_output in (
            signing_evidence_path(version),
            signing_public_key_path(version),
        ):
            if evidence_output.exists() and evidence_output.is_file() and not evidence_output.is_symlink():
                evidence_output.chmod(0o600)
                evidence_output.unlink()
        raise
    finally:
        shutil.rmtree(snapshot_root, ignore_errors=True)
    return signing_evidence


def _write_github_output(name: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT", "")
    if output_path:
        with Path(output_path).open("a", encoding="utf-8", newline="\n") as output:
            output.write(f"{name}={value}\n")


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--repository", type=Path, required=True)
    prepare_parser.add_argument("--output", type=Path, required=True)
    prepare_parser.add_argument("--version", required=True)
    prepare_parser.add_argument("--commit", required=True)
    prepare_parser.add_argument("--workflow-sha", required=True)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--handoff", type=Path, required=True)
    verify_parser.add_argument("--output-repository", type=Path, required=True)
    verify_parser.add_argument("--version", required=True)
    verify_parser.add_argument("--commit", required=True)
    verify_parser.add_argument("--workflow-sha", required=True)
    verify_parser.add_argument("--github-artifact-digest")
    verify_parser.add_argument("--github-artifact-id")
    verify_parser.add_argument("--github-artifact-name")
    finalize_parser = subparsers.add_parser("sign-finalize")
    finalize_parser.add_argument("--repository", type=Path, required=True)
    finalize_parser.add_argument("--manifest", type=Path, required=True)
    finalize_parser.add_argument("--bundle", type=Path, required=True)
    finalize_parser.add_argument("--version", required=True)
    finalize_parser.add_argument("--gnupg-home", type=Path, required=True)
    finalize_parser.add_argument("--fingerprint", required=True)
    finalize_parser.add_argument("--passphrase-file", type=Path, required=True)
    proof_parser = subparsers.add_parser("verify-signing-evidence")
    proof_parser.add_argument("--bundle", type=Path, required=True)
    proof_parser.add_argument("--evidence", type=Path, required=True)
    proof_parser.add_argument("--public-key", type=Path, required=True)
    proof_parser.add_argument("--version", required=True)
    proof_parser.add_argument("--fingerprint", required=True)
    proof_parser.add_argument("--github-artifact", type=Path, required=True)
    proof_parser.add_argument("--github-artifact-digest", required=True)
    proof_parser.add_argument("--github-artifact-id", required=True)
    proof_parser.add_argument("--github-artifact-name", required=True)
    options = parser.parse_args(arguments)
    try:
        if options.command == "prepare":
            prepare(
                options.repository,
                options.output,
                options.version,
                options.commit,
                options.workflow_sha,
            )
        elif options.command == "verify":
            digest = verify(
                options.handoff,
                options.output_repository,
                options.version,
                options.commit,
                options.workflow_sha,
                github_artifact_digest=options.github_artifact_digest,
                github_artifact_id=options.github_artifact_id,
                github_artifact_name=options.github_artifact_name,
            )
            _write_github_output("unsigned_bundle_sha256", digest)
        elif options.command == "sign-finalize":
            artifact_digest = os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST")
            artifact_id = os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_ID")
            artifact_name = os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_NAME")
            artifact = (
                Path(".procwright-handoff")
                if artifact_digest is not None
                else None
            )
            evidence = sign_and_finalize(
                options.repository,
                options.manifest,
                options.bundle,
                options.version,
                options.gnupg_home,
                options.fingerprint,
                options.passphrase_file,
                github_artifact=artifact,
                github_artifact_digest=artifact_digest,
                github_artifact_id=artifact_id,
                github_artifact_name=artifact_name,
            )
            _write_github_output("signing_fingerprint", evidence["fingerprint"])
            _write_github_output("signed_bundle_sha256", evidence["bundle"]["sha256"])
        else:
            digest = verify_signing_evidence(
                options.bundle,
                options.evidence,
                options.public_key,
                options.version,
                options.fingerprint,
                options.github_artifact,
                options.github_artifact_digest,
                options.github_artifact_id,
                options.github_artifact_name,
            )
            _write_github_output("verified_signed_bundle_sha256", digest)
    except (
        EvidenceError,
        ReleaseContractError,
        ReleaseHandoffError,
        OSError,
        ValueError,
    ) as error:
        print(f"Release handoff failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
