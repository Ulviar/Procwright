#!/usr/bin/env python3
"""Select and extract one immutable GitHub staging artifact by exact identity."""

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
    EvidenceError,
    atomic_write,
    open_stable_regular_file,
    raw_central_directory,
    read_stable_file,
)
from release_contract import (
    ReleaseContractError,
    require_canonical_version,
    require_commit_sha,
)
from verify_staging_evidence import evidence_schema


MAX_API_BYTES = 4 * 1024 * 1024
MAX_ARTIFACT_BYTES = 300 * 1024 * 1024
MAX_CENTRAL_DIRECTORY_BYTES = 256 * 1024
WORKFLOW_PATH = ".github/workflows/publish-maven-central.yml"


class StagingArtifactError(RuntimeError):
    """GitHub staging artifact selection or extraction was not exact."""


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise StagingArtifactError(f"GitHub JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def _parse_response(path: Path) -> dict:
    try:
        payload = read_stable_file(path, "GitHub API response", MAX_API_BYTES)
        value = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (EvidenceError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingArtifactError("GitHub API response is not bounded UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise StagingArtifactError("GitHub API response must be a JSON object")
    return value


def _positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise StagingArtifactError(f"{label} must be a positive integer")
    return value


def _repository_identity(value: object, repository: str, label: str) -> None:
    if not isinstance(value, dict) or value.get("full_name") != repository:
        raise StagingArtifactError(f"{label} does not match the expected repository")


def select_workflow_run(response_path: Path, repository: str, commit: str) -> str:
    commit = require_commit_sha(commit)
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise StagingArtifactError("GitHub repository identity is malformed")
    response = _parse_response(response_path)
    runs = response.get("workflow_runs")
    total = response.get("total_count")
    if (
        isinstance(total, bool)
        or not isinstance(total, int)
        or total != 1
        or not isinstance(runs, list)
        or len(runs) != 1
        or not isinstance(runs[0], dict)
    ):
        raise StagingArtifactError(
            "expected exactly one successful staging workflow run for the release commit"
        )
    run = runs[0]
    run_id = _positive_integer(run.get("id"), "staging workflow run ID")
    _positive_integer(run.get("run_attempt"), "staging workflow run attempt")
    _repository_identity(run.get("repository"), repository, "workflow repository")
    _repository_identity(
        run.get("head_repository"), repository, "workflow head repository"
    )
    head_commit = run.get("head_commit")
    if (
        run.get("path") != WORKFLOW_PATH
        or run.get("event") != "workflow_dispatch"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("head_branch") != "main"
        or run.get("head_sha") != commit
        or not isinstance(head_commit, dict)
        or head_commit.get("id") != commit
    ):
        raise StagingArtifactError(
            "staging workflow run does not match the exact workflow, commit, and main identity"
        )
    return str(run_id)


def select_artifact(
    response_path: Path,
    repository: str,
    version: str,
    commit: str,
    run_id_text: str,
) -> tuple[str, str, str]:
    version = require_canonical_version(version)
    commit = require_commit_sha(commit)
    if re.fullmatch(r"[1-9][0-9]{0,19}", run_id_text) is None:
        raise StagingArtifactError("expected staging workflow run ID is malformed")
    run_id = int(run_id_text)
    expected_name = f"procwright-{version}-{commit}-central-bundle"
    response = _parse_response(response_path)
    artifacts = response.get("artifacts")
    total = response.get("total_count")
    if (
        isinstance(total, bool)
        or not isinstance(total, int)
        or not isinstance(artifacts, list)
        or total != len(artifacts)
        or total > 100
        or any(not isinstance(artifact, dict) for artifact in artifacts)
    ):
        raise StagingArtifactError("GitHub artifact response is incomplete or ambiguous")
    matches = [
        artifact
        for artifact in artifacts
        if artifact.get("name") == expected_name and artifact.get("expired") is False
    ]
    if len(matches) != 1:
        raise StagingArtifactError(
            "expected exactly one unexpired staging artifact with the closed name"
        )
    artifact = matches[0]
    artifact_id = _positive_integer(artifact.get("id"), "staging artifact ID")
    size = _positive_integer(artifact.get("size_in_bytes"), "staging artifact size")
    if size > MAX_ARTIFACT_BYTES:
        raise StagingArtifactError("staging artifact exceeds its size limit")
    digest = artifact.get("digest")
    if not isinstance(digest, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
        raise StagingArtifactError("staging artifact has no canonical service digest")
    expected_url = (
        f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
    )
    workflow_run = artifact.get("workflow_run")
    if not isinstance(workflow_run, dict):
        raise StagingArtifactError("staging artifact has no workflow run identity")
    repository_id = _positive_integer(
        workflow_run.get("repository_id"), "artifact repository ID"
    )
    head_repository_id = _positive_integer(
        workflow_run.get("head_repository_id"), "artifact head repository ID"
    )
    if (
        artifact.get("archive_download_url") != expected_url
        or workflow_run.get("id") != run_id
        or repository_id != head_repository_id
        or workflow_run.get("head_branch") != "main"
        or workflow_run.get("head_sha") != commit
    ):
        raise StagingArtifactError(
            "staging artifact does not belong to the expected workflow run and commit"
        )
    return str(artifact_id), expected_name, digest.removeprefix("sha256:")


def _prepare_destination(path: Path) -> Path:
    if path.exists() or path.is_symlink():
        raise StagingArtifactError("staging evidence destination must not pre-exist")
    parent = path.parent
    if not parent.exists():
        parent.mkdir(mode=0o700)
    try:
        metadata = parent.lstat()
    except OSError as error:
        raise StagingArtifactError("cannot inspect staging destination parent") from error
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_ISLNK(metadata.st_mode)
        or hasattr(os, "getuid")
        and metadata.st_uid != os.getuid()
        or metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH)
    ):
        raise StagingArtifactError("staging destination parent is unsafe")
    path.mkdir(mode=0o700)
    return path


def extract_artifact(
    archive_path: Path,
    destination_path: Path,
    version: str,
    expected_digest: str,
) -> None:
    schema = evidence_schema(version)
    expected = {spec[0]: spec[1] for spec in schema.values()}
    if re.fullmatch(r"[0-9a-f]{64}", expected_digest) is None:
        raise StagingArtifactError("expected staging artifact digest is malformed")
    destination = _prepare_destination(destination_path)
    try:
        payloads: dict[str, bytes] = {}
        with open_stable_regular_file(
            archive_path, "raw GitHub staging artifact", MAX_ARTIFACT_BYTES
        ) as source:
            digest = hashlib.sha256()
            while True:
                chunk = source.read(128 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
            if not hmac.compare_digest(digest.hexdigest(), expected_digest):
                raise StagingArtifactError(
                    "raw staging artifact differs from the GitHub service digest"
                )
            size = os.fstat(source.fileno()).st_size
            source.seek(0)
            raw_entries = raw_central_directory(
                source,
                size,
                maximum_entries=len(expected),
                maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
                maximum_entry_bytes=max(expected.values()),
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
                    raise StagingArtifactError(
                        "raw staging artifact file set does not match the exact schema"
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
                        raise StagingArtifactError(
                            "raw staging artifact contains an unsafe entry"
                        )
                    limit = expected[raw.name]
                    with archive.open(info) as entry:
                        payload = entry.read(limit + 1)
                    if not payload or len(payload) > limit or len(payload) != info.file_size:
                        raise StagingArtifactError(
                            "raw staging artifact entry violates its schema bound"
                        )
                    payloads[raw.name] = payload
        if set(payloads) != set(expected):
            raise StagingArtifactError("extracted staging evidence file set is not exact")
        for name, payload in sorted(payloads.items()):
            output = destination / name
            atomic_write(output, payload, maximum_size=expected[name])
            output.chmod(0o600)
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("select-run")
    run.add_argument("--response", type=Path, required=True)
    run.add_argument("--repository", required=True)
    run.add_argument("--commit", required=True)
    artifact = commands.add_parser("select-artifact")
    artifact.add_argument("--response", type=Path, required=True)
    artifact.add_argument("--repository", required=True)
    artifact.add_argument("--version", required=True)
    artifact.add_argument("--commit", required=True)
    artifact.add_argument("--run-id", required=True)
    extract = commands.add_parser("extract")
    extract.add_argument("--archive", type=Path, required=True)
    extract.add_argument("--directory", type=Path, required=True)
    extract.add_argument("--version", required=True)
    extract.add_argument("--digest", required=True)
    options = parser.parse_args(arguments)
    try:
        if options.command == "select-run":
            print(
                select_workflow_run(
                    options.response, options.repository, options.commit
                )
            )
        elif options.command == "select-artifact":
            print(
                "\t".join(
                    select_artifact(
                        options.response,
                        options.repository,
                        options.version,
                        options.commit,
                        options.run_id,
                    )
                )
            )
        else:
            extract_artifact(
                options.archive, options.directory, options.version, options.digest
            )
    except (
        EvidenceError,
        OSError,
        ReleaseContractError,
        StagingArtifactError,
        zipfile.BadZipFile,
    ) as error:
        reason = "".join(
            character if 32 <= ord(character) < 127 else "?"
            for character in str(error)
        )[:240]
        print(f"Staging artifact verification failed: {reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
