import copy
import hashlib
import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPTS_DIRECTORY.parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import staging_release_artifact as artifact
import verify_staging_evidence as evidence
from maven_release_test_fixtures import COMMIT, VERSION, zip_payload


REPOSITORY = "Ulviar/Procwright"
RUN_ID = 12345
ARTIFACT_ID = 67890
DIGEST = "a" * 64


class StagingReleaseArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)

    def write_json(self, name: str, value: dict) -> Path:
        path = self.root / name
        path.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
        path.chmod(0o600)
        return path

    @property
    def artifact_name(self) -> str:
        return f"procwright-{VERSION}-{COMMIT}-central-bundle"

    def workflow_response(self) -> dict:
        return {
            "total_count": 1,
            "workflow_runs": [
                {
                    "id": RUN_ID,
                    "run_attempt": 1,
                    "path": artifact.WORKFLOW_PATH,
                    "event": "workflow_dispatch",
                    "status": "completed",
                    "conclusion": "success",
                    "head_branch": "main",
                    "head_sha": COMMIT,
                    "head_commit": {"id": COMMIT},
                    "repository": {"full_name": REPOSITORY},
                    "head_repository": {"full_name": REPOSITORY},
                }
            ],
        }

    def artifact_response(self) -> dict:
        return {
            "total_count": 2,
            "artifacts": [
                {
                    "id": 111,
                    "name": f"procwright-{VERSION}-{COMMIT}-unsigned-release",
                    "expired": False,
                },
                {
                    "id": ARTIFACT_ID,
                    "name": self.artifact_name,
                    "expired": False,
                    "digest": f"sha256:{DIGEST}",
                    "size_in_bytes": 4096,
                    "archive_download_url": (
                        f"https://api.github.com/repos/{REPOSITORY}/actions/"
                        f"artifacts/{ARTIFACT_ID}/zip"
                    ),
                    "workflow_run": {
                        "id": RUN_ID,
                        "repository_id": 7,
                        "head_repository_id": 7,
                        "head_branch": "main",
                        "head_sha": COMMIT,
                    },
                },
            ],
        }

    def test_selects_only_exact_commit_run_and_artifact_service_identity(self) -> None:
        run = artifact.select_workflow_run(
            self.write_json("runs.json", self.workflow_response()), REPOSITORY, COMMIT
        )
        self.assertEqual(run, str(RUN_ID))
        selected = artifact.select_artifact(
            self.write_json("artifacts.json", self.artifact_response()),
            REPOSITORY,
            VERSION,
            COMMIT,
            run,
        )
        self.assertEqual(selected, (str(ARTIFACT_ID), self.artifact_name, DIGEST))

    def test_run_selection_fails_closed_on_ambiguity_and_identity_drift(self) -> None:
        mutations = []
        for field, value in (
            ("path", ".github/workflows/other.yml"),
            ("event", "push"),
            ("conclusion", "failure"),
            ("head_branch", "feature"),
            ("head_sha", "f" * 40),
        ):
            response = self.workflow_response()
            response["workflow_runs"][0][field] = value
            mutations.append(response)
        ambiguous = self.workflow_response()
        ambiguous["workflow_runs"].append(copy.deepcopy(ambiguous["workflow_runs"][0]))
        ambiguous["total_count"] = 2
        mutations.append(ambiguous)
        for index, response in enumerate(mutations):
            with self.subTest(mutation=index):
                with self.assertRaises(artifact.StagingArtifactError):
                    artifact.select_workflow_run(
                        self.write_json(f"run-{index}.json", response),
                        REPOSITORY,
                        COMMIT,
                    )

    def test_artifact_selection_rejects_missing_duplicate_or_mutable_identity(self) -> None:
        mutations = []
        for field, value in (
            ("id", 0),
            ("expired", True),
            ("digest", None),
            ("archive_download_url", "https://example.invalid/archive"),
        ):
            response = self.artifact_response()
            response["artifacts"][1][field] = value
            mutations.append(response)
        wrong_run = self.artifact_response()
        wrong_run["artifacts"][1]["workflow_run"]["id"] = RUN_ID + 1
        mutations.append(wrong_run)
        duplicate = self.artifact_response()
        duplicate["artifacts"].append(copy.deepcopy(duplicate["artifacts"][1]))
        duplicate["total_count"] = 3
        mutations.append(duplicate)
        for index, response in enumerate(mutations):
            with self.subTest(mutation=index):
                with self.assertRaises(artifact.StagingArtifactError):
                    artifact.select_artifact(
                        self.write_json(f"artifact-{index}.json", response),
                        REPOSITORY,
                        VERSION,
                        COMMIT,
                        str(RUN_ID),
                    )

    def test_extracts_only_exact_schema_after_raw_service_digest_verification(self) -> None:
        names = evidence.evidence_names(VERSION)
        archive = self.root / "artifact.zip"
        archive.write_bytes(
            zip_payload(
                [
                    (name, f"payload:{role}".encode(), stat.S_IFREG | 0o644)
                    for role, name in names.items()
                ]
            )
        )
        archive.chmod(0o600)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        destination = self.root / "output"
        artifact.extract_artifact(archive, destination, VERSION, digest)
        self.assertEqual({path.name for path in destination.iterdir()}, set(names.values()))
        self.assertTrue(
            all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in destination.iterdir())
        )

    def test_extraction_rejects_digest_drift_and_extra_schema_file(self) -> None:
        names = evidence.evidence_names(VERSION)
        exact_entries = [
            (name, f"payload:{role}".encode(), stat.S_IFREG | 0o644)
            for role, name in names.items()
        ]
        for label, entries, digest in (
            ("digest", exact_entries, "0" * 64),
            (
                "extra",
                exact_entries + [("private-signing-key.asc", b"secret", stat.S_IFREG | 0o600)],
                None,
            ),
        ):
            with self.subTest(case=label):
                archive = self.root / f"{label}.zip"
                archive.write_bytes(zip_payload(entries))
                archive.chmod(0o600)
                expected = digest or hashlib.sha256(archive.read_bytes()).hexdigest()
                with self.assertRaises(
                    (artifact.StagingArtifactError, artifact.EvidenceError)
                ):
                    artifact.extract_artifact(
                        archive, self.root / f"output-{label}", VERSION, expected
                    )

    def test_workflow_producer_and_downloader_share_the_closed_eight_file_contract(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github/workflows/publish-maven-central.yml").read_text(
            encoding="utf-8"
        )
        preserve = workflow.split("- name: Preserve validated staged bundle", 1)[1]
        path_block = preserve.split("path: |", 1)[1].split("if-no-files-found:", 1)[0]
        uploaded = [line.strip() for line in path_block.splitlines() if line.strip()]
        self.assertEqual(len(uploaded), 8)
        for filename in evidence.evidence_names(VERSION).values():
            templated = filename.replace(VERSION, "${{ inputs.release-version }}")
            self.assertIn(f"build/maven-central/{templated}", uploaded)
        self.assertNotIn("PRIVATE KEY", path_block)

        downloader = (
            REPOSITORY_ROOT / "scripts/release/download_staging_evidence.sh"
        ).read_text(encoding="utf-8")
        for required in (
            'head_sha="${PROCWRIGHT_RELEASE_COMMIT}"',
            "actions/artifacts/${artifact_id}/zip",
            '--digest "${artifact_digest}"',
            '--artifact-id "${artifact_id}"',
            '--artifact-name "${artifact_name}"',
            '--artifact-digest "${artifact_digest}"',
            '--github-env "${GITHUB_ENV}"',
        ):
            self.assertIn(required, downloader)
        self.assertNotIn("gh run download", downloader)

        downstream = (
            REPOSITORY_ROOT / "scripts/release/wait_for_central_publication.sh"
        ).read_text(encoding="utf-8")
        for carried_identity in (
            "PROCWRIGHT_STAGED_BUNDLE_SHA256",
            "PROCWRIGHT_STAGING_RUN_ID",
            "PROCWRIGHT_STAGING_ARTIFACT_ID",
            "PROCWRIGHT_STAGING_ARTIFACT_NAME",
            "PROCWRIGHT_STAGING_ARTIFACT_DIGEST",
            '--expected-bundle-sha256 "${PROCWRIGHT_STAGED_BUNDLE_SHA256}"',
        ):
            self.assertIn(carried_identity, downstream)


if __name__ == "__main__":
    unittest.main()
