import hashlib
import io
import json
import os
import stat
import subprocess
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import contextmanager
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import release_handoff as handoff
from maven_release_test_fixtures import (
    artifact_path,
    closed_release_payloads,
    refresh_module_metadata,
    replace_raw_zip_name,
    zip_payload,
)
from release_contract import (
    CHECKSUMS,
    GROUP_PATH,
    MODULES,
    expected_base_paths,
)


class _StreamingZipSink:
    """A non-seekable sink matching the streaming GitHub artifact writer."""

    def __init__(self) -> None:
        self._bytes = bytearray()

    def write(self, payload: bytes) -> int:
        self._bytes.extend(payload)
        return len(payload)

    def tell(self) -> int:
        return len(self._bytes)

    def flush(self) -> None:
        pass

    def seekable(self) -> bool:
        return False

    def seek(self, *_arguments: object) -> int:
        raise OSError("streaming ZIP output is not seekable")

    def payload(self) -> bytes:
        return bytes(self._bytes)


class ReleaseHandoffTest(unittest.TestCase):
    VERSION = "1.2.3"
    COMMIT = "0123456789abcdef0123456789abcdef01234567"
    MODULES = ("procwright", "procwright-integrations", "procwright-kotlin")

    @classmethod
    def setUpClass(cls) -> None:
        cls._gpg_directory = tempfile.TemporaryDirectory()
        root = Path(cls._gpg_directory.name)
        cls.gnupg_home = root / "gnupg"
        cls.gnupg_home.mkdir(mode=0o700)
        cls.passphrase_file = root / "passphrase"
        cls.passphrase_file.write_text("test-passphrase", encoding="utf-8")
        cls.passphrase_file.chmod(0o600)
        environment = os.environ.copy()
        environment["GNUPGHOME"] = str(cls.gnupg_home)
        generated = subprocess.run(
            [
                "gpg",
                "--batch",
                "--pinentry-mode",
                "loopback",
                "--passphrase-file",
                str(cls.passphrase_file),
                "--quick-generate-key",
                "Procwright Release Test <release-test@example.invalid>",
                "rsa2048",
                "cert",
                "1d",
            ],
            env=environment,
            check=False,
            capture_output=True,
            timeout=30,
        )
        if generated.returncode != 0:
            raise RuntimeError("isolated GPG test-key generation failed")
        listed = subprocess.run(
            ["gpg", "--batch", "--with-colons", "--list-secret-keys"],
            env=environment,
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        cls.fingerprint = next(
            line.split(":")[9]
            for line in listed.stdout.splitlines()
            if line.startswith("fpr:")
        )
        added = subprocess.run(
            [
                "gpg",
                "--batch",
                "--pinentry-mode",
                "loopback",
                "--passphrase-file",
                str(cls.passphrase_file),
                "--quick-add-key",
                cls.fingerprint,
                "rsa2048",
                "sign",
                "1d",
            ],
            env=environment,
            check=False,
            capture_output=True,
            timeout=30,
        )
        if added.returncode != 0:
            raise RuntimeError("isolated GPG signing-subkey generation failed")

    @classmethod
    def tearDownClass(cls) -> None:
        cls._gpg_directory.cleanup()

    def test_prepare_verify_finalize_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            artifact, artifact_digest, artifact_id, artifact_name = (
                self._verified_github_artifact_handoff()
            )
            self.assertRegex(artifact_digest, r"[0-9a-f]{64}")
            repository = Path("build/maven-central/repository")
            bundle = Path(
                f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
            )
            handoff.sign_and_finalize(
                repository,
                handoff.verified_manifest_path(self.VERSION),
                bundle,
                self.VERSION,
                self.gnupg_home,
                self.fingerprint,
                self.passphrase_file,
                github_artifact=artifact,
                github_artifact_digest=artifact_digest,
                github_artifact_id=artifact_id,
                github_artifact_name=artifact_name,
            )

            with zipfile.ZipFile(bundle) as archive:
                names = archive.namelist()
                self.assertEqual(len(names), 90)
                self.assertTrue(all(not name.startswith("/") for name in names))
                self.assertIn(
                    f"io/github/ulviar/procwright/{self.VERSION}/procwright-{self.VERSION}.pom.sha256",
                    names,
                )

    def test_prepare_rejects_unexpected_files_and_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            source = Path(".procwright-target/build/maven-central/repository")
            self._write_repository(source)
            (source / "unexpected.txt").write_text("unexpected", encoding="utf-8")
            with self.assertRaises(handoff.ReleaseHandoffError):
                handoff.prepare(
                    source,
                    Path(".procwright-target/build/release-handoff"),
                    self.VERSION,
                    self.COMMIT,
                    self.COMMIT,
                )

        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            source = Path(".procwright-target/build/maven-central/repository")
            self._write_repository(source)
            payload = next(iter(self._payload_paths(source)))
            payload.unlink()
            payload.symlink_to(Path("/etc/passwd"))
            with self.assertRaises(handoff.ReleaseHandoffError):
                handoff.prepare(
                    source,
                    Path(".procwright-target/build/release-handoff"),
                    self.VERSION,
                    self.COMMIT,
                    self.COMMIT,
                )

    def test_verify_rejects_tampering_extra_files_and_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            archive = downloaded / f"procwright-{self.VERSION}-unsigned.zip"
            archive.write_bytes(archive.read_bytes() + b"tampered")
            with self.assertRaises(handoff.ReleaseHandoffError):
                self._verify_downloaded(downloaded)

        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            (downloaded / "extra.txt").write_text("extra", encoding="utf-8")
            with self.assertRaises(handoff.ReleaseHandoffError):
                self._verify_downloaded(downloaded)

        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            checksum = downloaded / f"procwright-{self.VERSION}-unsigned.zip.sha256"
            checksum.unlink()
            checksum.symlink_to(Path("/etc/passwd"))
            with self.assertRaises(handoff.ReleaseHandoffError):
                self._verify_downloaded(downloaded)

    def test_verify_rejects_archive_traversal_even_with_matching_outer_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            archive_path = downloaded / f"procwright-{self.VERSION}-unsigned.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("../escape", b"hostile")
            digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
            (downloaded / f"procwright-{self.VERSION}-unsigned.zip.sha256").write_text(
                f"{digest}  {archive_path.name}\n", encoding="ascii"
            )
            manifest_path = downloaded / f"procwright-{self.VERSION}-unsigned-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["archive"] = {
                "name": archive_path.name,
                "sha256": digest,
                "size": archive_path.stat().st_size,
            }
            manifest_path.write_text(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

            with self.assertRaises(handoff.ReleaseHandoffError):
                self._verify_downloaded(downloaded)
            self.assertFalse(Path("escape").exists())

    def test_verify_rejects_raw_unsigned_zip_name_and_flag_bypasses(self) -> None:
        for mutation in (
            "nul",
            "local-central",
            "descriptor",
            "encrypted",
            "duplicate",
            "case-collision",
            "unicode-normalization",
            "path-normalization",
        ):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as directory,
                self._working_directory(Path(directory)),
            ):
                downloaded = self._prepared_handoff()
                archive_path = downloaded / f"procwright-{self.VERSION}-unsigned.zip"
                payload = archive_path.read_bytes()
                first_name = expected_base_paths(self.VERSION)[0]
                if mutation in {
                    "duplicate",
                    "case-collision",
                    "unicode-normalization",
                    "path-normalization",
                }:
                    with zipfile.ZipFile(archive_path) as archive:
                        entries = [
                            (info.filename, archive.read(info), 0o100644)
                            for info in archive.infolist()
                        ]
                    if mutation == "duplicate":
                        entries[1] = (entries[0][0], entries[1][1], entries[1][2])
                    elif mutation == "case-collision":
                        entries[0] = ("Collision", entries[0][1], entries[0][2])
                        entries[1] = ("collision", entries[1][1], entries[1][2])
                    elif mutation == "unicode-normalization":
                        entries[0] = ("e\u0301", entries[0][1], entries[0][2])
                    else:
                        entries[0] = ("a/../b", entries[0][1], entries[0][2])
                    payload = zip_payload(entries)
                elif mutation == "nul":
                    replacement = first_name[:-1] + "\x00"
                    payload = replace_raw_zip_name(payload, first_name, replacement)
                else:
                    payload = bytearray(payload)
                    local = payload.find(b"PK\x03\x04")
                    central = payload.find(b"PK\x01\x02")
                    self.assertGreaterEqual(local, 0)
                    self.assertGreaterEqual(central, 0)
                    if mutation == "local-central":
                        crc = struct.unpack_from("<L", payload, local + 14)[0]
                        struct.pack_into("<L", payload, local + 14, crc ^ 0x1)
                    else:
                        bit = 0x8 if mutation == "descriptor" else 0x1
                        local_flags = struct.unpack_from("<H", payload, local + 6)[0]
                        central_flags = struct.unpack_from("<H", payload, central + 8)[0]
                        struct.pack_into("<H", payload, local + 6, local_flags | bit)
                        struct.pack_into("<H", payload, central + 8, central_flags | bit)
                    payload = bytes(payload)
                self._replace_archive_identity(downloaded, payload)

                result = self._run_cli(
                    "verify",
                    "--handoff",
                    ".procwright-handoff",
                    "--output-repository",
                    "build/maven-central/repository",
                    "--version",
                    self.VERSION,
                    "--commit",
                    self.COMMIT,
                    "--workflow-sha",
                    self.COMMIT,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(Path("build/maven-central/repository").exists())

    def test_prepare_cli_rejects_cross_module_semantic_swap(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            source = Path(".procwright-target/build/maven-central/repository")
            self._write_repository(source)
            core = source / next(
                name
                for name in expected_base_paths(self.VERSION)
                if "/procwright/" in name and name.endswith(".jar")
            )
            integration = source / next(
                name
                for name in expected_base_paths(self.VERSION)
                if "/procwright-integrations/" in name and name.endswith(".jar")
            )
            core.write_bytes(integration.read_bytes())

            result = self._run_cli(
                "prepare",
                "--repository",
                ".procwright-target/build/maven-central/repository",
                "--output",
                ".procwright-target/build/release-handoff",
                "--version",
                self.VERSION,
                "--commit",
                self.COMMIT,
                "--workflow-sha",
                self.COMMIT,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(Path(".procwright-target/build/release-handoff").exists())

    def test_verify_rejects_output_outside_fixed_publish_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            with self.assertRaises(handoff.ReleaseHandoffError):
                handoff.verify(
                    downloaded,
                    Path(".procwright-trusted"),
                    self.VERSION,
                    self.COMMIT,
                    self.COMMIT,
                )

    def test_verify_rejects_raw_github_artifact_digest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded = self._prepared_handoff()
            artifact = downloaded / f"procwright-{self.VERSION}-github-artifact.zip"
            with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_STORED) as archive:
                for source in sorted(downloaded.iterdir()):
                    if source != artifact:
                        archive.writestr(handoff._zip_info(source.name), source.read_bytes())
            for source in tuple(downloaded.iterdir()):
                if source != artifact:
                    source.unlink()

            with self.assertRaisesRegex(
                handoff.ReleaseHandoffError, "GitHub artifact digest"
            ):
                handoff.verify(
                    downloaded,
                    Path("build/maven-central/repository"),
                    self.VERSION,
                    self.COMMIT,
                    self.COMMIT,
                    github_artifact_digest="0" * 64,
                    github_artifact_id="123456789",
                    github_artifact_name=(
                        f"procwright-{self.VERSION}-{self.COMMIT}-unsigned-release"
                    ),
                )
            self.assertFalse(Path("build/maven-central/repository").exists())

    def test_verify_accepts_streamed_github_artifact_data_descriptors(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            downloaded, _, _, _ = self._verified_github_artifact_handoff()
            artifact = next(downloaded.iterdir())
            with zipfile.ZipFile(artifact) as archive:
                self.assertTrue(all(info.flag_bits & 0x8 for info in archive.infolist()))
                self.assertTrue(
                    all(info.compress_type == zipfile.ZIP_DEFLATED for info in archive.infolist())
                )

    def test_signing_requires_a_verified_unsigned_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            repository = Path("build/maven-central/repository")
            self._write_repository(repository)
            with self.assertRaises(handoff.ReleaseHandoffError):
                handoff.sign_and_finalize(
                    repository,
                    handoff.verified_manifest_path(self.VERSION),
                    Path(
                        f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
                    ),
                    self.VERSION,
                    self.gnupg_home,
                    self.fingerprint,
                    self.passphrase_file,
                )

    def test_signing_requires_raw_github_artifact_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            self._verified_github_artifact_handoff()

            with self.assertRaisesRegex(
                handoff.ReleaseHandoffError, "original GitHub artifact identity"
            ):
                handoff.sign_and_finalize(
                    Path("build/maven-central/repository"),
                    handoff.verified_manifest_path(self.VERSION),
                    Path(
                        f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
                    ),
                    self.VERSION,
                    self.gnupg_home,
                    self.fingerprint,
                    self.passphrase_file,
                )

    def test_signing_rejects_a_non_private_passphrase_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            artifact, artifact_digest, artifact_id, artifact_name = (
                self._verified_github_artifact_handoff()
            )
            bundle = Path(
                f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
            )
            self.passphrase_file.chmod(0o644)
            try:
                with self.assertRaisesRegex(
                    handoff.ReleaseHandoffError, "passphrase must be owner-only"
                ):
                    handoff.sign_and_finalize(
                        Path("build/maven-central/repository"),
                        handoff.verified_manifest_path(self.VERSION),
                        bundle,
                        self.VERSION,
                        self.gnupg_home,
                        self.fingerprint,
                        self.passphrase_file,
                        github_artifact=artifact,
                        github_artifact_digest=artifact_digest,
                        github_artifact_id=artifact_id,
                        github_artifact_name=artifact_name,
                    )
            finally:
                self.passphrase_file.chmod(0o600)
            self.assertFalse(bundle.exists())

    def test_signing_rejects_every_unsigned_manifest_entry_identity_drift(self) -> None:
        mutations = {
            "path": "io/github/ulviar/other/1.2.3/other-1.2.3.jar",
            "module": "other",
            "baseSuffix": "-tests.jar",
            "role": "signature",
            "size": 0,
            "sha256": "0" * 64,
        }
        for field, value in mutations.items():
            with (
                self.subTest(field=field),
                tempfile.TemporaryDirectory() as directory,
                self._working_directory(Path(directory)),
            ):
                artifact, artifact_digest, artifact_id, artifact_name = (
                    self._verified_github_artifact_handoff()
                )
                manifest_path = handoff.verified_manifest_path(self.VERSION)
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                manifest["entries"][0][field] = value
                manifest_path.chmod(0o600)
                manifest_path.write_text(
                    json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                    encoding="utf-8",
                )
                bundle = Path(
                    f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
                )
                with self.assertRaisesRegex(
                    handoff.ReleaseHandoffError, "does not bind"
                ):
                    handoff.sign_and_finalize(
                        Path("build/maven-central/repository"),
                        manifest_path,
                        bundle,
                        self.VERSION,
                        self.gnupg_home,
                        self.fingerprint,
                        self.passphrase_file,
                        github_artifact=artifact,
                        github_artifact_digest=artifact_digest,
                        github_artifact_id=artifact_id,
                        github_artifact_name=artifact_name,
                    )
                self.assertFalse(bundle.exists())

    def test_signing_rejects_consistent_repository_and_manifest_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            artifact, artifact_digest, artifact_id, artifact_name = (
                self._verified_github_artifact_handoff()
            )
            repository = Path("build/maven-central/repository")
            target_name = next(
                name
                for name in expected_base_paths(self.VERSION)
                if "/procwright/" in name and name.endswith("-sources.jar")
            )
            target = repository / target_name
            with zipfile.ZipFile(target) as archive:
                source_entries = {
                    info.filename: archive.read(info) for info in archive.infolist()
                }
            source_name = next(
                name for name in source_entries if name.endswith("/Procwright.java")
            )
            source_entries[source_name] += b"\n// Consistent replacement fixture.\n"
            replacement_buffer = io.BytesIO()
            with zipfile.ZipFile(
                replacement_buffer, "w", compression=zipfile.ZIP_STORED
            ) as archive:
                for name, payload in sorted(source_entries.items()):
                    archive.writestr(handoff._zip_info(name), payload)
            replacement = replacement_buffer.getvalue()
            target.chmod(0o600)
            target.write_bytes(replacement)
            replacements = {
                name: (repository / name).read_bytes()
                for name in expected_base_paths(self.VERSION)
            }
            replacements[target_name] = replacement
            refresh_module_metadata(replacements, "procwright", self.VERSION)
            module_name = artifact_path("procwright", ".module", self.VERSION)
            module_target = repository / module_name
            module_target.chmod(0o600)
            module_target.write_bytes(replacements[module_name])
            manifest_path = handoff.verified_manifest_path(self.VERSION)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            for changed_name in (target_name, module_name):
                entry = next(
                    item
                    for item in manifest["entries"]
                    if item["path"] == changed_name
                )
                changed = replacements[changed_name]
                entry["sha256"] = hashlib.sha256(changed).hexdigest()
                entry["size"] = len(changed)
            manifest_path.chmod(0o600)
            manifest_bytes = (
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode("utf-8")
            manifest_path.write_bytes(manifest_bytes)
            receipt_path = handoff.verified_receipt_path(self.VERSION)
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            receipt["entries"] = manifest["entries"]
            receipt["unsignedManifest"] = {
                "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
                "size": len(manifest_bytes),
            }
            receipt_path.chmod(0o600)
            receipt_path.write_text(
                json.dumps(receipt, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            bundle = Path(
                f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
            )

            with self.assertRaisesRegex(
                handoff.ReleaseHandoffError,
                "snapshots differ from the original GitHub artifact",
            ):
                handoff.sign_and_finalize(
                    repository,
                    manifest_path,
                    bundle,
                    self.VERSION,
                    self.gnupg_home,
                    self.fingerprint,
                    self.passphrase_file,
                    github_artifact=artifact,
                    github_artifact_digest=artifact_digest,
                    github_artifact_id=artifact_id,
                    github_artifact_name=artifact_name,
                )
            self.assertFalse(bundle.exists())

    def test_persistent_signing_evidence_rejects_a_real_signature_for_wrong_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory, self._working_directory(
            Path(directory)
        ):
            artifact, artifact_digest, artifact_id, artifact_name = (
                self._verified_github_artifact_handoff()
            )
            bundle = Path(
                f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
            )
            handoff.sign_and_finalize(
                Path("build/maven-central/repository"),
                handoff.verified_manifest_path(self.VERSION),
                bundle,
                self.VERSION,
                self.gnupg_home,
                self.fingerprint,
                self.passphrase_file,
                github_artifact=artifact,
                github_artifact_digest=artifact_digest,
                github_artifact_id=artifact_id,
                github_artifact_name=artifact_name,
            )
            evidence_path = handoff.signing_evidence_path(self.VERSION)
            public_key_path = handoff.signing_public_key_path(self.VERSION)
            self.assertEqual(stat.S_IMODE(evidence_path.stat().st_mode), 0o400)
            self.assertEqual(stat.S_IMODE(public_key_path.stat().st_mode), 0o400)
            self.assertNotIn(b"PRIVATE KEY", public_key_path.read_bytes())
            handoff.verify_signing_evidence(
                bundle,
                evidence_path,
                public_key_path,
                self.VERSION,
                self.fingerprint,
                artifact,
                artifact_digest,
                artifact_id,
                artifact_name,
            )
            self.assertEqual(
                handoff.verify_persisted_signing_evidence(
                    bundle,
                    evidence_path,
                    public_key_path,
                    handoff.verified_receipt_path(self.VERSION),
                    self.VERSION,
                    self.COMMIT,
                ),
                (
                    hashlib.sha256(bundle.read_bytes()).hexdigest(),
                    self.fingerprint,
                ),
            )

            with zipfile.ZipFile(bundle) as archive:
                payloads = {
                    info.filename: archive.read(info) for info in archive.infolist()
                }
            signatures = sorted(name for name in payloads if name.endswith(".asc"))
            payloads[signatures[0]], payloads[signatures[1]] = (
                payloads[signatures[1]],
                payloads[signatures[0]],
            )
            bundle.unlink()
            handoff._write_archive(bundle, payloads)
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            bundle_bytes = bundle.read_bytes()
            evidence["bundle"] = {
                "sha256": hashlib.sha256(bundle_bytes).hexdigest(),
                "size": len(bundle_bytes),
            }
            by_path = {item["signaturePath"]: item for item in evidence["signatures"]}
            for signature in signatures[:2]:
                by_path[signature]["sha256"] = hashlib.sha256(payloads[signature]).hexdigest()
                by_path[signature]["size"] = len(payloads[signature])
            evidence_path.chmod(0o600)
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            evidence_path.chmod(0o400)

            with self.assertRaisesRegex(
                handoff.ReleaseHandoffError,
                "signature does not verify",
            ):
                handoff.verify_signing_evidence(
                    bundle,
                    evidence_path,
                    public_key_path,
                    self.VERSION,
                    self.fingerprint,
                    artifact,
                    artifact_digest,
                    artifact_id,
                    artifact_name,
                )
            with self.assertRaisesRegex(
                handoff.ReleaseHandoffError,
                "signature does not verify",
            ):
                handoff.verify_persisted_signing_evidence(
                    bundle,
                    evidence_path,
                    public_key_path,
                    handoff.verified_receipt_path(self.VERSION),
                    self.VERSION,
                    self.COMMIT,
                )

    def test_signing_rejects_replacements_at_every_captured_phase(self) -> None:
        cases = (
            ("after-collect", "jar"),
            ("after-collect", "pom"),
            ("after-snapshot", "jar"),
            ("after-snapshot", "pom"),
            ("after-signature", "jar-signature"),
            ("after-signature", "pom-signature"),
            ("before-finalize", "jar"),
            ("before-finalize", "pom"),
        )
        for mutation, role in cases:
            with (
                self.subTest(mutation=mutation, role=role),
                tempfile.TemporaryDirectory() as directory,
                self._working_directory(Path(directory)),
            ):
                artifact, artifact_digest, artifact_id, artifact_name = (
                    self._verified_github_artifact_handoff()
                )
                repository = Path("build/maven-central/repository")
                bundle = Path(
                    f"build/maven-central/procwright-{self.VERSION}-maven-central-bundle.zip"
                )
                mutated = False
                base_role = role.removesuffix("-signature")
                suffix = ".jar" if base_role == "jar" else ".pom"
                relative_target = artifact_path("procwright", suffix, self.VERSION)
                snapshot_target = relative_target + (
                    ".asc" if role.endswith("-signature") else ""
                )

                def replace(phase: str, path: Path) -> None:
                    nonlocal mutated
                    if (
                        mutated
                        or phase != mutation
                        or (
                            phase in {"after-snapshot", "after-signature"}
                            and not path.as_posix().endswith(snapshot_target)
                        )
                    ):
                        return
                    mutated = True
                    if phase in {"after-snapshot", "after-signature"}:
                        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o400)
                        path.chmod(0o600)
                        path.write_bytes(path.read_bytes() + b"replacement")
                    else:
                        target = repository / relative_target
                        target.chmod(0o600)
                        target.write_bytes(target.read_bytes() + b"replacement")

                with self.assertRaises(handoff.ReleaseHandoffError):
                    handoff.sign_and_finalize(
                        repository,
                        handoff.verified_manifest_path(self.VERSION),
                        bundle,
                        self.VERSION,
                        self.gnupg_home,
                        self.fingerprint,
                        self.passphrase_file,
                        github_artifact=artifact,
                        github_artifact_digest=artifact_digest,
                        github_artifact_id=artifact_id,
                        github_artifact_name=artifact_name,
                        phase_hook=replace,
                    )
                self.assertTrue(mutated)
                self.assertFalse(bundle.exists())

    def _prepared_handoff(self) -> Path:
        source = Path(".procwright-target/build/maven-central/repository")
        self._write_repository(source)
        produced = Path(".procwright-target/build/release-handoff")
        handoff.prepare(
            source, produced, self.VERSION, self.COMMIT, self.COMMIT
        )
        downloaded = Path(".procwright-handoff")
        downloaded.mkdir()
        for file in produced.iterdir():
            (downloaded / file.name).write_bytes(file.read_bytes())
        return downloaded

    def _verified_github_artifact_handoff(self) -> tuple[Path, str, str, str]:
        downloaded = self._prepared_handoff()
        artifact = downloaded / f"procwright-{self.VERSION}-github-artifact.zip"
        sink = _StreamingZipSink()
        with zipfile.ZipFile(sink, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for source in sorted(downloaded.iterdir()):
                if source == artifact:
                    continue
                archive.write(source, source.name)
        artifact.write_bytes(sink.payload())
        for source in tuple(downloaded.iterdir()):
            if source != artifact:
                source.unlink()
        artifact_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        artifact_id = "123456789"
        artifact_name = (
            f"procwright-{self.VERSION}-{self.COMMIT}-unsigned-release"
        )
        handoff.verify(
            downloaded,
            Path("build/maven-central/repository"),
            self.VERSION,
            self.COMMIT,
            self.COMMIT,
            github_artifact_digest=artifact_digest,
            github_artifact_id=artifact_id,
            github_artifact_name=artifact_name,
        )
        self.assertEqual(
            stat.S_IMODE(handoff.verified_manifest_path(self.VERSION).stat().st_mode),
            0o400,
        )
        self.assertEqual(
            stat.S_IMODE(handoff.verified_receipt_path(self.VERSION).stat().st_mode),
            0o400,
        )
        for relative in expected_base_paths(self.VERSION):
            self.assertEqual(
                stat.S_IMODE(
                    (Path("build/maven-central/repository") / relative).stat().st_mode
                ),
                0o400,
            )
        return downloaded, artifact_digest, artifact_id, artifact_name

    def _replace_archive_identity(self, downloaded: Path, payload: bytes) -> None:
        archive = downloaded / f"procwright-{self.VERSION}-unsigned.zip"
        archive.write_bytes(payload)
        digest = hashlib.sha256(payload).hexdigest()
        (downloaded / f"procwright-{self.VERSION}-unsigned.zip.sha256").write_text(
            f"{digest}  {archive.name}\n", encoding="ascii"
        )
        manifest_path = downloaded / f"procwright-{self.VERSION}-unsigned-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["archive"] = {
            "name": archive.name,
            "sha256": digest,
            "size": len(payload),
        }
        manifest_path.write_text(
            json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )

    def _run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPTS_DIRECTORY / "release_handoff.py"), *arguments],
            cwd=Path.cwd(),
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )

    def _verify_downloaded(self, downloaded: Path) -> str:
        return handoff.verify(
            downloaded,
            Path("build/maven-central/repository"),
            self.VERSION,
            self.COMMIT,
            self.COMMIT,
        )

    def _write_repository(self, root: Path) -> None:
        payloads = closed_release_payloads(self.VERSION)
        for relative in expected_base_paths(self.VERSION):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = payloads[relative]
            path.write_bytes(payload)
            for algorithm, _length in CHECKSUMS:
                path.with_name(path.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, payload).hexdigest(), encoding="ascii"
                )
        for module in MODULES:
            metadata = root / GROUP_PATH / module / "maven-metadata.xml"
            metadata.parent.mkdir(parents=True, exist_ok=True)
            payload = f"<metadata><artifactId>{module}</artifactId></metadata>\n".encode()
            metadata.write_bytes(payload)
            for algorithm, _length in CHECKSUMS:
                metadata.with_name(metadata.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, payload).hexdigest(), encoding="ascii"
                )

    def _payload_paths(self, root: Path):
        for relative in expected_base_paths(self.VERSION):
            yield root / relative

    @contextmanager
    def _working_directory(self, directory: Path):
        previous = Path.cwd()
        os.chdir(directory)
        try:
            yield
        finally:
            os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
