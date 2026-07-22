import json
import sys
import unittest
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import verify_release_ci_run as proof


class ReleaseCiRunProofTest(unittest.TestCase):
    REPOSITORY = "Ulviar/Procwright"
    COMMIT = "0123456789abcdef0123456789abcdef01234567"
    WORKFLOW_ID = 12345

    def test_accepts_exact_active_workflow_and_successful_push_run(self) -> None:
        commands = []

        def runner(command: list[str]) -> bytes:
            commands.append(command)
            if command[-1].endswith("actions/workflows/ci.yml"):
                return self._workflow()
            return self._runs()

        run_id = proof.verify(self.REPOSITORY, self.COMMIT, runner)

        self.assertEqual(run_id, 777)
        self.assertEqual(len(commands), 2)
        self.assertIn(f"actions/workflows/{self.WORKFLOW_ID}/runs", commands[1][4])
        self.assertIn(f"head_sha={self.COMMIT}", commands[1])

    def test_rejects_untrusted_workflow_identity(self) -> None:
        hostile = (
            {"id": 0, "path": proof.CI_WORKFLOW_PATH, "state": "active"},
            {"id": self.WORKFLOW_ID, "path": ".github/workflows/other.yml", "state": "active"},
            {"id": self.WORKFLOW_ID, "path": proof.CI_WORKFLOW_PATH, "state": "disabled_manually"},
        )
        for workflow in hostile:
            with self.subTest(workflow=workflow):
                with self.assertRaises(proof.CiRunProofError):
                    proof.require_workflow_identity(workflow)

    def test_rejects_every_missing_or_mismatched_run_identity_field(self) -> None:
        baseline = self._run()
        mutations = {
            "workflow_id": 999,
            "path": ".github/workflows/other.yml",
            "event": "workflow_dispatch",
            "status": "in_progress",
            "conclusion": "failure",
            "head_branch": "feature",
            "head_sha": "f" * 40,
            "run_attempt": 0,
            "repository": {"full_name": "other/repository"},
            "head_repository": {"full_name": "fork/repository"},
            "head_commit": {"id": "f" * 40},
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                run = dict(baseline)
                run[field] = value
                with self.assertRaises(proof.CiRunProofError):
                    proof.require_exact_successful_run(
                        {"total_count": 1, "workflow_runs": [run]},
                        self.REPOSITORY,
                        self.COMMIT,
                        self.WORKFLOW_ID,
                    )

    def test_rejects_boolean_inconsistent_duplicate_or_ambiguous_run_counts(self) -> None:
        exact = self._run()
        hostile = (
            {"total_count": True, "workflow_runs": [exact]},
            {"total_count": 0, "workflow_runs": [exact]},
            {"total_count": 1, "workflow_runs": []},
            {"total_count": 2, "workflow_runs": [exact]},
            {"total_count": 2, "workflow_runs": [exact, dict(exact)]},
            {"total_count": 1, "workflow_runs": [exact, dict(exact)]},
        )
        for response in hostile:
            with self.subTest(response=response):
                with self.assertRaisesRegex(proof.CiRunProofError, "exactly one run"):
                    proof.require_exact_successful_run(
                        response,
                        self.REPOSITORY,
                        self.COMMIT,
                        self.WORKFLOW_ID,
                    )

    def test_persists_only_the_authoritative_run_id(self) -> None:
        import os
        import tempfile
        from unittest import mock

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            environment = Path(directory) / "environment"
            with mock.patch.dict(
                os.environ,
                {"GITHUB_OUTPUT": str(output), "GITHUB_ENV": str(environment)},
                clear=False,
            ):
                proof._persist_run_id(777)

            self.assertEqual(output.read_text(), "release_ci_run_id=777\n")
            self.assertEqual(
                environment.read_text(), "PROCWRIGHT_RELEASE_CI_RUN_ID=777\n"
            )

    def test_rejects_malformed_oversized_or_duplicate_json(self) -> None:
        for payload in (
            b"[]",
            b"not-json",
            b'\xff',
            b'{"id":1,"id":1}',
            b"{" + b" " * proof.MAX_API_BYTES + b"}",
        ):
            with self.subTest(payload=payload[:20]):
                with self.assertRaises(proof.CiRunProofError):
                    proof.parse_json(payload, "test response")

    def _workflow(self) -> bytes:
        return json.dumps(
            {"id": self.WORKFLOW_ID, "path": proof.CI_WORKFLOW_PATH, "state": "active"}
        ).encode()

    def _runs(self) -> bytes:
        return json.dumps({"total_count": 1, "workflow_runs": [self._run()]}).encode()

    def _run(self) -> dict:
        return {
            "id": 777,
            "run_attempt": 1,
            "workflow_id": self.WORKFLOW_ID,
            "path": proof.CI_WORKFLOW_PATH,
            "event": "push",
            "status": "completed",
            "conclusion": "success",
            "head_branch": "main",
            "head_sha": self.COMMIT,
            "head_commit": {"id": self.COMMIT},
            "repository": {"full_name": self.REPOSITORY},
            "head_repository": {"full_name": self.REPOSITORY},
        }


if __name__ == "__main__":
    unittest.main()
