"""Regression tests for the privileged Maven Central signing wrapper."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

from maven_release_test_fixtures import EphemeralSigningKey


REPOSITORY_ROOT = SCRIPTS_DIRECTORY.parent
SIGNING_SCRIPT = REPOSITORY_ROOT / "scripts/release/sign_release_handoff.sh"


class SignReleaseHandoffTest(unittest.TestCase):
    def test_rejects_malformed_approved_fingerprint_before_import(self) -> None:
        with tempfile.TemporaryDirectory(prefix="psh-", dir="/tmp") as root:
            runner_temp = Path(root) / "runner"
            runner_temp.mkdir(mode=0o700)
            environment = {
                **os.environ,
                "PROCWRIGHT_RELEASE_VERSION": "1.2.3",
                "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT": "a" * 40,
                "RUNNER_TEMP": str(runner_temp),
                "SIGNING_KEY": "private-key-must-not-be-read",
                "SIGNING_PASSWORD": "password-must-not-be-read",
            }

            result = subprocess.run(
                ["bash", str(SIGNING_SCRIPT)],
                cwd=root,
                env=environment,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn(
                "Approved signing fingerprint must be 40 uppercase hexadecimal characters",
                result.stderr,
            )
            self.assertNotIn("private-key-must-not-be-read", result.stdout + result.stderr)
            self.assertFalse((runner_temp / "procwright-release-gnupg").exists())

    def test_rejects_secret_key_that_does_not_match_independent_fingerprint(self) -> None:
        imported = EphemeralSigningKey(
            "Imported Procwright Test Key <imported@example.invalid>"
        )
        approved = EphemeralSigningKey(
            "Approved Procwright Test Key <approved@example.invalid>"
        )
        try:
            with tempfile.TemporaryDirectory(prefix="psh-", dir="/tmp") as root:
                runner_temp = Path(root) / "runner"
                runner_temp.mkdir(mode=0o700)
                environment = {
                    **os.environ,
                    "PROCWRIGHT_RELEASE_VERSION": "1.2.3",
                    "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT": approved.fingerprint,
                    "RUNNER_TEMP": str(runner_temp),
                    "SIGNING_KEY": imported.export_secret_key().decode("ascii"),
                    "SIGNING_PASSWORD": "unused-test-password",
                }

                result = subprocess.run(
                    ["bash", str(SIGNING_SCRIPT)],
                    cwd=root,
                    env=environment,
                    check=False,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    timeout=30,
                    text=True,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(
                    "Signing key does not match the independently approved fingerprint",
                    result.stderr,
                )
                self.assertNotIn(imported.fingerprint, result.stdout + result.stderr)
                self.assertNotIn(approved.fingerprint, result.stdout + result.stderr)
                self.assertNotIn(
                    "BEGIN PGP PRIVATE KEY BLOCK", result.stdout + result.stderr
                )
                self.assertFalse(
                    (runner_temp / "procwright-release-gnupg").exists()
                )
                self.assertFalse(
                    (runner_temp / "procwright-release-signing-key.asc").exists()
                )
                self.assertFalse(
                    (runner_temp / "procwright-release-signing-passphrase").exists()
                )
        finally:
            approved.close()
            imported.close()


if __name__ == "__main__":
    unittest.main()
