import hashlib
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import maven_central_portal as portal
import maven_release_evidence as evidence
import verify_maven_central_staged_bundle as staged
import verify_staging_evidence as verifier
from maven_release_test_fixtures import (
    COMMIT,
    DEPLOYMENT_ID,
    VERSION,
    closed_release_payloads,
    write_bundle,
)


REPOSITORY = "Ulviar/Procwright"
STAGE_RUN_ID = "12345"
ARTIFACT_ID = "67890"
ARTIFACT_DIGEST = "d" * 64


class StagingEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.directory = self.root / "evidence"
        self.directory.mkdir(mode=0o700)
        self.names = verifier.evidence_names(VERSION)
        self.bundle = self.directory / self.names["bundle"]
        write_bundle(self.bundle, closed_release_payloads())
        manifest = staged.write_manifest(
            self.bundle, self.directory / self.names["manifest"], VERSION
        )
        digest = manifest["bundle"]["sha256"]
        self.digest = digest
        self.write(self.names["checksum"], f"{digest}  {self.names['bundle']}\n".encode())
        identity = portal.PortalIdentity(VERSION, COMMIT)
        deployment = portal.deployment_evidence(
            identity,
            DEPLOYMENT_ID,
            "VALIDATED",
            digest,
            identity.purls,
        )
        self.write(
            self.names["deployment"], portal.deployment_evidence_bytes(deployment)
        )
        provenance = (
            f"version={VERSION}\n"
            f"commit={COMMIT}\n"
            f"workflow_ref={REPOSITORY}/.github/workflows/publish-maven-central.yml@refs/heads/main\n"
            f"workflow_sha={COMMIT}\n"
            f"unsigned_handoff_sha256={'b' * 64}\n"
            f"staged_bundle_sha256={digest}\n"
            "runner_os=ubuntu24\n"
            "runner_image_version=20260720.1.0\n"
            "Python 3.13.14\n"
            "gpg (GnuPG) 2.4.7\n"
        ).encode()
        self.write(self.names["provenance"], provenance)
        self.write(self.names["signing_evidence"], b"{}\n")
        self.write(
            self.names["public_key"],
            b"-----BEGIN PGP PUBLIC KEY BLOCK-----\nfixture\n"
            b"-----END PGP PUBLIC KEY BLOCK-----\n",
        )
        self.write(self.names["verified_handoff"], b"{}\n")
        patcher = mock.patch.object(
            verifier,
            "verify_persisted_signing_evidence",
            return_value=(self.digest, "A" * 40),
        )
        self.crypto_verifier = patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, name: str, payload: bytes) -> Path:
        path = self.directory / name
        path.write_bytes(payload)
        path.chmod(0o600)
        return path

    @property
    def artifact_name(self) -> str:
        return f"procwright-{VERSION}-{COMMIT}-central-bundle"

    def verify(self) -> tuple[str, str]:
        return verifier.verify_staging_evidence(
            self.directory,
            VERSION,
            COMMIT,
            REPOSITORY,
            STAGE_RUN_ID,
            ARTIFACT_ID,
            self.artifact_name,
            ARTIFACT_DIGEST,
        )

    def test_accepts_only_the_exact_bound_evidence_set(self) -> None:
        deployment_id, bundle_sha256 = self.verify()
        self.assertEqual(deployment_id, DEPLOYMENT_ID)
        self.assertEqual(bundle_sha256, self.digest)
        self.crypto_verifier.assert_called_once()
        self.assertEqual(
            {path.name for path in self.directory.iterdir()}, set(self.names.values())
        )
        for path in self.directory.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_rejects_extra_missing_and_wrong_checksum_filename(self) -> None:
        extra = self.directory / "evil.exe"
        extra.write_bytes(b"MZ")
        with self.assertRaisesRegex(verifier.StagingEvidenceError, "file set"):
            self.verify()
        extra.unlink()

        public_key = self.directory / self.names["public_key"]
        public_key_payload = public_key.read_bytes()
        public_key.unlink()
        with self.assertRaisesRegex(verifier.StagingEvidenceError, "file set"):
            self.verify()
        self.write(self.names["public_key"], public_key_payload)

        checksum = self.directory / self.names["checksum"]
        checksum.write_text(f"{self.digest}  /dev/zero\n", encoding="ascii")
        checksum.chmod(0o600)
        with self.assertRaisesRegex(verifier.StagingEvidenceError, "checksum sidecar"):
            self.verify()

    def test_rejects_symlink_hardlink_and_special_evidence_files(self) -> None:
        checksum = self.directory / self.names["checksum"]
        checksum.unlink()
        try:
            checksum.symlink_to("/dev/zero")
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"symlinks unavailable: {error}")
        with self.assertRaisesRegex(verifier.StagingEvidenceError, "non-regular"):
            self.verify()
        checksum.unlink()
        self.write(
            self.names["checksum"],
            f"{self.digest}  {self.names['bundle']}\n".encode(),
        )

        provenance = self.directory / self.names["provenance"]
        provenance.unlink()
        try:
            os.link(self.directory / self.names["manifest"], provenance)
        except OSError as error:
            self.skipTest(f"hardlinks unavailable: {error}")
        with self.assertRaisesRegex(evidence.EvidenceError, "filesystem link"):
            self.verify()

    def test_rejects_special_fifo_without_opening_it(self) -> None:
        if not hasattr(os, "mkfifo"):
            self.skipTest("FIFO unavailable")
        deployment = self.directory / self.names["deployment"]
        deployment.unlink()
        os.mkfifo(deployment)
        with self.assertRaisesRegex(verifier.StagingEvidenceError, "non-regular"):
            self.verify()

    def test_rejects_wrong_deployment_identity(self) -> None:
        deployment_path = self.directory / self.names["deployment"]
        deployment = json.loads(deployment_path.read_bytes())
        deployment["publishingType"] = "AUTOMATIC"
        self.write(
            self.names["deployment"],
            portal.deployment_evidence_bytes(deployment),
        )
        with self.assertRaises(portal.PortalError):
            self.verify()

    def test_rejects_provenance_bound_to_another_staged_bundle(self) -> None:
        provenance_path = self.directory / self.names["provenance"]
        provenance = provenance_path.read_text(encoding="ascii").replace(
            f"staged_bundle_sha256={self.digest}",
            f"staged_bundle_sha256={'c' * 64}",
        )
        self.write(self.names["provenance"], provenance.encode("ascii"))

        with self.assertRaisesRegex(
            verifier.StagingEvidenceError, "provenance identity"
        ):
            self.verify()

    def test_rejects_missing_or_wrong_stage_artifact_identity(self) -> None:
        cases = (
            ("", ARTIFACT_ID, self.artifact_name, ARTIFACT_DIGEST),
            (STAGE_RUN_ID, "0", self.artifact_name, ARTIFACT_DIGEST),
            (STAGE_RUN_ID, ARTIFACT_ID, "other", ARTIFACT_DIGEST),
            (STAGE_RUN_ID, ARTIFACT_ID, self.artifact_name, "A" * 64),
        )
        for run_id, artifact_id, artifact_name, digest in cases:
            with self.subTest(identity=(run_id, artifact_id, artifact_name, digest)):
                with self.assertRaises(verifier.StagingEvidenceError):
                    verifier.verify_staging_evidence(
                        self.directory,
                        VERSION,
                        COMMIT,
                        REPOSITORY,
                        run_id,
                        artifact_id,
                        artifact_name,
                        digest,
                    )

    def test_rejects_cryptographic_evidence_for_another_bundle(self) -> None:
        self.crypto_verifier.return_value = ("e" * 64, "A" * 40)
        with self.assertRaisesRegex(
            verifier.StagingEvidenceError, "another staged bundle"
        ):
            self.verify()

    def test_cli_writes_outputs_only_after_complete_validation(self) -> None:
        output = self.root / "github-output"
        output.touch(mode=0o600)
        environment = self.root / "github-environment"
        environment.touch(mode=0o600)
        result = verifier.main(
            [
                "--directory",
                str(self.directory),
                "--version",
                VERSION,
                "--commit",
                COMMIT,
                "--repository",
                REPOSITORY,
                "--stage-run-id",
                STAGE_RUN_ID,
                "--artifact-id",
                ARTIFACT_ID,
                "--artifact-name",
                self.artifact_name,
                "--artifact-digest",
                ARTIFACT_DIGEST,
                "--github-output",
                str(output),
                "--github-env",
                str(environment),
            ]
        )
        self.assertEqual(result, 0)
        self.assertEqual(
            output.read_text(),
            f"deployment_id={DEPLOYMENT_ID}\n"
            f"bundle_sha256={self.digest}\n"
            f"stage_run_id={STAGE_RUN_ID}\n"
            f"stage_artifact_id={ARTIFACT_ID}\n"
            f"stage_artifact_name={self.artifact_name}\n"
            f"stage_artifact_digest={ARTIFACT_DIGEST}\n",
        )
        self.assertEqual(
            environment.read_text(),
            f"PROCWRIGHT_STAGED_BUNDLE_SHA256={self.digest}\n"
            f"PROCWRIGHT_STAGING_RUN_ID={STAGE_RUN_ID}\n"
            f"PROCWRIGHT_STAGING_ARTIFACT_ID={ARTIFACT_ID}\n"
            f"PROCWRIGHT_STAGING_ARTIFACT_NAME={self.artifact_name}\n"
            f"PROCWRIGHT_STAGING_ARTIFACT_DIGEST={ARTIFACT_DIGEST}\n",
        )


if __name__ == "__main__":
    unittest.main()
