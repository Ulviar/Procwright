import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import verify_local_maven_central_bundle as local_bundle
from maven_release_test_fixtures import (
    VERSION,
    closed_release_payloads,
    write_bundle,
)
from release_contract import (
    CHECKSUMS,
    GROUP_PATH,
    MODULES,
    expected_base_paths,
)


class LocalMavenCentralBundleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.bundle = self.root / "bundle.zip"
        self.payloads = closed_release_payloads()
        self._write_repository()
        write_bundle(self.bundle, self.payloads)

    def test_accepts_exact_repository_and_bundle(self) -> None:
        local_bundle.verify_local_postcondition(self.repository, self.bundle, VERSION)

    def test_accepts_exact_unsigned_gradle_repository(self) -> None:
        unsigned_repository = self.root / "unsigned-repository"
        self._write_unsigned_repository(unsigned_repository)

        payloads = local_bundle.verify_unsigned_repository(
            unsigned_repository, VERSION
        )

        self.assertEqual(tuple(sorted(payloads)), expected_base_paths(VERSION))

    def test_unsigned_repository_rejects_metadata_basename_at_wrong_path(self) -> None:
        unsigned_repository = self.root / "unsigned-repository"
        self._write_unsigned_repository(unsigned_repository)
        expected = unsigned_repository / GROUP_PATH / "procwright" / "maven-metadata.xml"
        misplaced = unsigned_repository / "untrusted" / "maven-metadata.xml"
        misplaced.parent.mkdir(parents=True)
        expected.replace(misplaced)

        with self.assertRaisesRegex(local_bundle.LocalBundleError, "file set is not exact"):
            local_bundle.verify_unsigned_repository(unsigned_repository, VERSION)

    def test_unsigned_repository_rejects_missing_and_extra_metadata_sidecars(self) -> None:
        for mutation in ("missing", "extra"):
            with self.subTest(mutation=mutation):
                unsigned_repository = self.root / f"unsigned-{mutation}"
                self._write_unsigned_repository(unsigned_repository)
                metadata = (
                    unsigned_repository
                    / GROUP_PATH
                    / "procwright"
                    / "maven-metadata.xml.sha256"
                )
                if mutation == "missing":
                    metadata.unlink()
                else:
                    (metadata.parent / "maven-metadata.xml.asc").write_text(
                        "unexpected", encoding="ascii"
                    )

                with self.assertRaisesRegex(
                    local_bundle.LocalBundleError, "file set is not exact"
                ):
                    local_bundle.verify_unsigned_repository(
                        unsigned_repository, VERSION
                    )

    def test_production_cli_rejects_extra_tests_classifier_in_repository(self) -> None:
        extra = (
            self.repository
            / GROUP_PATH
            / "procwright"
            / VERSION
            / f"procwright-{VERSION}-tests.jar"
        )
        extra.write_bytes(b"not published")

        result = self._run_cli()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("extra", result.stderr)

    def test_production_cli_rejects_extra_classifier_in_zip(self) -> None:
        payloads = dict(self.payloads)
        payloads[
            f"{GROUP_PATH}/procwright/{VERSION}/procwright-{VERSION}-tests.jar"
        ] = b"not published"
        write_bundle(self.bundle, payloads)

        result = self._run_cli()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("too many entries", result.stderr)

    def test_rejects_repository_bundle_byte_mismatch(self) -> None:
        first = next(iter(self.payloads))
        (self.repository / first).write_bytes(b"replacement")

        with self.assertRaises(local_bundle.LocalBundleError):
            local_bundle.verify_local_postcondition(
                self.repository, self.bundle, VERSION
            )

    def _write_repository(self) -> None:
        for name, payload in self.payloads.items():
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
        for module in MODULES:
            metadata = self.repository / GROUP_PATH / module / "maven-metadata.xml"
            metadata.parent.mkdir(parents=True, exist_ok=True)
            payload = f"<metadata><artifactId>{module}</artifactId></metadata>\n".encode()
            metadata.write_bytes(payload)
            for algorithm, _length in CHECKSUMS:
                metadata.with_name(metadata.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, payload).hexdigest(), encoding="ascii"
                )

    def _write_unsigned_repository(self, repository: Path) -> None:
        base_payloads = {
            name: payload
            for name, payload in self.payloads.items()
            if name in expected_base_paths(VERSION)
        }
        for name, payload in base_payloads.items():
            path = repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            for algorithm, _length in CHECKSUMS:
                path.with_name(path.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, payload).hexdigest(), encoding="ascii"
                )
        for module in MODULES:
            metadata = repository / GROUP_PATH / module / "maven-metadata.xml"
            metadata.parent.mkdir(parents=True, exist_ok=True)
            payload = f"<metadata><artifactId>{module}</artifactId></metadata>\n".encode()
            metadata.write_bytes(payload)
            for algorithm, _length in CHECKSUMS:
                metadata.with_name(metadata.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, payload).hexdigest(), encoding="ascii"
                )

    def _run_cli(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPTS_DIRECTORY / "verify_local_maven_central_bundle.py"),
                "--repository",
                str(self.repository),
                "--bundle",
                str(self.bundle),
                "--version",
                VERSION,
            ],
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )


if __name__ == "__main__":
    unittest.main()
