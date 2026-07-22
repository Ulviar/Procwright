#!/usr/bin/env python3
"""Prove that the current trusted main commit passed its own CI workflow revision."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from collections.abc import Callable

from release_contract import ReleaseContractError, require_commit_sha


MAX_API_BYTES = 2 * 1024 * 1024
COMMAND_TIMEOUT_SECONDS = 60
CI_WORKFLOW_PATH = ".github/workflows/ci.yml"


class CiRunProofError(RuntimeError):
    """GitHub API evidence does not prove the required CI run identity."""


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CiRunProofError(f"GitHub API JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def parse_json(payload: bytes, description: str) -> dict:
    if len(payload) > MAX_API_BYTES:
        raise CiRunProofError(f"{description} exceeds its size limit")
    try:
        value = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CiRunProofError(f"{description} is not valid UTF-8 JSON: {error}") from error
    if not isinstance(value, dict):
        raise CiRunProofError(f"{description} must be a JSON object")
    return value


CommandRunner = Callable[[list[str]], bytes]


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
        raise CiRunProofError(f"command output exceeds its size limit: {command[0]}")
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise CiRunProofError(f"command failed ({command[0]}): {message}")
    return result.stdout


def _positive_integer(value, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CiRunProofError(f"{field} must be a positive integer")
    return value


def require_workflow_identity(workflow: dict) -> int:
    workflow_id = _positive_integer(workflow.get("id"), "CI workflow id")
    if workflow.get("path") != CI_WORKFLOW_PATH:
        raise CiRunProofError(f"CI workflow path must be exactly {CI_WORKFLOW_PATH}")
    if workflow.get("state") != "active":
        raise CiRunProofError("CI workflow must be active")
    return workflow_id


def _run_matches(run: object, repository: str, commit: str, workflow_id: int) -> bool:
    if not isinstance(run, dict):
        return False
    repository_data = run.get("repository")
    head_repository = run.get("head_repository")
    head_commit = run.get("head_commit")
    if not all(isinstance(value, dict) for value in (repository_data, head_repository, head_commit)):
        return False
    return (
        isinstance(run.get("id"), int)
        and not isinstance(run.get("id"), bool)
        and run["id"] > 0
        and isinstance(run.get("run_attempt"), int)
        and not isinstance(run.get("run_attempt"), bool)
        and run["run_attempt"] > 0
        and run.get("workflow_id") == workflow_id
        and run.get("path") == CI_WORKFLOW_PATH
        and run.get("event") == "push"
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and run.get("head_branch") == "main"
        and run.get("head_sha") == commit
        and head_commit.get("id") == commit
        and repository_data.get("full_name") == repository
        and head_repository.get("full_name") == repository
    )


def require_exact_successful_run(
    response: dict, repository: str, commit: str, workflow_id: int
) -> int:
    runs = response.get("workflow_runs")
    total_count = response.get("total_count")
    if (
        isinstance(total_count, bool)
        or not isinstance(total_count, int)
        or total_count != 1
        or not isinstance(runs, list)
        or len(runs) != 1
    ):
        raise CiRunProofError("CI workflow run response must contain exactly one run")
    matches = [run for run in runs if _run_matches(run, repository, commit, workflow_id)]
    if len(matches) != 1:
        raise CiRunProofError(
            "GitHub API did not prove a successful main push CI run for the exact trusted commit and workflow identity"
        )
    return _positive_integer(matches[0].get("id"), "authoritative CI run id")


def verify(repository: str, commit: str, runner: CommandRunner = run_command) -> int:
    if not repository or repository.count("/") != 1:
        raise CiRunProofError("GITHUB_REPOSITORY must identify one owner/repository")
    commit = require_commit_sha(commit)
    workflow = parse_json(
        runner(
            [
                "gh",
                "api",
                "--method",
                "GET",
                f"repos/{repository}/actions/workflows/ci.yml",
            ]
        ),
        "CI workflow response",
    )
    workflow_id = require_workflow_identity(workflow)
    response = parse_json(
        runner(
            [
                "gh",
                "api",
                "--method",
                "GET",
                f"repos/{repository}/actions/workflows/{workflow_id}/runs",
                "-f",
                "branch=main",
                "-f",
                "event=push",
                "-f",
                f"head_sha={commit}",
                "-f",
                "status=success",
                "-f",
                "per_page=100",
            ]
        ),
        "CI workflow runs response",
    )
    return require_exact_successful_run(response, repository, commit, workflow_id)


def _persist_run_id(run_id: int) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8", newline="\n") as output:
            output.write(f"release_ci_run_id={run_id}\n")
    environment_path = os.environ.get("GITHUB_ENV")
    if environment_path:
        with open(environment_path, "a", encoding="utf-8", newline="\n") as output:
            output.write(f"PROCWRIGHT_RELEASE_CI_RUN_ID={run_id}\n")


def main() -> int:
    try:
        run_id = verify(
            os.environ.get("GITHUB_REPOSITORY", ""),
            os.environ.get("PROCWRIGHT_RELEASE_COMMIT", ""),
        )
        _persist_run_id(run_id)
    except (CiRunProofError, ReleaseContractError, OSError, subprocess.SubprocessError) as error:
        print(f"Release CI proof failed: {error}", file=sys.stderr)
        return 1
    print(
        "Verified exact successful CI workflow identity for the current trusted main "
        f"commit at authoritative run {run_id}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
