import hashlib
import io
import json
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import central_release_artifact as artifact
import verify_maven_central_staged_bundle as staged
from maven_release_evidence import EvidenceError
from maven_release_test_fixtures import COMMIT, VERSION, closed_release_payloads, write_bundle


REPOSITORY = "Ulviar/Procwright"
RUN_ID = "12345"
ARTIFACT_ID = "67890"


def artifact_zip(payloads: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in sorted(payloads.items()):
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o400) << 16
            archive.writestr(info, payload)
    return output.getvalue()


class CentralReleaseArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.bundle = self.root / f"procwright-{VERSION}-maven-central-bundle.zip"
        self.manifest = self.root / "manifest.json"
        self.release_payloads = closed_release_payloads()
        write_bundle(self.bundle, self.release_payloads)
        staged.write_manifest(self.bundle, self.manifest, VERSION)
        self.flat_payloads = {
            Path(path).name: payload for path, payload in self.release_payloads.items()
        }
        self.name = artifact.artifact_name(VERSION, COMMIT)

    def write_directory(self, name: str = "central-bytes") -> Path:
        directory = self.root / name
        directory.mkdir(mode=0o700)
        for filename, payload in self.flat_payloads.items():
            path = directory / filename
            path.write_bytes(payload)
            path.chmod(0o600)
        return directory

    def write_archive(
        self, payloads: dict[str, bytes] | None = None, name: str = "artifact.zip"
    ) -> tuple[Path, str]:
        archive = self.root / name
        archive.write_bytes(artifact_zip(self.flat_payloads if payloads is None else payloads))
        archive.chmod(0o600)
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def metadata(self, **changes) -> dict:
        value = {
            "id": int(ARTIFACT_ID),
            "name": self.name,
            "expired": False,
            "size_in_bytes": 123456,
            "digest": "sha256:" + "a" * 64,
            "archive_download_url": (
                f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/"
                f"{ARTIFACT_ID}/zip"
            ),
            "workflow_run": {
                "id": int(RUN_ID),
                "repository_id": 11,
                "head_repository_id": 11,
                "head_branch": "main",
                "head_sha": COMMIT,
            },
        }
        value.update(changes)
        return value

    def write_metadata(self, value: dict, name: str = "metadata.json") -> Path:
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        path.chmod(0o600)
        return path

    def test_seals_only_the_exact_signed_90_file_directory(self) -> None:
        directory = self.write_directory()

        artifact.seal_directory(directory, self.bundle, self.manifest, VERSION)

        self.assertEqual(len(list(directory.iterdir())), 90)
        self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o500)
        self.assertTrue(
            all(stat.S_IMODE(path.stat().st_mode) == 0o400 for path in directory.iterdir())
        )

    def test_seal_rejects_extra_missing_and_mutated_files(self) -> None:
        for case in ("extra", "missing", "mutated"):
            with self.subTest(case=case):
                directory = self.write_directory(f"central-{case}")
                if case == "extra":
                    (directory / "extra.txt").write_bytes(b"extra")
                elif case == "missing":
                    next(iter(directory.iterdir())).unlink()
                else:
                    target = next(iter(directory.iterdir()))
                    target.write_bytes(target.read_bytes() + b"mutation")
                with self.assertRaises((artifact.CentralArtifactError, EvidenceError)):
                    artifact.seal_directory(
                        directory, self.bundle, self.manifest, VERSION
                    )

    def test_accepts_exact_artifact_metadata(self) -> None:
        artifact.verify_metadata(
            self.write_metadata(self.metadata()),
            REPOSITORY,
            RUN_ID,
            VERSION,
            COMMIT,
            ARTIFACT_ID,
            self.name,
            "a" * 64,
        )

    def test_rejects_wrong_artifact_id_name_digest_and_workflow(self) -> None:
        cases = (
            ("id", self.metadata(id=999), ARTIFACT_ID, self.name, "a" * 64),
            ("name", self.metadata(name="other"), ARTIFACT_ID, self.name, "a" * 64),
            (
                "digest",
                self.metadata(digest="sha256:" + "b" * 64),
                ARTIFACT_ID,
                self.name,
                "a" * 64,
            ),
            (
                "run",
                self.metadata(workflow_run={**self.metadata()["workflow_run"], "id": 7}),
                ARTIFACT_ID,
                self.name,
                "a" * 64,
            ),
        )
        for label, response, expected_id, expected_name, digest in cases:
            with self.subTest(case=label):
                with self.assertRaises(artifact.CentralArtifactError):
                    artifact.verify_metadata(
                        self.write_metadata(response, f"metadata-{label}.json"),
                        REPOSITORY,
                        RUN_ID,
                        VERSION,
                        COMMIT,
                        expected_id,
                        expected_name,
                        digest,
                    )

    def test_extracts_exact_service_bound_bytes_matching_signed_manifest(self) -> None:
        archive, digest = self.write_archive()
        destination = self.root / "downloaded"

        artifact.extract_and_verify(
            archive,
            destination,
            self.bundle,
            self.manifest,
            VERSION,
            digest,
        )

        self.assertEqual(
            {path.name: path.read_bytes() for path in destination.iterdir()},
            self.flat_payloads,
        )
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o500)

    def test_extract_rejects_wrong_digest_extra_missing_and_mutated_bytes(self) -> None:
        cases = []
        exact_archive, exact_digest = self.write_archive(name="wrong-digest.zip")
        cases.append(("digest", exact_archive, "0" * 64))
        for label in ("extra", "missing", "mutated"):
            payloads = dict(self.flat_payloads)
            if label == "extra":
                payloads["extra.txt"] = b"extra"
            elif label == "missing":
                payloads.pop(next(iter(payloads)))
            else:
                key = next(iter(payloads))
                payloads[key] += b"mutation"
            archive, digest = self.write_archive(payloads, f"{label}.zip")
            cases.append((label, archive, digest))
        for label, archive, digest in cases:
            with self.subTest(case=label):
                with self.assertRaises((artifact.CentralArtifactError, EvidenceError)):
                    artifact.extract_and_verify(
                        archive,
                        self.root / f"downloaded-{label}",
                        self.bundle,
                        self.manifest,
                        VERSION,
                        digest,
                    )
                self.assertFalse((self.root / f"downloaded-{label}").exists())


if __name__ == "__main__":
    unittest.main()
