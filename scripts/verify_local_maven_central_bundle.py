#!/usr/bin/env python3
"""Verify the exact Gradle repository and Maven Central ZIP postcondition."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import stat
import sys
from pathlib import Path

from maven_release_evidence import (
    EvidenceError,
    artifact_size_limit,
    inspect_bundle,
    read_stable_file,
    validate_base_payloads,
)
from release_contract import (
    CHECKSUMS,
    ReleaseContractError,
    artifact_identity,
    expected_base_paths,
    expected_generated_metadata_paths,
    expected_release_paths,
    require_canonical_version,
)


MAX_REPOSITORY_FILES = 128
MAX_METADATA_BYTES = 1024 * 1024


class LocalBundleError(RuntimeError):
    """The local publication output is not the exact Central upload set."""


def _walk_regular_files(root: Path) -> dict[str, Path]:
    try:
        metadata = root.lstat()
    except OSError as error:
        raise LocalBundleError("cannot inspect the local Maven repository") from error
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise LocalBundleError("local Maven repository must be a real directory")
    result: dict[str, Path] = {}
    casefolded: set[str] = set()

    def visit(directory: Path) -> None:
        try:
            entries = sorted(os.scandir(directory), key=lambda entry: entry.name)
        except OSError as error:
            raise LocalBundleError("cannot enumerate the local Maven repository") from error
        for entry in entries:
            path = Path(entry.path)
            if entry.is_symlink():
                raise LocalBundleError("local Maven repository contains a symlink")
            if entry.is_dir(follow_symlinks=False):
                visit(path)
                continue
            if not entry.is_file(follow_symlinks=False):
                raise LocalBundleError("local Maven repository contains a special file")
            relative = path.relative_to(root).as_posix()
            folded = relative.casefold()
            if relative in result or folded in casefolded:
                raise LocalBundleError("local Maven repository paths collide")
            result[relative] = path
            casefolded.add(folded)
            if len(result) > MAX_REPOSITORY_FILES:
                raise LocalBundleError("local Maven repository exceeds its file-count limit")

    visit(root)
    return result


def _verify_generated_metadata(files: dict[str, Path]) -> None:
    for metadata_path in expected_generated_metadata_paths():
        if not metadata_path.endswith("/maven-metadata.xml"):
            continue
        payload = read_stable_file(
            files[metadata_path], "generated Maven metadata", MAX_METADATA_BYTES
        )
        if not payload:
            raise LocalBundleError("generated Maven metadata must not be empty")
        for algorithm, length in CHECKSUMS:
            sidecar_path = f"{metadata_path}.{algorithm}"
            sidecar = read_stable_file(
                files[sidecar_path], "generated Maven metadata checksum", 256
            )
            try:
                text = sidecar.decode("ascii").strip()
            except UnicodeDecodeError as error:
                raise LocalBundleError("generated metadata checksum is not ASCII") from error
            if len(text) != length or not hmac.compare_digest(
                text, hashlib.new(algorithm, payload).hexdigest()
            ):
                raise LocalBundleError("generated metadata checksum does not match")


def _verify_payload_checksums(
    files: dict[str, Path], payloads: dict[str, bytes]
) -> None:
    for payload_path, payload in payloads.items():
        for algorithm, length in CHECKSUMS:
            checksum_path = f"{payload_path}.{algorithm}"
            checksum = read_stable_file(
                files[checksum_path], "generated release checksum", 256
            )
            try:
                text = checksum.decode("ascii").strip()
            except UnicodeDecodeError as error:
                raise LocalBundleError("generated release checksum is not ASCII") from error
            if len(text) != length or not hmac.compare_digest(
                text, hashlib.new(algorithm, payload).hexdigest()
            ):
                raise LocalBundleError("generated release checksum does not match")


def verify_unsigned_repository(
    repository_path: Path | str, version: str
) -> dict[str, bytes]:
    """Prove the exact Gradle output consumed by the unsigned handoff."""
    version = require_canonical_version(version)
    repository = Path(repository_path)
    try:
        files = _walk_regular_files(repository)
        base_paths = expected_base_paths(version)
        checksum_paths = tuple(
            f"{path}.{algorithm}"
            for path in base_paths
            for algorithm, _length in CHECKSUMS
        )
        expected = tuple(
            sorted(base_paths + checksum_paths + expected_generated_metadata_paths())
        )
        if tuple(sorted(files)) != expected:
            missing = sorted(set(expected) - set(files))
            extra = sorted(set(files) - set(expected))
            raise LocalBundleError(
                "unsigned Gradle repository file set is not exact; "
                f"missing={missing}, extra={extra}"
            )
        payloads = {
            path: read_stable_file(
                files[path],
                "unsigned Maven release artifact",
                artifact_size_limit(artifact_identity(path, version)),
            )
            for path in base_paths
        }
        validate_base_payloads(payloads, version)
        _verify_payload_checksums(files, payloads)
        _verify_generated_metadata(files)
        return payloads
    except (EvidenceError, ReleaseContractError) as error:
        raise LocalBundleError(str(error)) from error


def verify_local_postcondition(
    repository_path: Path | str, bundle_path: Path | str, version: str
) -> None:
    version = require_canonical_version(version)
    repository = Path(repository_path)
    try:
        _manifest, bundle_payloads = inspect_bundle(bundle_path, version)
        files = _walk_regular_files(repository)
        expected_release = expected_release_paths(version)
        expected_all = tuple(
            sorted(expected_release + expected_generated_metadata_paths())
        )
        if tuple(sorted(files)) != expected_all:
            missing = sorted(set(expected_all) - set(files))
            extra = sorted(set(files) - set(expected_all))
            raise LocalBundleError(
                f"local Maven repository file set is not exact; missing={missing}, extra={extra}"
            )
        for path in expected_release:
            identity = artifact_identity(path, version)
            repository_payload = read_stable_file(
                files[path], "local Maven release artifact", artifact_size_limit(identity)
            )
            if not hmac.compare_digest(repository_payload, bundle_payloads[path]):
                raise LocalBundleError(
                    f"local repository bytes differ from the Central ZIP: {path}"
                )
        _verify_generated_metadata(files)
    except (EvidenceError, ReleaseContractError) as error:
        raise LocalBundleError(str(error)) from error


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--version", required=True)
    options = parser.parse_args(arguments)
    try:
        if options.unsigned:
            if options.bundle is not None:
                raise LocalBundleError("--bundle is not valid with --unsigned")
            verify_unsigned_repository(options.repository, options.version)
        else:
            if options.bundle is None:
                raise LocalBundleError("--bundle is required without --unsigned")
            verify_local_postcondition(options.repository, options.bundle, options.version)
    except (LocalBundleError, OSError, ValueError) as error:
        print(f"Local Maven Central bundle verification failed: {error}", file=sys.stderr)
        return 1
    if options.unsigned:
        print("Verified exact unsigned Gradle repository and 15 metadata files.")
    else:
        print("Verified exact local Maven repository and 90-file Central ZIP postcondition.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
