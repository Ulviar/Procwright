#!/usr/bin/env python3
"""Verify immutable GitHub artifact identity for the current release workflow run."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from collections.abc import Callable

from release_contract import ReleaseContractError, require_commit_sha


MAX_API_BYTES = 1024 * 1024
MAX_ARTIFACT_BYTES = 512 * 1024 * 1024
DIGEST_PATTERN = re.compile(r"[0-9a-f]{64}")


class HandoffArtifactError(RuntimeError):
    """GitHub did not prove the exact current-run handoff artifact identity."""


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise HandoffArtifactError(f"artifact JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def parse_json(payload: bytes) -> dict:
    if len(payload) > MAX_API_BYTES:
        raise HandoffArtifactError("artifact response exceeds its size limit")
    try:
        value = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise HandoffArtifactError(f"artifact response is not valid UTF-8 JSON: {error}") from error
    if not isinstance(value, dict):
        raise HandoffArtifactError("artifact response must be a JSON object")
    return value


CommandRunner = Callable[[list[str]], bytes]


def run_command(command: list[str]) -> bytes:
    result = subprocess.run(
        command,
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
    )
    if len(result.stdout) > MAX_API_BYTES or len(result.stderr) > MAX_API_BYTES:
        raise HandoffArtifactError("GitHub API command output exceeds its size limit")
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise HandoffArtifactError(f"GitHub API command failed: {message}")
    return result.stdout


def _positive_integer(value, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise HandoffArtifactError(f"{field} must be a positive integer")
    return value


def _parse_decimal(value: str, field: str) -> int:
    if not value.isdecimal() or value.startswith("0"):
        raise HandoffArtifactError(f"{field} must be a canonical positive decimal integer")
    parsed = int(value)
    if parsed <= 0:
        raise HandoffArtifactError(f"{field} must be positive")
    return parsed


def validate_artifact(
    artifact: dict,
    repository: str,
    run_id: int,
    artifact_id: int,
    expected_name: str,
    expected_digest: str,
    commit: str,
) -> None:
    if artifact.get("id") != artifact_id:
        raise HandoffArtifactError("artifact ID does not match the build job output")
    if artifact.get("name") != expected_name:
        raise HandoffArtifactError("artifact name does not match the closed handoff name")
    if artifact.get("expired") is not False:
        raise HandoffArtifactError("handoff artifact must exist and be unexpired")
    if artifact.get("digest") != f"sha256:{expected_digest}":
        raise HandoffArtifactError("artifact service digest does not match the upload action output")
    size = _positive_integer(artifact.get("size_in_bytes"), "artifact size")
    if size > MAX_ARTIFACT_BYTES:
        raise HandoffArtifactError("handoff artifact exceeds its size limit")
    expected_url = (
        f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
    )
    if artifact.get("archive_download_url") != expected_url:
        raise HandoffArtifactError("artifact download URL is not the exact GitHub API URL")
    workflow_run = artifact.get("workflow_run")
    if not isinstance(workflow_run, dict):
        raise HandoffArtifactError("artifact must expose workflow run identity")
    repository_id = _positive_integer(workflow_run.get("repository_id"), "artifact repository id")
    head_repository_id = _positive_integer(
        workflow_run.get("head_repository_id"), "artifact head repository id"
    )
    if (
        workflow_run.get("id") != run_id
        or repository_id != head_repository_id
        or workflow_run.get("head_branch") != "main"
        or workflow_run.get("head_sha") != commit
    ):
        raise HandoffArtifactError(
            "artifact was not produced by the current main workflow run at the release commit"
        )


def verify(
    repository: str,
    run_id_text: str,
    artifact_id_text: str,
    expected_name: str,
    expected_digest: str,
    commit: str,
    runner: CommandRunner = run_command,
) -> None:
    if not repository or repository.count("/") != 1:
        raise HandoffArtifactError("GITHUB_REPOSITORY must identify one owner/repository")
    run_id = _parse_decimal(run_id_text, "workflow run ID")
    artifact_id = _parse_decimal(artifact_id_text, "artifact ID")
    if DIGEST_PATTERN.fullmatch(expected_digest) is None:
        raise HandoffArtifactError("artifact digest must be a lowercase SHA-256 value")
    commit = require_commit_sha(commit)
    artifact = parse_json(
        runner(
            [
                "gh",
                "api",
                "--method",
                "GET",
                f"repos/{repository}/actions/artifacts/{artifact_id}",
            ]
        )
    )
    validate_artifact(
        artifact,
        repository,
        run_id,
        artifact_id,
        expected_name,
        expected_digest,
        commit,
    )


def main() -> int:
    try:
        verify(
            os.environ.get("GITHUB_REPOSITORY", ""),
            os.environ.get("GITHUB_RUN_ID", ""),
            os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_ID", ""),
            os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_NAME", ""),
            os.environ.get("PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST", ""),
            os.environ.get("PROCWRIGHT_RELEASE_COMMIT", ""),
        )
    except (HandoffArtifactError, ReleaseContractError, OSError, subprocess.SubprocessError) as error:
        print(f"Release handoff artifact verification failed: {error}", file=sys.stderr)
        return 1
    print("Verified immutable current-run release handoff artifact identity.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
