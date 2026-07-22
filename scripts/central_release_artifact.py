#!/usr/bin/env python3
"""Seal and verify the exact Maven Central bytes transferred between CI jobs."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import shutil
import stat
import sys
import zipfile
from pathlib import Path

from maven_release_evidence import (
    MAX_BUNDLE_BYTES,
    MAX_MANIFEST_BYTES,
    EvidenceError,
    artifact_size_limit,
    atomic_write,
    inspect_bundle,
    manifest_bytes,
    parse_manifest,
    raw_central_directory,
    read_stable_file,
    open_stable_regular_file,
)
from release_contract import (
    ReleaseContractError,
    artifact_identity,
    expected_release_paths,
    require_canonical_version,
    require_commit_sha,
)


MAX_API_BYTES = 2 * 1024 * 1024
MAX_ARTIFACT_BYTES = 300 * 1024 * 1024
MAX_CENTRAL_DIRECTORY_BYTES = 512 * 1024


class CentralArtifactError(RuntimeError):
    """The cross-job Central byte artifact was not exact."""


def artifact_name(version: str, commit: str) -> str:
    return (
        f"procwright-{require_canonical_version(version)}-"
        f"{require_commit_sha(commit)}-central-verified-bytes"
    )


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CentralArtifactError(f"GitHub artifact JSON repeats key {key!r}")
        result[key] = value
    return result


def _parse_json(path: Path) -> dict:
    try:
        value = json.loads(
            read_stable_file(path, "GitHub artifact response", MAX_API_BYTES),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (EvidenceError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CentralArtifactError("GitHub artifact response is not bounded UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise CentralArtifactError("GitHub artifact response must be an object")
    return value


def _positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CentralArtifactError(f"{label} must be a positive integer")
    return value


def verify_metadata(
    response_path: Path,
    repository: str,
    run_id_text: str,
    version: str,
    commit: str,
    artifact_id_text: str,
    expected_name: str,
    expected_digest: str,
) -> None:
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise CentralArtifactError("GitHub repository identity is malformed")
    if re.fullmatch(r"[1-9][0-9]{0,19}", run_id_text) is None:
        raise CentralArtifactError("producer workflow run ID is malformed")
    if re.fullmatch(r"[1-9][0-9]{0,19}", artifact_id_text) is None:
        raise CentralArtifactError("Central byte artifact ID is malformed")
    if expected_name != artifact_name(version, commit):
        raise CentralArtifactError("Central byte artifact name is not the closed identity")
    if re.fullmatch(r"[0-9a-f]{64}", expected_digest) is None:
        raise CentralArtifactError("Central byte artifact digest is malformed")

    response = _parse_json(response_path)
    artifact_id = int(artifact_id_text)
    run_id = int(run_id_text)
    workflow_run = response.get("workflow_run")
    if not isinstance(workflow_run, dict):
        raise CentralArtifactError("Central byte artifact has no workflow identity")
    repository_id = _positive_integer(
        workflow_run.get("repository_id"), "artifact repository ID"
    )
    head_repository_id = _positive_integer(
        workflow_run.get("head_repository_id"), "artifact head repository ID"
    )
    size = _positive_integer(response.get("size_in_bytes"), "artifact size")
    expected_url = (
        f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
    )
    if (
        _positive_integer(response.get("id"), "artifact ID") != artifact_id
        or response.get("name") != expected_name
        or response.get("expired") is not False
        or response.get("digest") != f"sha256:{expected_digest}"
        or response.get("archive_download_url") != expected_url
        or size > MAX_ARTIFACT_BYTES
        or workflow_run.get("id") != run_id
        or workflow_run.get("head_branch") != "main"
        or workflow_run.get("head_sha") != commit
        or repository_id != head_repository_id
    ):
        raise CentralArtifactError(
            "Central byte artifact metadata does not match its exact producer identity"
        )


def _expected_payloads(
    bundle_path: Path, manifest_path: Path, version: str
) -> dict[str, tuple[str, bytes, int]]:
    manifest_payload = read_stable_file(
        manifest_path, "signed staging manifest", MAX_MANIFEST_BYTES
    )
    parse_manifest(manifest_payload, version)
    manifest, payloads = inspect_bundle(bundle_path, version)
    if not hmac.compare_digest(manifest_payload, manifest_bytes(manifest)):
        raise CentralArtifactError(
            "signed staging manifest does not identify the staged bundle"
        )
    expected: dict[str, tuple[str, bytes, int]] = {}
    for path in expected_release_paths(version):
        filename = Path(path).name
        if filename in expected:
            raise CentralArtifactError("release paths collide in the flat byte artifact")
        expected[filename] = (
            path,
            payloads[path],
            artifact_size_limit(artifact_identity(path, version)),
        )
    return expected


def _directory(path: Path, *, create: bool) -> Path:
    if create:
        if path.exists() or path.is_symlink():
            raise CentralArtifactError("Central byte destination must not pre-exist")
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.mkdir(mode=0o700)
    try:
        metadata = path.lstat()
    except OSError as error:
        raise CentralArtifactError("cannot inspect Central byte directory") from error
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_ISLNK(metadata.st_mode)
        or hasattr(os, "getuid")
        and metadata.st_uid != os.getuid()
        or metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH)
    ):
        raise CentralArtifactError("Central byte directory is unsafe")
    return path


def _verify_directory(
    directory_path: Path,
    expected: dict[str, tuple[str, bytes, int]],
    *,
    seal: bool,
) -> None:
    directory = _directory(directory_path, create=False)
    try:
        entries = list(os.scandir(directory))
    except OSError as error:
        raise CentralArtifactError("cannot enumerate Central byte directory") from error
    if (
        len(entries) != len(expected)
        or {entry.name for entry in entries} != set(expected)
    ):
        raise CentralArtifactError("Central byte directory file set is not exact")
    for entry in entries:
        if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
            raise CentralArtifactError("Central byte directory contains a non-regular file")
        _path, payload, limit = expected[entry.name]
        observed = read_stable_file(Path(entry.path), f"Central byte {entry.name}", limit)
        if not hmac.compare_digest(observed, payload):
            raise CentralArtifactError("Central byte differs from the signed staging bundle")
    if seal:
        for entry in entries:
            Path(entry.path).chmod(0o400)
        directory.chmod(0o500)


def seal_directory(
    directory_path: Path,
    bundle_path: Path,
    manifest_path: Path,
    version: str,
) -> None:
    version = require_canonical_version(version)
    _verify_directory(
        directory_path,
        _expected_payloads(bundle_path, manifest_path, version),
        seal=True,
    )


def extract_and_verify(
    archive_path: Path,
    destination_path: Path,
    bundle_path: Path,
    manifest_path: Path,
    version: str,
    expected_digest: str,
) -> None:
    version = require_canonical_version(version)
    if re.fullmatch(r"[0-9a-f]{64}", expected_digest) is None:
        raise CentralArtifactError("Central byte artifact digest is malformed")
    expected = _expected_payloads(bundle_path, manifest_path, version)
    destination = _directory(destination_path, create=True)
    try:
        extracted: dict[str, bytes] = {}
        with open_stable_regular_file(
            archive_path, "raw Central byte artifact", MAX_ARTIFACT_BYTES
        ) as source:
            digest = hashlib.sha256()
            while chunk := source.read(128 * 1024):
                digest.update(chunk)
            if not hmac.compare_digest(digest.hexdigest(), expected_digest):
                raise CentralArtifactError(
                    "raw Central byte artifact differs from the GitHub service digest"
                )
            size = os.fstat(source.fileno()).st_size
            source.seek(0)
            raw_entries = raw_central_directory(
                source,
                size,
                maximum_entries=len(expected),
                maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
                maximum_entry_bytes=max(item[2] for item in expected.values()),
                maximum_total_compressed_bytes=MAX_ARTIFACT_BYTES,
                maximum_total_bytes=MAX_ARTIFACT_BYTES,
                allow_directories=False,
                allow_signed_data_descriptors=True,
            )
            source.seek(0)
            with zipfile.ZipFile(source) as archive:
                infos = archive.infolist()
                if (
                    len(infos) != len(raw_entries)
                    or {entry.name for entry in raw_entries} != set(expected)
                ):
                    raise CentralArtifactError(
                        "raw Central byte artifact file set is not exact"
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
                        raise CentralArtifactError(
                            "raw Central byte artifact contains an unsafe entry"
                        )
                    _path, expected_payload, limit = expected[raw.name]
                    with archive.open(info) as entry:
                        payload = entry.read(limit + 1)
                    if (
                        len(payload) > limit
                        or len(payload) != info.file_size
                        or not hmac.compare_digest(payload, expected_payload)
                    ):
                        raise CentralArtifactError(
                            "Central byte artifact payload differs from signed staging bytes"
                        )
                    extracted[raw.name] = payload
        if set(extracted) != set(expected):
            raise CentralArtifactError("extracted Central byte file set is not exact")
        for filename, payload in sorted(extracted.items()):
            output = destination / filename
            atomic_write(output, payload, maximum_size=expected[filename][2])
            output.chmod(0o400)
        _verify_directory(destination, expected, seal=True)
    except BaseException:
        destination.chmod(0o700)
        shutil.rmtree(destination, ignore_errors=True)
        raise


def _write_output(path: Path, name: str) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"artifact_name={name}\n")


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    seal = commands.add_parser("seal")
    for option in ("directory", "bundle", "manifest", "github-output"):
        seal.add_argument(f"--{option}", type=Path, required=True)
    seal.add_argument("--version", required=True)
    seal.add_argument("--commit", required=True)
    metadata = commands.add_parser("verify-metadata")
    metadata.add_argument("--response", type=Path, required=True)
    for option in (
        "repository",
        "run-id",
        "version",
        "commit",
        "artifact-id",
        "artifact-name",
        "artifact-digest",
    ):
        metadata.add_argument(f"--{option}", required=True)
    extract = commands.add_parser("extract")
    for option in ("archive", "directory", "bundle", "manifest"):
        extract.add_argument(f"--{option}", type=Path, required=True)
    extract.add_argument("--version", required=True)
    extract.add_argument("--digest", required=True)
    options = parser.parse_args(arguments)
    try:
        if options.command == "seal":
            name = artifact_name(options.version, options.commit)
            seal_directory(
                options.directory, options.bundle, options.manifest, options.version
            )
            _write_output(options.github_output, name)
        elif options.command == "verify-metadata":
            verify_metadata(
                options.response,
                options.repository,
                options.run_id,
                options.version,
                options.commit,
                options.artifact_id,
                options.artifact_name,
                options.artifact_digest,
            )
        else:
            extract_and_verify(
                options.archive,
                options.directory,
                options.bundle,
                options.manifest,
                options.version,
                options.digest,
            )
    except (
        CentralArtifactError,
        EvidenceError,
        OSError,
        ReleaseContractError,
        zipfile.BadZipFile,
    ) as error:
        reason = "".join(
            character if 32 <= ord(character) < 127 else "?"
            for character in str(error)
        )[:240]
        print(f"Central byte artifact verification failed: {reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
