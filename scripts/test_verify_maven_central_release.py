import io
import hashlib
import json
import stat
import sys
import unittest
import urllib.error
import urllib.parse
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import maven_central_repository as repository
import verify_maven_central_release as release
from maven_release_test_fixtures import (
    EphemeralSigningKey,
    VERSION,
    artifact_path,
    closed_release_payloads,
    cryptographically_sign_payloads,
    refresh_base_sidecars,
    replace_base_payload,
    zip_payload,
)
from release_contract import expected_release_paths


class Response:
    def __init__(self, body, url, content_type, *, after_read=None, headers=None):
        self._body = io.BytesIO(body)
        self._url = url
        self.status = 200
        self.headers = headers or {
            "Content-Length": str(len(body)),
            "Content-Type": content_type,
        }
        self._after_read = after_read

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def geturl(self):
        return self._url

    def getcode(self):
        return self.status

    def read(self, size=-1):
        result = self._body.read(size)
        if self._after_read is not None:
            self._after_read()
        return result


def opener_for(payloads, *, requested=None, after_read=None):
    def opener(request, timeout):
        path = urllib.parse.unquote(
            urllib.parse.urlsplit(request.full_url).path.removeprefix("/maven2/")
        )
        if requested is not None:
            requested.append(path)
        if path not in payloads:
            raise urllib.error.HTTPError(request.full_url, 404, "not found", {}, None)
        return Response(
            payloads[path],
            request.full_url,
            repository.artifact_content_type(path, VERSION),
            after_read=after_read,
        )

    return opener


class MavenCentralReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.signing_key = EphemeralSigningKey()
        cls.wrong_signing_key = EphemeralSigningKey(
            "Wrong Procwright Release Test <wrong-release-test@example.invalid>"
        )
        cls.signed_payloads = closed_release_payloads()
        cryptographically_sign_payloads(cls.signed_payloads, cls.signing_key)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.wrong_signing_key.close()
        cls.signing_key.close()

    def setUp(self) -> None:
        self.payloads = dict(self.signed_payloads)

    def verify(self, payloads, **options):
        return release.verify_release(
            VERSION,
            approved_public_key=self.signing_key.public_key,
            approved_fingerprint=self.signing_key.fingerprint,
            opener=opener_for(payloads),
            attempts=1,
            retry_delay_seconds=0,
            overall_deadline_seconds=60,
            **options,
        )

    def test_accepts_strict_semver_and_rejects_hostile_versions(self) -> None:
        for version in ("0.1.0", "10.20.30-rc.1", VERSION):
            self.assertEqual(release.validate_version(version), version)
        for version in ("", "v1.2.3", "01.2.3", "1.2", "1.2.3/../4", "1.2.3-"):
            with self.subTest(version=version):
                with self.assertRaises(release.ReleaseEvidenceError):
                    release.validate_version(version)

    def test_recovery_downloads_and_validates_the_same_complete_90_file_set(self) -> None:
        requested = []
        result = release.verify_release(
            VERSION,
            approved_public_key=self.signing_key.public_key,
            approved_fingerprint=self.signing_key.fingerprint,
            opener=opener_for(self.payloads, requested=requested),
            attempts=1,
            retry_delay_seconds=0,
            overall_deadline_seconds=60,
        )

        self.assertEqual(tuple(requested), expected_release_paths(VERSION))
        self.assertEqual(result, self.payloads)
        self.assertEqual(len(result), 90)
        for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module"):
            base = artifact_path("procwright", suffix)
            self.assertIn(base, result)
            self.assertIn(base + ".asc", result)
            for checksum in ("md5", "sha1", "sha256", "sha512"):
                self.assertIn(f"{base}.{checksum}", result)

    def test_recovery_rejects_missing_classifier_or_signature(self) -> None:
        for missing in (
            artifact_path("procwright", "-sources.jar"),
            artifact_path("procwright-kotlin", "-javadoc.jar") + ".asc",
        ):
            with self.subTest(missing=missing):
                payloads = dict(self.payloads)
                payloads.pop(missing)
                calls = []
                with self.assertRaisesRegex(
                    release.ReleaseEvidenceError, "after 2 attempts"
                ):
                    release.verify_release(
                        VERSION,
                        approved_public_key=self.signing_key.public_key,
                        approved_fingerprint=self.signing_key.fingerprint,
                        opener=opener_for(payloads, requested=calls),
                        attempts=2,
                        retry_delay_seconds=0,
                        overall_deadline_seconds=60,
                    )
                self.assertEqual(calls.count(missing), 2)

    def test_recovery_rejects_fake_jar_pom_module_signature_and_checksum(self) -> None:
        mutations = []

        no_class = dict(self.payloads)
        replace_base_payload(
            no_class,
            artifact_path("procwright", ".jar"),
            zip_payload(
                [("META-INF/MANIFEST.MF", b"manifest", stat.S_IFREG | 0o644)]
            ),
        )
        mutations.append(("package identity", no_class))

        fake_pom = dict(self.payloads)
        path = artifact_path("procwright", ".pom")
        replace_base_payload(
            fake_pom,
            path,
            fake_pom[path].replace(
                b"</project>",
                b"<repositories><repository/></repositories></project>",
            ),
        )
        mutations.append(("POM contains repository", fake_pom))

        fake_module = dict(self.payloads)
        path = artifact_path("procwright-integrations", ".module")
        metadata = json.loads(fake_module[path])
        metadata["variants"][0]["dependencies"][0]["version"]["requires"] = "9.9.9"
        replace_base_payload(
            fake_module,
            path,
            json.dumps(metadata, separators=(",", ":")).encode(),
        )
        mutations.append(("module dependencies", fake_module))

        fake_signature = dict(self.payloads)
        fake_signature[artifact_path("procwright", ".pom") + ".asc"] = b"signature"
        mutations.append(("armored signature", fake_signature))

        fake_checksum = dict(self.payloads)
        checksum = artifact_path("procwright", ".jar") + ".sha256"
        fake_checksum[checksum] += b"  /dev/zero"
        mutations.append(("checksum", fake_checksum))

        for expected, payloads in mutations:
            with self.subTest(expected=expected):
                with self.assertRaisesRegex(release.ReleaseEvidenceError, expected):
                    release.verify_release(
                        VERSION,
                        approved_public_key=self.signing_key.public_key,
                        approved_fingerprint=self.signing_key.fingerprint,
                        opener=opener_for(payloads),
                        attempts=1,
                        retry_delay_seconds=0,
                        overall_deadline_seconds=60,
                    )

    def test_recovery_uses_one_deadline_across_all_files_and_reads(self) -> None:
        now = [0.0]

        def advance():
            now[0] += 0.02

        with self.assertRaisesRegex(release.ReleaseEvidenceError, "deadline"):
            release.verify_release(
                VERSION,
                approved_public_key=self.signing_key.public_key,
                approved_fingerprint=self.signing_key.fingerprint,
                opener=opener_for(self.payloads, after_read=advance),
                attempts=1,
                retry_delay_seconds=0,
                overall_deadline_seconds=0.1,
                clock=lambda: now[0],
            )

    def test_recovery_rejects_wrong_content_headers_before_reading(self) -> None:
        first_path = expected_release_paths(VERSION)[0]
        reads = [0]

        class WrongResponse(Response):
            def read(self, size=-1):
                reads[0] += 1
                return super().read(size)

        def opener(request, timeout):
            path = urllib.parse.unquote(
                urllib.parse.urlsplit(request.full_url).path.removeprefix("/maven2/")
            )
            return WrongResponse(
                self.payloads[path],
                request.full_url,
                "text/html",
            )

        with self.assertRaisesRegex(release.ReleaseEvidenceError, "Content-Type"):
            release.verify_release(
                VERSION,
                approved_public_key=self.signing_key.public_key,
                approved_fingerprint=self.signing_key.fingerprint,
                opener=opener,
                attempts=1,
                retry_delay_seconds=0,
                overall_deadline_seconds=60,
            )
        self.assertEqual(reads[0], 0)
        self.assertEqual(first_path, expected_release_paths(VERSION)[0])

    def test_remote_error_body_and_reason_are_not_exposed(self) -> None:
        secret = "credential\x1b[31m raw-response"

        def forbidden(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 403, secret, {}, io.BytesIO(secret.encode()))

        with self.assertRaises(release.ReleaseEvidenceError) as raised:
            release.verify_release(
                VERSION,
                approved_public_key=self.signing_key.public_key,
                approved_fingerprint=self.signing_key.fingerprint,
                opener=forbidden,
                attempts=3,
                retry_delay_seconds=0,
                overall_deadline_seconds=60,
            )
        message = str(raised.exception)
        self.assertIn("status 403", message)
        self.assertNotIn(secret, message)
        self.assertNotIn("\x1b", message)

    def test_recovery_rejects_wrong_unapproved_or_ambiguous_key_material(self) -> None:
        hostile = (
            (self.wrong_signing_key.public_key, self.signing_key.fingerprint),
            (self.signing_key.public_key, self.wrong_signing_key.fingerprint),
            (
                self.signing_key.public_key + self.wrong_signing_key.public_key,
                self.signing_key.fingerprint,
            ),
            (b"not a public key", self.signing_key.fingerprint),
            (self.signing_key.public_key, self.signing_key.fingerprint.lower()),
        )
        for public_key, fingerprint in hostile:
            with self.subTest(fingerprint=fingerprint):
                with self.assertRaises(release.ReleaseEvidenceError):
                    release.verify_release(
                        VERSION,
                        approved_public_key=public_key,
                        approved_fingerprint=fingerprint,
                        opener=opener_for(self.payloads),
                        attempts=1,
                        retry_delay_seconds=0,
                        overall_deadline_seconds=60,
                    )

    def test_recovery_rejects_wrong_key_tampered_artifact_and_signature(self) -> None:
        wrong_key = dict(self.payloads)
        cryptographically_sign_payloads(wrong_key, self.wrong_signing_key)

        tampered_artifact = dict(self.payloads)
        path = artifact_path("procwright", ".pom")
        tampered_artifact[path] = tampered_artifact[path].replace(
            b"<name>Procwright</name>", b"<name>Procwright </name>"
        )
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            tampered_artifact[path + "." + algorithm] = hashlib.new(
                algorithm, tampered_artifact[path]
            ).hexdigest().encode("ascii")

        tampered_signature = dict(self.payloads)
        signature_path = path + ".asc"
        signature = bytearray(tampered_signature[signature_path])
        body_offset = signature.index(b"\n\n") + 2
        signature[body_offset] = ord("A") if signature[body_offset] != ord("A") else ord("B")
        tampered_signature[signature_path] = bytes(signature)

        structurally_valid_but_fake = dict(self.payloads)
        refresh_base_sidecars(structurally_valid_but_fake, path)

        substituted_signature = dict(self.payloads)
        substituted_signature[signature_path] = self.payloads[
            artifact_path("procwright-integrations", ".pom") + ".asc"
        ]

        for payloads in (
            wrong_key,
            tampered_artifact,
            tampered_signature,
            structurally_valid_but_fake,
            substituted_signature,
        ):
            with self.subTest(signature=payloads[signature_path][:24]):
                with self.assertRaises(release.ReleaseEvidenceError):
                    self.verify(payloads)


if __name__ == "__main__":
    unittest.main()
