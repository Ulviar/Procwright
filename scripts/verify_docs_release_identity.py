#!/usr/bin/env python3
"""Verify immutable release identity and exact fresh release-event evidence."""

from __future__ import annotations

import hashlib
import hmac
import io
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from maven_release_evidence import raw_central_directory
from release_contract import (
    ReleaseContractError,
    require_canonical_version,
    require_commit_sha,
)
from staging_release_artifact import extract_artifact
from verify_staging_evidence import verify_staging_evidence


MAX_COMMAND_OUTPUT_BYTES = 4 * 1024 * 1024
MAX_ARTIFACT_BYTES = 300 * 1024 * 1024
MAX_CONSUMER_PROOF_BYTES = 16 * 1024
MAX_CENTRAL_DIRECTORY_BYTES = 64 * 1024
COMMAND_TIMEOUT_SECONDS = 60
STAGING_WORKFLOW = "publish-maven-central.yml"
STAGING_WORKFLOW_PATH = ".github/workflows/publish-maven-central.yml"
CONSUMER_WORKFLOW = "ci.yml"
CONSUMER_WORKFLOW_PATH = ".github/workflows/ci.yml"


class ReleaseIdentityError(RuntimeError):
    """Release identity or producer evidence is incomplete, ambiguous, or untrusted."""


