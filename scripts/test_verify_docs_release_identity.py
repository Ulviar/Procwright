import copy
import hashlib
import io
import json
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import verify_docs_release_identity as identity


class DocsReleaseIdentityTest(unittest.TestCase):
    COMMIT = "0123456789abcdef0123456789abcdef01234567"
    WORKFLOW_SHA = "89abcdef0123456789abcdef0123456789abcdef"
    REPOSITORY = "Ulviar/Procwright"
    VERSION = "1.2.3"
    DEPLOYMENT_ID = "123e4567-e89b-12d3-a456-426614174000"
    BUNDLE_SHA256 = "b" * 64
    VERIFICATION_METADATA = b"<verification-metadata>exact</verification-metadata>\n"

    def setUp(self) -> None:
        self.commands = []
        self.downloads = []
        self.stage_archive = b"exact bounded staging archive bytes"
        self.consumer_proof = identity._canonical_consumer_proof(
            self.VERSION,
            self.COMMIT,
            self.BUNDLE_SHA256,
            hashlib.sha256(self.VERIFICATION_METADATA).hexdigest(),
            self.DEPLOYMENT_ID,
            101,
        )
        self.consumer_archive = self.zip_file("provenance.txt", self.consumer_proof)
        self.responses = self.complete_api_responses()

    @staticmethod
    def zip_file(name: str, payload: bytes, mode: int = stat.S_IFREG | 0o644) -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = mode << 16
            archive.writestr(info, payload)
        return output.getvalue()

    def workflow(self, workflow_id: int, path: str) -> dict:
        return {
            "id": workflow_id,
            "node_id": f"workflow-{workflow_id}",
            "name": "producer",
            "path": path,
            "state": "active",
            "created_at": "2026-01-01T00:00:00Z",
            "updated_at": "2026-01-01T00:00:00Z",
            "url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/workflows/{workflow_id}",
            "html_url": f"https://github.com/{self.REPOSITORY}/actions/workflows/{path.rsplit('/', 1)[-1]}",
            "badge_url": "https://example.invalid/badge.svg",
        }

    def run_response(self, run_id: int, workflow_id: int, path: str) -> dict:
        repository = {"id": 9001, "full_name": self.REPOSITORY}
        run = {
            "id": run_id,
            "node_id": f"run-{run_id}",
            "name": "producer",
            "path": path,
            "display_title": "release producer",
            "run_number": 7,
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": "success",
            "workflow_id": workflow_id,
            "head_branch": "main",
            "head_sha": self.COMMIT,
            "run_attempt": 1,
            "repository": repository,
            "head_repository": repository,
            "head_commit": {"id": self.COMMIT, "message": "release"},
            "url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/runs/{run_id}",
            "artifacts_url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/runs/{run_id}/artifacts",
            "created_at": "2026-01-01T00:00:00Z",
            "updated_at": "2026-01-01T00:00:01Z",
        }
        return {"total_count": 1, "workflow_runs": [run]}

    def artifact_response(
        self, artifact_id: int, run_id: int, name: str, payload: bytes
    ) -> dict:
        artifact = {
            "id": artifact_id,
            "node_id": f"artifact-{artifact_id}",
            "name": name,
            "size_in_bytes": len(payload),
            "url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/artifacts/{artifact_id}",
            "archive_download_url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/artifacts/{artifact_id}/zip",
            "expired": False,
            "created_at": "2026-01-01T00:00:01Z",
            "expires_at": "2026-04-01T00:00:01Z",
            "updated_at": "2026-01-01T00:00:01Z",
            "digest": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "workflow_run": {
                "id": run_id,
                "repository_id": 9001,
                "head_repository_id": 9001,
                "head_branch": "main",
                "head_sha": self.COMMIT,
            },
        }
        return {"total_count": 1, "artifacts": [artifact]}

    def complete_api_responses(self) -> dict[str, dict]:
        stage_name = f"procwright-{self.VERSION}-{self.COMMIT}-central-bundle"
        consumer_name = (
            f"procwright-{self.VERSION}-{self.COMMIT}-central-consumer-smoke"
        )
        return {
            "workflow:publish-maven-central.yml": self.workflow(
                11, identity.STAGING_WORKFLOW_PATH
            ),
            "runs:11": self.run_response(101, 11, identity.STAGING_WORKFLOW_PATH),
            "artifacts:101": self.artifact_response(
                201, 101, stage_name, self.stage_archive
            ),
            "workflow:ci.yml": self.workflow(12, identity.CONSUMER_WORKFLOW_PATH),
            "runs:12": self.run_response(102, 12, identity.CONSUMER_WORKFLOW_PATH),
            "artifacts:102": self.artifact_response(
                202, 102, consumer_name, self.consumer_archive
            ),
        }

    def environment(self, event_name: str, release_ref: str) -> dict[str, str]:
        return {
            "GITHUB_REPOSITORY": self.REPOSITORY,
            "PROCWRIGHT_EVENT_NAME": event_name,
            "PROCWRIGHT_RELEASE_COMMIT": self.COMMIT
            if event_name == "workflow_dispatch"
            else "",
            "PROCWRIGHT_RELEASE_REF": release_ref,
            "PROCWRIGHT_TRUSTED_ROOT": ".procwright-trusted",
            "PROCWRIGHT_WORKFLOW_SHA": self.WORKFLOW_SHA,
        }

    def runner(self, command: list[str]) -> bytes:
        self.commands.append(command)
        joined = " ".join(command)
        if command[:4] == ["git", "-C", ".procwright-trusted", "rev-parse"]:
            if command[-1] == "HEAD^{commit}":
                return f"{self.WORKFLOW_SHA}\n".encode()
            return f"{self.COMMIT}\n".encode()
        if command[:4] == ["git", "-C", ".procwright-trusted", "show"]:
            return self.VERIFICATION_METADATA
        if "/releases/tags/v1.2.3" in joined:
            return json.dumps(
                {
                    "id": 71,
                    "tag_name": "v1.2.3",
                    "target_commitish": "main",
                    "draft": False,
                    "prerelease": False,
                    "immutable": True,
                }
            ).encode()
        if "/actions/workflows/11/runs" in joined:
            return json.dumps(self.responses["runs:11"]).encode()
        if "/actions/workflows/12/runs" in joined:
            return json.dumps(self.responses["runs:12"]).encode()
        if "/actions/workflows/" in joined:
            workflow = command[4].rsplit("/", 1)[-1]
            return json.dumps(self.responses[f"workflow:{workflow}"]).encode()
        if "/actions/runs/101/artifacts" in joined:
            return json.dumps(self.responses["artifacts:101"]).encode()
        if "/actions/runs/102/artifacts" in joined:
            return json.dumps(self.responses["artifacts:102"]).encode()
        raise AssertionError(f"unexpected command: {command}")

    def downloader(self, repository: str, artifact_id: int, maximum_size: int) -> bytes:
        self.downloads.append((repository, artifact_id, maximum_size))
        return {201: self.stage_archive, 202: self.consumer_archive}[artifact_id]

    def staging_validator(self, *arguments) -> tuple[str, str]:
        self.assertEqual(arguments[0], self.stage_archive)
        self.assertEqual(
            arguments[1:],
            (
                self.VERSION,
                self.COMMIT,
                self.REPOSITORY,
                101,
                201,
                f"procwright-{self.VERSION}-{self.COMMIT}-central-bundle",
                hashlib.sha256(self.stage_archive).hexdigest(),
            ),
        )
        return self.DEPLOYMENT_ID, self.BUNDLE_SHA256

    def verify_release_event(self) -> None:
        with mock.patch.dict(
            identity.os.environ,
            self.environment("release", "v1.2.3"),
            clear=True,
        ):
            identity.verify_from_environment(
                self.runner, self.downloader, self.staging_validator
            )

    def test_requires_event_specific_release_reference(self) -> None:
        self.assertEqual(identity.release_version("release", "v1.2.3"), "1.2.3")
        self.assertEqual(
            identity.release_version("workflow_dispatch", "1.2.3"), "1.2.3"
        )
        for event_name, release_ref in (
            ("release", "1.2.3"),
            ("release", "vv1.2.3"),
            ("workflow_dispatch", "v1.2.3"),
            ("workflow_dispatch", "01.2.3"),
        ):
            with self.subTest(event_name=event_name, release_ref=release_ref):
                with self.assertRaises(
                    (identity.ReleaseIdentityError, identity.ReleaseContractError)
                ):
                    identity.release_version(event_name, release_ref)

    def test_requires_exact_immutable_non_draft_release_and_strict_json(self) -> None:
        identity.validate_release_fields(
            {"tag_name": "v1.2.3", "draft": False, "immutable": True},
            "v1.2.3",
        )
        for payload in (
            {"tag_name": "v9.9.9", "draft": False, "immutable": True},
            {"tag_name": "v1.2.3", "draft": True, "immutable": True},
            {"tag_name": "v1.2.3", "draft": False, "immutable": False},
            {"tag_name": "v1.2.3", "draft": False},
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(identity.ReleaseIdentityError):
                    identity.validate_release_fields(payload, "v1.2.3")
        with self.assertRaises(identity.ReleaseIdentityError):
            identity.parse_release_json(
                b'{"tag_name":"v1.2.3","immutable":true,"immutable":true}'
            )
        for payload in (b"[]", b"not json", b"\xff"):
            with self.subTest(payload=payload):
                with self.assertRaises(identity.ReleaseIdentityError):
                    identity.parse_release_json(payload)

    def test_release_event_binds_complete_api_identity_id_digest_and_content(self) -> None:
        self.verify_release_event()
        self.assertEqual(
            [(repository, artifact_id) for repository, artifact_id, _ in self.downloads],
            [(self.REPOSITORY, 201), (self.REPOSITORY, 202)],
        )
        run_commands = [
            command
            for command in self.commands
            if any(part.endswith("/runs") for part in command)
        ]
        self.assertEqual(len(run_commands), 2)
        for command in run_commands:
            self.assertIn(f"head_sha={self.COMMIT}", command)
            self.assertIn("branch=main", command)
            self.assertIn("event=workflow_dispatch", command)
            self.assertIn("status=success", command)
            self.assertNotIn("--jq", command)

    def test_recovery_skips_expiring_producer_evidence_and_writes_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "github-output")
            environment = self.environment("workflow_dispatch", "1.2.3")
            environment["GITHUB_OUTPUT"] = str(output)
            with mock.patch.dict(identity.os.environ, environment, clear=True):
                identity.verify_from_environment(
                    self.runner, self.downloader, self.staging_validator
                )
            self.assertEqual(
                output.read_text(encoding="utf-8"),
                f"release_commit={self.COMMIT}\nrelease_version=1.2.3\n",
            )
        self.assertFalse(any("/actions/" in " ".join(command) for command in self.commands))
        self.assertEqual(self.downloads, [])

    def test_rejects_zero_multiple_historical_or_weakened_producer_runs(self) -> None:
        hostile = []
        zero = copy.deepcopy(self.responses["runs:11"])
        zero.update(total_count=0, workflow_runs=[])
        hostile.append(zero)
        multiple = copy.deepcopy(self.responses["runs:11"])
        multiple["total_count"] = 2
        multiple["workflow_runs"].append(copy.deepcopy(multiple["workflow_runs"][0]))
        hostile.append(multiple)
        for field, value in (
            ("head_sha", "f" * 40),
            ("path", identity.CONSUMER_WORKFLOW_PATH),
            ("event", "push"),
            ("head_branch", "release"),
            ("status", "in_progress"),
            ("conclusion", "neutral"),
            ("workflow_id", 999),
        ):
            response = copy.deepcopy(self.responses["runs:11"])
            response["workflow_runs"][0][field] = value
            hostile.append(response)
        for response in hostile:
            with self.subTest(response=response):
                self.responses["runs:11"] = response
                with self.assertRaises(identity.ReleaseIdentityError):
                    self.verify_release_event()
        self.responses = self.complete_api_responses()
        self.responses["workflow:publish-maven-central.yml"]["state"] = "disabled_manually"
        with self.assertRaises(identity.ReleaseIdentityError):
            self.verify_release_event()

    def test_rejects_name_only_ambiguous_wrong_or_tampered_artifacts(self) -> None:
        hostile = []
        name_only = copy.deepcopy(self.responses["artifacts:101"])
        name_only["artifacts"][0].pop("digest")
        name_only["artifacts"][0].pop("workflow_run")
        hostile.append(name_only)
        multiple = copy.deepcopy(self.responses["artifacts:101"])
        multiple["total_count"] = 2
        multiple["artifacts"].append(copy.deepcopy(multiple["artifacts"][0]))
        hostile.append(multiple)
        for field, value in (("expired", True), ("name", "historical")):
            response = copy.deepcopy(self.responses["artifacts:101"])
            response["artifacts"][0][field] = value
            hostile.append(response)
        wrong_run = copy.deepcopy(self.responses["artifacts:101"])
        wrong_run["artifacts"][0]["workflow_run"]["id"] = 999
        hostile.append(wrong_run)
        for response in hostile:
            with self.subTest(response=response):
                self.responses["artifacts:101"] = response
                with self.assertRaises(identity.ReleaseIdentityError):
                    self.verify_release_event()

        self.responses = self.complete_api_responses()

        def tampered_downloader(repository: str, artifact_id: int, maximum_size: int) -> bytes:
            if artifact_id == 201:
                return self.stage_archive + b"tampered"
            return self.consumer_archive

        with mock.patch.dict(
            identity.os.environ,
            self.environment("release", "v1.2.3"),
            clear=True,
        ):
            with self.assertRaisesRegex(identity.ReleaseIdentityError, "service digest"):
                identity.verify_from_environment(
                    self.runner, tampered_downloader, self.staging_validator
                )

    def test_rejects_noncanonical_or_substituted_consumer_proof_content(self) -> None:
        for archive in (
            self.zip_file("provenance.txt", self.consumer_proof + b"extra=true\n"),
            self.zip_file("renamed.txt", self.consumer_proof),
            self.zip_file("provenance.txt", b""),
        ):
            with self.subTest(archive=archive[:20]):
                self.consumer_archive = archive
                self.responses = self.complete_api_responses()
                with self.assertRaises(identity.ReleaseIdentityError):
                    self.verify_release_event()


if __name__ == "__main__":
    unittest.main()
