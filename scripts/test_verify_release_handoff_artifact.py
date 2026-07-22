import json
import sys
import unittest
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import verify_release_handoff_artifact as verifier


class ReleaseHandoffArtifactTest(unittest.TestCase):
    REPOSITORY = "Ulviar/Procwright"
    RUN_ID = 98765
    ARTIFACT_ID = 12345
    VERSION = "1.2.3"
    COMMIT = "0123456789abcdef0123456789abcdef01234567"
    DIGEST = "a" * 64

    def test_accepts_exact_current_run_artifact_identity(self) -> None:
        verifier.validate_artifact(
            self._artifact(),
            self.REPOSITORY,
            self.RUN_ID,
            self.ARTIFACT_ID,
            self._name(),
            self.DIGEST,
            self.COMMIT,
        )

    def test_rejects_every_mutable_or_cross_run_identity_field(self) -> None:
        mutations = {
            "id": 999,
            "name": "other-artifact",
            "expired": True,
            "digest": "sha256:" + "b" * 64,
            "size_in_bytes": 0,
            "archive_download_url": "https://example.invalid/artifact.zip",
            "workflow_run": {
                "id": self.RUN_ID + 1,
                "repository_id": 1,
                "head_repository_id": 1,
                "head_branch": "main",
                "head_sha": self.COMMIT,
            },
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                artifact = self._artifact()
                artifact[field] = value
                with self.assertRaises(verifier.HandoffArtifactError):
                    verifier.validate_artifact(
                        artifact,
                        self.REPOSITORY,
                        self.RUN_ID,
                        self.ARTIFACT_ID,
                        self._name(),
                        self.DIGEST,
                        self.COMMIT,
                    )

    def test_rejects_fork_branch_and_commit_mismatch(self) -> None:
        for field, value in (
            ("head_repository_id", 2),
            ("head_branch", "feature"),
            ("head_sha", "f" * 40),
        ):
            with self.subTest(field=field):
                artifact = self._artifact()
                artifact["workflow_run"][field] = value
                with self.assertRaises(verifier.HandoffArtifactError):
                    verifier.validate_artifact(
                        artifact,
                        self.REPOSITORY,
                        self.RUN_ID,
                        self.ARTIFACT_ID,
                        self._name(),
                        self.DIGEST,
                        self.COMMIT,
                    )

    def test_verification_requests_only_exact_artifact_id(self) -> None:
        commands = []

        def runner(command: list[str]) -> bytes:
            commands.append(command)
            return json.dumps(self._artifact()).encode()

        verifier.verify(
            self.REPOSITORY,
            str(self.RUN_ID),
            str(self.ARTIFACT_ID),
            self._name(),
            self.DIGEST,
            self.COMMIT,
            runner,
        )

        self.assertEqual(
            commands,
            [
                [
                    "gh",
                    "api",
                    "--method",
                    "GET",
                    f"repos/{self.REPOSITORY}/actions/artifacts/{self.ARTIFACT_ID}",
                ]
            ],
        )

    def _name(self) -> str:
        return f"procwright-{self.VERSION}-{self.COMMIT}-unsigned-release"

    def _artifact(self) -> dict:
        return {
            "id": self.ARTIFACT_ID,
            "name": self._name(),
            "expired": False,
            "digest": "sha256:" + self.DIGEST,
            "size_in_bytes": 1024,
            "archive_download_url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/artifacts/{self.ARTIFACT_ID}/zip",
            "workflow_run": {
                "id": self.RUN_ID,
                "repository_id": 1,
                "head_repository_id": 1,
                "head_branch": "main",
                "head_sha": self.COMMIT,
            },
        }


if __name__ == "__main__":
    unittest.main()