def _reject_duplicate_json_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseIdentityError(f"GitHub API JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def parse_release_json(payload: bytes, description: str = "GitHub release response") -> dict:
    if len(payload) > MAX_COMMAND_OUTPUT_BYTES:
        raise ReleaseIdentityError(f"{description} exceeds its size limit")
    try:
        parsed = json.loads(payload, object_pairs_hook=_reject_duplicate_json_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseIdentityError(
            f"{description} is not valid UTF-8 JSON: {error}"
        ) from error
    if not isinstance(parsed, dict):
        raise ReleaseIdentityError(f"{description} must be a JSON object")
    return parsed


def validate_release_fields(release: dict, expected_tag: str) -> None:
    if release.get("tag_name") != expected_tag:
        raise ReleaseIdentityError(f"GitHub Release tag must be exactly {expected_tag}")
    if release.get("draft") is not False:
        raise ReleaseIdentityError(f"GitHub Release {expected_tag} must not be a draft")
    if release.get("immutable") is not True:
        raise ReleaseIdentityError(
            f"GitHub Release {expected_tag} must have immutable releases enabled and be immutable"
        )


CommandRunner = Callable[[list[str]], bytes]
ArtifactDownloader = Callable[[str, int, int], bytes]
StagingValidator = Callable[[bytes, str, str, str, int, int, str, str], tuple[str, str]]


def run_command(command: list[str]) -> bytes:
    result = subprocess.run(
        command,
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=COMMAND_TIMEOUT_SECONDS,
    )
    if len(result.stdout) > MAX_COMMAND_OUTPUT_BYTES or len(result.stderr) > MAX_COMMAND_OUTPUT_BYTES:
        raise ReleaseIdentityError(f"command output exceeds its size limit: {command[0]}")
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise ReleaseIdentityError(f"command failed ({command[0]}): {message}")
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
            raise ReleaseIdentityError("GitHub artifact download failed") from error
        if len(result.stderr) > MAX_COMMAND_OUTPUT_BYTES:
            raise ReleaseIdentityError("GitHub artifact download error exceeds its size limit")
        if result.returncode != 0:
            raise ReleaseIdentityError("GitHub artifact download failed")
        size = output.tell()
        if size <= 0 or size > maximum_size:
            raise ReleaseIdentityError("GitHub artifact download violates its size bound")
        output.seek(0)
        return output.read()


def _single_line(command: list[str], runner: CommandRunner) -> str:
    try:
        lines = runner(command).decode("utf-8", errors="strict").splitlines()
    except UnicodeDecodeError as error:
        raise ReleaseIdentityError(
            f"command returned malformed UTF-8: {command[0]}"
        ) from error
    if len(lines) != 1 or not lines[0]:
        raise ReleaseIdentityError(f"command must return exactly one line: {command[0]}")
    return lines[0]


def _positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ReleaseIdentityError(f"{label} must be a positive integer")
    return value


def _repository_identity(value: object, repository: str, label: str) -> None:
    if not isinstance(value, dict) or value.get("full_name") != repository:
        raise ReleaseIdentityError(f"{label} does not match the trusted repository")


def release_version(event_name: str, release_ref: str) -> str:
    if event_name == "release":
        if not release_ref.startswith("v"):
            raise ReleaseIdentityError("release event ref must start with exactly one v")
        version = require_canonical_version(release_ref[1:])
        if release_ref != f"v{version}":
            raise ReleaseIdentityError(f"release event tag must be exactly v{version}")
        return version
    if event_name == "workflow_dispatch":
        return require_canonical_version(release_ref)
    raise ReleaseIdentityError(f"unsupported docs deployment event: {event_name!r}")


@dataclass(frozen=True)
class ArtifactIdentity:
    artifact_id: int
    name: str
    digest: str
    size: int


def _require_workflow_identity(
    repository: str,
    workflow_file: str,
    workflow_path: str,
    runner: CommandRunner,
) -> int:
    payload = runner(
        [
            "gh",
            "api",
            "--method",
            "GET",
            f"repos/{repository}/actions/workflows/{workflow_file}",
        ]
    )
    workflow = parse_release_json(payload, f"{workflow_file} workflow response")
    workflow_id = _positive_integer(workflow.get("id"), f"{workflow_file} workflow ID")
    if workflow.get("path") != workflow_path or workflow.get("state") != "active":
        raise ReleaseIdentityError(
            f"{workflow_file} is not the exact active trusted workflow"
        )
    return workflow_id


def _require_exact_producer_run(
    repository: str,
    workflow_id: int,
    workflow_path: str,
    commit: str,
    runner: CommandRunner,
) -> int:
    payload = runner(
        [
            "gh",
            "api",
            "--method",
            "GET",
            f"repos/{repository}/actions/workflows/{workflow_id}/runs",
            "-f",
            "branch=main",
            "-f",
            "event=workflow_dispatch",
            "-f",
            f"head_sha={commit}",
            "-f",
            "status=success",
            "-f",
            "per_page=100",
        ]
    )
    response = parse_release_json(payload, f"{workflow_path} runs response")
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
        raise ReleaseIdentityError(
            f"{workflow_path} must have exactly one producer run for {commit}"
        )
    run = runs[0]
    run_id = _positive_integer(run.get("id"), f"{workflow_path} run ID")
    _positive_integer(run.get("run_attempt"), f"{workflow_path} run attempt")
    _repository_identity(run.get("repository"), repository, "workflow repository")
    _repository_identity(run.get("head_repository"), repository, "workflow head repository")
    head_commit = run.get("head_commit")
    if (
        run.get("workflow_id") != workflow_id
        or run.get("path") != workflow_path
        or run.get("event") != "workflow_dispatch"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("head_branch") != "main"
        or run.get("head_sha") != commit
        or not isinstance(head_commit, dict)
        or head_commit.get("id") != commit
    ):
        raise ReleaseIdentityError(
            f"{workflow_path} producer run is not bound to the trusted workflow revision and release commit"
        )
    return run_id


def _require_exact_artifact(
    repository: str,
    run_id: int,
    commit: str,
    expected_name: str,
    runner: CommandRunner,
) -> ArtifactIdentity:
    payload = runner(
        [
            "gh",
            "api",
            "--method",
            "GET",
            f"repos/{repository}/actions/runs/{run_id}/artifacts",
            "-f",
            f"name={expected_name}",
            "-f",
            "per_page=100",
        ]
    )
    response = parse_release_json(payload, f"{expected_name} artifact response")
    artifacts = response.get("artifacts")
    total = response.get("total_count")
    if (
        isinstance(total, bool)
        or not isinstance(total, int)
        or total != 1
        or not isinstance(artifacts, list)
        or len(artifacts) != 1
        or not isinstance(artifacts[0], dict)
    ):
        raise ReleaseIdentityError(
            f"producer run must contain exactly one artifact named {expected_name}"
        )
    artifact = artifacts[0]
    artifact_id = _positive_integer(artifact.get("id"), f"{expected_name} artifact ID")
    size = _positive_integer(artifact.get("size_in_bytes"), f"{expected_name} artifact size")
    digest = artifact.get("digest")
    workflow_run = artifact.get("workflow_run")
    if (
        artifact.get("name") != expected_name
        or artifact.get("expired") is not False
        or size > MAX_ARTIFACT_BYTES
        or not isinstance(digest, str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None
        or artifact.get("archive_download_url")
        != f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
        or not isinstance(workflow_run, dict)
        or workflow_run.get("id") != run_id
        or workflow_run.get("head_branch") != "main"
        or workflow_run.get("head_sha") != commit
        or _positive_integer(workflow_run.get("repository_id"), "artifact repository ID")
        != _positive_integer(
            workflow_run.get("head_repository_id"), "artifact head repository ID"
        )
    ):
        raise ReleaseIdentityError(
            f"artifact {expected_name} is not an immutable artifact of the exact producer run"
        )
    return ArtifactIdentity(artifact_id, expected_name, digest.removeprefix("sha256:"), size)


def _download_exact_artifact(
    repository: str,
    artifact: ArtifactIdentity,
    downloader: ArtifactDownloader,
) -> bytes:
    payload = downloader(repository, artifact.artifact_id, MAX_ARTIFACT_BYTES)
    if not payload or len(payload) > MAX_ARTIFACT_BYTES:
        raise ReleaseIdentityError("downloaded GitHub artifact violates its size bound")
    if not hmac.compare_digest(hashlib.sha256(payload).hexdigest(), artifact.digest):
        raise ReleaseIdentityError(
            f"raw artifact bytes do not match service digest for artifact {artifact.artifact_id}"
        )
    return payload


def _select_and_download_producer_artifact(
    repository: str,
    workflow_file: str,
    workflow_path: str,
    commit: str,
    expected_name: str,
    runner: CommandRunner,
    downloader: ArtifactDownloader,
) -> tuple[int, ArtifactIdentity, bytes]:
    workflow_id = _require_workflow_identity(
        repository, workflow_file, workflow_path, runner
    )
    run_id = _require_exact_producer_run(
        repository, workflow_id, workflow_path, commit, runner
    )
    artifact = _require_exact_artifact(
        repository, run_id, commit, expected_name, runner
    )
    return run_id, artifact, _download_exact_artifact(repository, artifact, downloader)


def _validate_staging_artifact(
    archive: bytes,
    version: str,
    commit: str,
    repository: str,
    run_id: int,
    artifact_id: int,
    artifact_name: str,
    artifact_digest: str,
) -> tuple[str, str]:
    with tempfile.TemporaryDirectory(prefix="procwright-docs-release-") as directory:
        root = Path(directory)
        archive_path = root / "staging.zip"
        archive_path.write_bytes(archive)
        destination = root / "evidence"
        extract_artifact(archive_path, destination, version, artifact_digest)
        return verify_staging_evidence(
            destination,
            version,
            commit,
            repository,
            str(run_id),
            str(artifact_id),
            artifact_name,
            artifact_digest,
        )


def _single_artifact_file(archive_payload: bytes, expected_name: str) -> bytes:
    source = io.BytesIO(archive_payload)
    raw_entries = raw_central_directory(
        source,
        len(archive_payload),
        maximum_entries=1,
        maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
        maximum_entry_bytes=MAX_CONSUMER_PROOF_BYTES,
        maximum_total_compressed_bytes=MAX_ARTIFACT_BYTES,
        maximum_total_bytes=MAX_CONSUMER_PROOF_BYTES,
        allow_directories=False,
        allow_signed_data_descriptors=True,
    )
    source.seek(0)
    with zipfile.ZipFile(source) as archive:
        infos = archive.infolist()
        if len(raw_entries) != 1 or len(infos) != 1:
            raise ReleaseIdentityError("consumer proof artifact file set is not exact")
        raw = raw_entries[0]
        info = infos[0]
        file_type = stat.S_IFMT(info.external_attr >> 16)
        if (
            raw.name != expected_name
            or info.filename != expected_name
            or info.orig_filename != expected_name
            or info.is_dir()
            or file_type not in (0, stat.S_IFREG)
            or info.flag_bits & 0x1
        ):
            raise ReleaseIdentityError("consumer proof artifact contains an unsafe entry")
        with archive.open(info) as entry:
            payload = entry.read(MAX_CONSUMER_PROOF_BYTES + 1)
        if not payload or len(payload) > MAX_CONSUMER_PROOF_BYTES or len(payload) != info.file_size:
            raise ReleaseIdentityError("consumer proof payload violates its size bound")
        return payload


def _canonical_consumer_proof(
    version: str,
    commit: str,
    bundle_sha256: str,
    verification_metadata_sha256: str,
    deployment_id: str,
    stage_run_id: int,
) -> bytes:
    return (
        f"version={version}\n"
        f"commit={commit}\n"
        f"bundle_sha256={bundle_sha256}\n"
        f"verification_metadata_sha256={verification_metadata_sha256}\n"
        f"central_deployment_id={deployment_id}\n"
        "central_state=PUBLISHED\n"
        f"stage_run_id={stage_run_id}\n"
        f"workflow_sha={commit}\n"
    ).encode("ascii")


def verify_from_environment(
    runner: CommandRunner = run_command,
    downloader: ArtifactDownloader = download_artifact,
    staging_validator: StagingValidator = _validate_staging_artifact,
) -> None:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    event_name = os.environ.get("PROCWRIGHT_EVENT_NAME", "")
    release_ref = os.environ.get("PROCWRIGHT_RELEASE_REF", "")
    requested_commit = os.environ.get("PROCWRIGHT_RELEASE_COMMIT", "")
    trusted_root = os.environ.get("PROCWRIGHT_TRUSTED_ROOT", "")
    workflow_sha = require_commit_sha(os.environ.get("PROCWRIGHT_WORKFLOW_SHA", ""))
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise ReleaseIdentityError("GITHUB_REPOSITORY must identify one owner/repository")
    if not trusted_root:
        raise ReleaseIdentityError("PROCWRIGHT_TRUSTED_ROOT is required")
    version = release_version(event_name, release_ref)
    tag = f"v{version}"

    trusted_commit = require_commit_sha(
        _single_line(["git", "-C", trusted_root, "rev-parse", "HEAD^{commit}"], runner)
    )
    if trusted_commit != workflow_sha:
        raise ReleaseIdentityError(
            f"trusted control is {trusted_commit}, expected workflow revision {workflow_sha}"
        )
    commit = require_commit_sha(
        _single_line(
            ["git", "-C", trusted_root, "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"],
            runner,
        )
    )
    if requested_commit:
        require_commit_sha(requested_commit)
        if requested_commit != commit:
            raise ReleaseIdentityError(f"tag {tag} resolves to {commit}, expected {requested_commit}")

    release_payload = runner(["gh", "api", f"repos/{repository}/releases/tags/{tag}"])
    validate_release_fields(parse_release_json(release_payload), tag)

    if event_name == "release":
        stage_name = f"procwright-{version}-{commit}-central-bundle"
        stage_run_id, stage_artifact, stage_archive = _select_and_download_producer_artifact(
            repository,
            STAGING_WORKFLOW,
            STAGING_WORKFLOW_PATH,
            commit,
            stage_name,
            runner,
            downloader,
        )
        deployment_id, bundle_sha256 = staging_validator(
            stage_archive,
            version,
            commit,
            repository,
            stage_run_id,
            stage_artifact.artifact_id,
            stage_artifact.name,
            stage_artifact.digest,
        )

        consumer_name = f"procwright-{version}-{commit}-central-consumer-smoke"
        _consumer_run_id, _consumer_artifact, consumer_archive = (
            _select_and_download_producer_artifact(
                repository,
                CONSUMER_WORKFLOW,
                CONSUMER_WORKFLOW_PATH,
                commit,
                consumer_name,
                runner,
                downloader,
            )
        )
        verification_metadata = runner(
            [
                "git",
                "-C",
                trusted_root,
                "show",
                f"{commit}:gradle/verification-metadata.xml",
            ]
        )
        expected_proof = _canonical_consumer_proof(
            version,
            commit,
            bundle_sha256,
            hashlib.sha256(verification_metadata).hexdigest(),
            deployment_id,
            stage_run_id,
        )
        actual_proof = _single_artifact_file(consumer_archive, "provenance.txt")
        if not hmac.compare_digest(actual_proof, expected_proof):
            raise ReleaseIdentityError(
                "consumer proof content is not canonical for the exact release producers"
            )

    output_path = os.environ.get("GITHUB_OUTPUT", "")
    if output_path:
        with Path(output_path).open("a", encoding="utf-8", newline="\n") as output:
            output.write(f"release_commit={commit}\n")
            output.write(f"release_version={version}\n")


def main() -> int:
    try:
        verify_from_environment()
    except (ReleaseContractError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Docs release identity verification failed: {error}", file=sys.stderr)
        return 1
    print("Verified exact immutable GitHub Release identity and bound release evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
