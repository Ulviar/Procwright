#!/usr/bin/env python3
"""Seal and verify the exact current-run GitHub Pages artifact content."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from maven_release_evidence import EvidenceError, open_stable_regular_file, raw_central_directory
from release_contract import ReleaseContractError, require_commit_sha


MAX_API_BYTES = 4 * 1024 * 1024
MAX_ARCHIVE_BYTES = 300 * 1024 * 1024
MAX_SITE_BYTES = 250 * 1024 * 1024
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_SITE_FILES = 100_000
MAX_CENTRAL_DIRECTORY_BYTES = 64 * 1024
COMMAND_TIMEOUT_SECONDS = 60
PAGES_ARTIFACT_NAME = "github-pages"


class PagesArtifactError(RuntimeError):
    """The Pages build-to-deploy artifact binding is incomplete or inconsistent."""


CommandRunner = Callable[[list[str]], bytes]
ArtifactDownloader = Callable[[str, int, int], bytes]


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PagesArtifactError(f"GitHub API JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def parse_json(payload: bytes, description: str) -> dict:
    if len(payload) > MAX_API_BYTES:
        raise PagesArtifactError(f"{description} exceeds its size limit")
    try:
        value = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PagesArtifactError(f"{description} is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise PagesArtifactError(f"{description} must be a JSON object")
    return value


def run_command(command: list[str]) -> bytes:
    result = subprocess.run(
        command,
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=COMMAND_TIMEOUT_SECONDS,
    )
    if len(result.stdout) > MAX_API_BYTES or len(result.stderr) > MAX_API_BYTES:
        raise PagesArtifactError(f"command output exceeds its size limit: {command[0]}")
    if result.returncode != 0:
        raise PagesArtifactError(f"command failed: {command[0]}")
    return result.stdout


def download_artifact(repository: str, artifact_id: int, maximum_size: int) -> bytes:
    with tempfile.TemporaryFile() as output:
        try:
            result = subprocess.run(
                [
                    "gh",
                    "api",
                    "--method",
                    "GET",
                    f"repos/{repository}/actions/artifacts/{artifact_id}/zip",
                ],
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=subprocess.PIPE,
                timeout=COMMAND_TIMEOUT_SECONDS,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise PagesArtifactError("Pages artifact download failed") from error
        if result.returncode != 0 or len(result.stderr) > MAX_API_BYTES:
            raise PagesArtifactError("Pages artifact download failed")
        size = output.tell()
        if size <= 0 or size > maximum_size:
            raise PagesArtifactError("Pages artifact download violates its size bound")
        output.seek(0)
        return output.read()


def _positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise PagesArtifactError(f"{label} must be a positive integer")
    return value


def _positive_integer_text(value: str, label: str) -> int:
    if re.fullmatch(r"[1-9][0-9]{0,19}", value) is None:
        raise PagesArtifactError(f"{label} must be a canonical positive integer")
    return int(value)


def _canonical_digest(files: Mapping[str, bytes]) -> str:
    if "index.html" not in files or not files:
        raise PagesArtifactError("Pages content must contain index.html")
    records = []
    total = 0
    for path, payload in sorted(files.items()):
        total += len(payload)
        if total > MAX_SITE_BYTES:
            raise PagesArtifactError("Pages content exceeds its total size limit")
        records.append(
            {
                "path": path,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
            }
        )
    manifest = (json.dumps(records, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )
    return hashlib.sha256(manifest).hexdigest()


def _visible_relative_path(relative: Path) -> str | None:
    parts = relative.parts
    if not parts or any(part in ("", ".", "..") for part in parts):
        raise PagesArtifactError("Pages source contains a non-canonical path")
    if any(part.startswith(".") for part in parts):
        return None
    value = PurePosixPath(*parts).as_posix()
    if "\\" in value or value.startswith("/"):
        raise PagesArtifactError("Pages source contains an unsafe path")
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as error:
        raise PagesArtifactError("Pages source path is not canonical UTF-8") from error
    return value


def seal_directory(directory_path: Path | str) -> str:
    directory = Path(directory_path)
    try:
        root_metadata = directory.lstat()
    except OSError as error:
        raise PagesArtifactError("cannot inspect Pages source directory") from error
    if not stat.S_ISDIR(root_metadata.st_mode) or stat.S_ISLNK(root_metadata.st_mode):
        raise PagesArtifactError("Pages source must be a real directory")
    files: dict[str, bytes] = {}
    stack = [directory]
    while stack:
        current = stack.pop()
        try:
            entries = sorted(os.scandir(current), key=lambda entry: entry.name)
        except OSError as error:
            raise PagesArtifactError("cannot enumerate Pages source") from error
        for entry in entries:
            relative = Path(entry.path).relative_to(directory)
            visible = _visible_relative_path(relative)
            metadata = entry.stat(follow_symlinks=False)
            if visible is None:
                continue
            if stat.S_ISLNK(metadata.st_mode):
                raise PagesArtifactError("Pages source must not contain symlinks")
            if stat.S_ISDIR(metadata.st_mode):
                stack.append(Path(entry.path))
                continue
            if not stat.S_ISREG(metadata.st_mode):
                raise PagesArtifactError("Pages source must contain only regular files")
            if metadata.st_nlink != 1:
                raise PagesArtifactError("Pages source must not contain hard-linked files")
            try:
                with open_stable_regular_file(
                    Path(entry.path), "Pages source file", MAX_FILE_BYTES
                ) as source:
                    payload = source.read(MAX_FILE_BYTES + 1)
            except RuntimeError as error:
                raise PagesArtifactError(str(error)) from error
            if len(payload) > MAX_FILE_BYTES:
                raise PagesArtifactError("Pages source file exceeds its size limit")
            files[visible] = payload
            if len(files) > MAX_SITE_FILES:
                raise PagesArtifactError("Pages source contains too many files")
    return _canonical_digest(files)


def _normalize_tar_path(name: str) -> str | None:
    if name in (".", "./"):
        return None
    while name.startswith("./"):
        name = name[2:]
    if not name or name.startswith("/") or "\\" in name:
        raise PagesArtifactError("Pages tar contains an unsafe path")
    path = PurePosixPath(name)
    if any(part in ("", ".", "..") for part in path.parts):
        raise PagesArtifactError("Pages tar contains a non-canonical path")
    if any(part.startswith(".") for part in path.parts):
        raise PagesArtifactError("Pages tar unexpectedly contains hidden content")
    value = path.as_posix()
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as error:
        raise PagesArtifactError("Pages tar path is not canonical UTF-8") from error
    return value


def _extract_artifact_tar(archive_payload: bytes) -> bytes:
    source = io.BytesIO(archive_payload)
    try:
        raw_entries = raw_central_directory(
            source,
            len(archive_payload),
            maximum_entries=1,
            maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
            maximum_entry_bytes=MAX_SITE_BYTES,
            maximum_total_compressed_bytes=MAX_ARCHIVE_BYTES,
            maximum_total_bytes=MAX_SITE_BYTES,
            allow_directories=False,
            allow_signed_data_descriptors=True,
        )
    except EvidenceError as error:
        raise PagesArtifactError("Pages artifact ZIP structure is invalid") from error
    source.seek(0)
    with zipfile.ZipFile(source) as archive:
        infos = archive.infolist()
        if len(raw_entries) != 1 or len(infos) != 1:
            raise PagesArtifactError("Pages artifact ZIP file set is not exact")
        raw = raw_entries[0]
        info = infos[0]
        file_type = stat.S_IFMT(info.external_attr >> 16)
        if (
            raw.name != "artifact.tar"
            or info.filename != "artifact.tar"
            or info.orig_filename != "artifact.tar"
            or info.is_dir()
            or file_type not in (0, stat.S_IFREG)
            or info.flag_bits & 0x1
        ):
            raise PagesArtifactError("Pages artifact ZIP contains an unsafe entry")
        with archive.open(info) as entry:
            payload = entry.read(MAX_SITE_BYTES + 1)
        if not payload or len(payload) > MAX_SITE_BYTES or len(payload) != info.file_size:
            raise PagesArtifactError("Pages artifact tar violates its size bound")
        return payload


def pages_tar_digest(tar_payload: bytes) -> str:
    files: dict[str, bytes] = {}
    seen: set[str] = set()
    try:
        with tarfile.open(fileobj=io.BytesIO(tar_payload), mode="r:") as archive:
            members = archive.getmembers()
            if len(members) > MAX_SITE_FILES * 2 + 1:
                raise PagesArtifactError("Pages tar contains too many entries")
            for member in members:
                path = _normalize_tar_path(member.name)
                if path is None:
                    if not member.isdir():
                        raise PagesArtifactError("Pages tar root entry must be a directory")
                    continue
                if path in seen:
                    raise PagesArtifactError("Pages tar contains duplicate paths")
                seen.add(path)
                if member.isdir():
                    continue
                if not member.isreg() or member.issym() or member.islnk():
                    raise PagesArtifactError("Pages tar contains a non-regular entry")
                if member.size < 0 or member.size > MAX_FILE_BYTES:
                    raise PagesArtifactError("Pages tar file violates its size bound")
                source = archive.extractfile(member)
                if source is None:
                    raise PagesArtifactError("Pages tar regular file cannot be read")
                payload = source.read(MAX_FILE_BYTES + 1)
                if len(payload) != member.size or len(payload) > MAX_FILE_BYTES:
                    raise PagesArtifactError("Pages tar file content is truncated or oversized")
                files[path] = payload
                if len(files) > MAX_SITE_FILES:
                    raise PagesArtifactError("Pages tar contains too many files")
    except (tarfile.TarError, OSError) as error:
        raise PagesArtifactError("Pages artifact does not contain a valid tar") from error
    return _canonical_digest(files)


@dataclass(frozen=True)
class ArtifactIdentity:
    artifact_id: int
    digest: str
    size: int


def _artifact_identity(
    artifact: object,
    repository: str,
    expected_id: int,
    run_id: int,
    run_commit: str,
) -> ArtifactIdentity:
    if not isinstance(artifact, dict):
        raise PagesArtifactError("Pages artifact metadata must be a JSON object")
    artifact_id = _positive_integer(artifact.get("id"), "Pages artifact ID")
    size = _positive_integer(artifact.get("size_in_bytes"), "Pages artifact size")
    digest = artifact.get("digest")
    workflow_run = artifact.get("workflow_run")
    if (
        artifact_id != expected_id
        or artifact.get("name") != PAGES_ARTIFACT_NAME
        or artifact.get("expired") is not False
        or size > MAX_ARCHIVE_BYTES
        or not isinstance(digest, str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None
        or artifact.get("archive_download_url")
        != f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
        or not isinstance(workflow_run, dict)
        or workflow_run.get("id") != run_id
        or workflow_run.get("head_sha") != run_commit
        or _positive_integer(workflow_run.get("repository_id"), "artifact repository ID")
        != _positive_integer(
            workflow_run.get("head_repository_id"), "artifact head repository ID"
        )
    ):
        raise PagesArtifactError(
            "Pages artifact is not the exact immutable artifact from the current workflow run"
        )
    return ArtifactIdentity(artifact_id, digest.removeprefix("sha256:"), size)


def verify_artifact(
    repository: str,
    artifact_id: int,
    run_id: int,
    run_commit: str,
    expected_content_digest: str,
    runner: CommandRunner = run_command,
    downloader: ArtifactDownloader = download_artifact,
) -> str:
    run_commit = require_commit_sha(run_commit)
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise PagesArtifactError("GitHub repository identity is malformed")
    if re.fullmatch(r"[0-9a-f]{64}", expected_content_digest) is None:
        raise PagesArtifactError("expected Pages content digest is malformed")
    list_response = parse_json(
        runner(
            [
                "gh",
                "api",
                "--method",
                "GET",
                f"repos/{repository}/actions/runs/{run_id}/artifacts",
                "-f",
                f"name={PAGES_ARTIFACT_NAME}",
                "-f",
                "per_page=100",
            ]
        ),
        "current-run Pages artifacts response",
    )
    artifacts = list_response.get("artifacts")
    total = list_response.get("total_count")
    if (
        isinstance(total, bool)
        or not isinstance(total, int)
        or total != 1
        or not isinstance(artifacts, list)
        or len(artifacts) != 1
    ):
        raise PagesArtifactError(
            "current workflow run must contain exactly one Pages artifact"
        )
    listed = _artifact_identity(
        artifacts[0], repository, artifact_id, run_id, run_commit
    )
    detail_response = parse_json(
        runner(
            [
                "gh",
                "api",
                "--method",
                "GET",
                f"repos/{repository}/actions/artifacts/{artifact_id}",
            ]
        ),
        "Pages artifact detail response",
    )
    detailed = _artifact_identity(
        detail_response, repository, artifact_id, run_id, run_commit
    )
    if listed != detailed:
        raise PagesArtifactError("Pages artifact list and immutable detail identity differ")
    raw_archive = downloader(repository, artifact_id, MAX_ARCHIVE_BYTES)
    if not raw_archive or len(raw_archive) > MAX_ARCHIVE_BYTES:
        raise PagesArtifactError("raw Pages artifact violates its size bound")
    raw_digest = hashlib.sha256(raw_archive).hexdigest()
    if not hmac.compare_digest(raw_digest, detailed.digest):
        raise PagesArtifactError("raw Pages artifact differs from its immutable API digest")
    observed_content_digest = pages_tar_digest(_extract_artifact_tar(raw_archive))
    if not hmac.compare_digest(observed_content_digest, expected_content_digest):
        raise PagesArtifactError(
            "Pages artifact content differs from the exact sealed build output"
        )
    return raw_digest


def _write_output(name: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8", newline="\n") as output:
            output.write(f"{name}={value}\n")


def verify_from_environment(
    runner: CommandRunner = run_command,
    downloader: ArtifactDownloader = download_artifact,
) -> str:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    artifact_id = _positive_integer_text(
        os.environ.get("PROCWRIGHT_PAGES_ARTIFACT_ID", ""), "Pages artifact ID"
    )
    run_id = _positive_integer_text(
        os.environ.get("PROCWRIGHT_PAGES_RUN_ID", ""), "Pages workflow run ID"
    )
    run_commit = os.environ.get("PROCWRIGHT_PAGES_RUN_COMMIT", "")
    expected_content_digest = os.environ.get("PROCWRIGHT_PAGES_CONTENT_SHA256", "")
    trusted_root = os.environ.get("PROCWRIGHT_TRUSTED_ROOT", "")
    workflow_sha = require_commit_sha(os.environ.get("PROCWRIGHT_WORKFLOW_SHA", ""))
    if not trusted_root:
        raise PagesArtifactError("trusted control checkout is required")
    try:
        trusted_lines = runner(
            ["git", "-C", trusted_root, "rev-parse", "HEAD^{commit}"]
        ).decode("ascii", errors="strict").splitlines()
    except UnicodeDecodeError as error:
        raise PagesArtifactError("trusted checkout identity is malformed") from error
    if trusted_lines != [workflow_sha]:
        raise PagesArtifactError("trusted checkout does not match the workflow revision")
    return verify_artifact(
        repository,
        artifact_id,
        run_id,
        run_commit,
        expected_content_digest,
        runner,
        downloader,
    )


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    seal = subparsers.add_parser("seal")
    seal.add_argument("--directory", type=Path, required=True)
    subparsers.add_parser("verify")
    options = parser.parse_args(arguments)
    try:
        if options.command == "seal":
            digest = seal_directory(options.directory)
            _write_output("pages_content_sha256", digest)
            print(f"Sealed Pages content SHA-256: {digest}")
        else:
            digest = verify_from_environment()
            print(f"Verified exact current-run Pages artifact SHA-256: {digest}")
    except (OSError, ReleaseContractError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Pages artifact verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
