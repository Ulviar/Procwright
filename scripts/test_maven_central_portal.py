import io
import json
import ssl
import sys
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import maven_central_portal as portal
from maven_release_test_fixtures import COMMIT, DEPLOYMENT_ID, VERSION


def status_payload(identity, state="VALIDATED", **changes) -> bytes:
    value = {
        "deploymentId": DEPLOYMENT_ID,
        "deploymentName": identity.deployment_name,
        "deploymentState": state,
        "purls": list(identity.purls if state not in {"PENDING", "VALIDATING"} else ()),
    }
    if state == "FAILED":
        value["errors"] = []
    value.update(changes)
    return json.dumps(value, separators=(",", ":")).encode()


def response(status, url, content_type, body, **headers):
    all_headers = [
        ("Content-Length", str(len(body))),
        ("Content-Type", content_type),
    ]
    all_headers.extend(headers.items())
    return portal.ResponseData(status, url, tuple(all_headers), body)


class MavenCentralPortalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.identity = portal.PortalIdentity(VERSION, COMMIT)

    def test_credentials_never_enter_argv_or_child_process(self) -> None:
        observed = {}

        def transport(method, url, headers, content_length, chunks, deadline):
            observed.update(headers)
            body = status_payload(self.identity)
            return response(200, url, "application/json", body)

        with mock.patch(
            "subprocess.Popen",
            side_effect=AssertionError("Portal credentials must not reach a child process"),
        ):
            client = portal.PortalClient(
                "central-user", "central-password", transport=transport
            )
            state, _purls = client.status(
                self.identity, DEPLOYMENT_ID, portal.Deadline(60), attempts=1
            )

        self.assertEqual(state, "VALIDATED")
        self.assertNotIn("central-user", " ".join(sys.argv))
        self.assertNotIn("central-password", " ".join(sys.argv))
        self.assertTrue(observed["Authorization"].startswith("Bearer "))

    def test_rejects_ambiguous_oversized_or_control_character_credentials(self) -> None:
        values = (
            ("", "password"),
            ("user:name", "password"),
            ("user", "pass\nword"),
            ("u" * (portal.MAX_CREDENTIAL_BYTES + 1), "password"),
        )
        for username, password in values:
            with self.subTest(username=username[:20]):
                with self.assertRaises(portal.PortalError):
                    portal.PortalClient(username, password)

    def test_upload_binds_exact_name_type_bytes_and_user_managed_query(self) -> None:
        bundle = b"exact bundle bytes"
        digest = __import__("hashlib").sha256(bundle).hexdigest()
        observed = {}

        def transport(method, url, headers, content_length, chunks, deadline):
            body = b"".join(chunks)
            observed.update(
                method=method,
                url=url,
                headers=headers,
                content_length=content_length,
                body=body,
            )
            return response(201, url, "text/plain", DEPLOYMENT_ID.encode())

        client = portal.PortalClient("user", "password", transport=transport)
        result = client.upload(
            io.BytesIO(bundle),
            len(bundle),
            f"procwright-{VERSION}-maven-central-bundle.zip",
            self.identity,
            digest,
            portal.Deadline(60),
        )

        self.assertEqual(result, DEPLOYMENT_ID)
        self.assertEqual(observed["method"], "POST")
        self.assertIn("publishingType=USER_MANAGED", observed["url"])
        self.assertIn("name=io.github.ulviar%3Aprocwright%3A", observed["url"])
        self.assertIn(bundle, observed["body"])
        self.assertEqual(len(observed["body"]), observed["content_length"])
        self.assertNotIn(b"password", observed["body"])

        with self.assertRaisesRegex(portal.PortalError, "bundle identity"):
            client.upload(
                io.BytesIO(bundle),
                len(bundle),
                "procwright-9.9.9-maven-central-bundle.zip",
                self.identity,
                digest,
                portal.Deadline(60),
            )

    def test_status_rejects_missing_extra_and_wrong_identity_fields(self) -> None:
        valid = json.loads(status_payload(self.identity))
        mutations = []
        for key in tuple(valid):
            value = dict(valid)
            value.pop(key)
            mutations.append((f"missing {key}", value))
        value = dict(valid)
        value["repository"] = "unexpected"
        mutations.append(("extra", value))
        for key, wrong in (
            ("deploymentId", "223e4567-e89b-12d3-a456-426614174000"),
            ("deploymentName", "wrong"),
            ("deploymentState", "UNKNOWN"),
            ("deploymentState", []),
            ("purls", ["pkg:maven/example/evil@1.0.0"]),
            ("purls", "pkg:maven/example/evil@1.0.0"),
        ):
            value = dict(valid)
            value[key] = wrong
            mutations.append((f"wrong {key}", value))

        for name, value in mutations:
            with self.subTest(name=name):
                with self.assertRaises(portal.PortalError):
                    portal.parse_status(
                        json.dumps(value, separators=(",", ":")).encode(),
                        self.identity,
                        DEPLOYMENT_ID,
                    )

    def test_normalized_status_evidence_requires_every_exact_identity(self) -> None:
        digest = "a" * 64
        exact = portal.deployment_evidence(
            self.identity,
            DEPLOYMENT_ID,
            "VALIDATED",
            digest,
            self.identity.purls,
        )
        self.assertEqual(exact["namespace"], "io.github.ulviar")
        self.assertEqual(exact["publishingType"], "USER_MANAGED")
        self.assertEqual(exact["repository"], "https://repo.maven.apache.org/maven2/")
        self.assertEqual(exact["version"], VERSION)
        self.assertEqual(exact["deploymentName"], self.identity.deployment_name)

        for key, wrong in (
            ("deploymentId", "223e4567-e89b-12d3-a456-426614174000"),
            ("namespace", "example.invalid"),
            ("publishingType", "AUTOMATIC"),
            ("repository", "https://example.invalid/"),
            ("version", "9.9.9"),
            ("deploymentName", "wrong"),
            ("deploymentState", "PUBLISHED"),
        ):
            hostile = dict(exact)
            hostile[key] = wrong
            with self.subTest(key=key):
                with self.assertRaises(portal.PortalError):
                    portal.parse_deployment_evidence(
                        portal.deployment_evidence_bytes(hostile),
                        self.identity,
                        expected_deployment_id=DEPLOYMENT_ID,
                        expected_state="VALIDATED",
                        expected_bundle_sha256=digest,
                    )

    def test_status_rejects_duplicate_json_keys_and_oversized_evidence(self) -> None:
        duplicate = status_payload(self.identity).replace(
            b'{"deploymentId":', b'{"deploymentId":"duplicate","deploymentId":', 1
        )
        with self.assertRaisesRegex(portal.PortalError, "duplicate"):
            portal.parse_status(duplicate, self.identity, DEPLOYMENT_ID)
        with self.assertRaisesRegex(portal.PortalError, "size limit"):
            portal.parse_deployment_evidence(
                b"x" * (portal.MAX_RESPONSE_BYTES + 1),
                self.identity,
                expected_deployment_id=DEPLOYMENT_ID,
                expected_state="VALIDATED",
                expected_bundle_sha256="a" * 64,
            )

    def test_response_requires_exact_url_status_type_encoding_and_length(self) -> None:
        url = f"{portal.PORTAL_ORIGIN}{portal.STATUS_PATH}?id={DEPLOYMENT_ID}"
        body = status_payload(self.identity)
        cases = (
            response(200, "https://example.invalid", "application/json", body),
            response(201, url, "application/json", body),
            response(200, url, "text/plain", body),
            response(200, url, "application/json", body, **{"Content-Encoding": "gzip"}),
            response(200, url, "application/json", body, **{"Transfer-Encoding": "chunked"}),
            portal.ResponseData(
                200,
                url,
                (("Content-Length", str(len(body) + 1)), ("Content-Type", "application/json")),
                body,
            ),
        )
        for result in cases:
            with self.subTest(result=result):
                client = portal.PortalClient(
                    "user", "password", transport=lambda *_args, value=result: value
                )
                with self.assertRaises(portal.PortalError):
                    client.status(
                        self.identity, DEPLOYMENT_ID, portal.Deadline(60), attempts=1
                    )

    def test_status_retries_only_transient_failures_and_tls_is_permanent(self) -> None:
        calls = 0

        def transient(method, url, headers, content_length, chunks, deadline):
            nonlocal calls
            calls += 1
            return response(503, url, "application/json", b"unavailable")

        client = portal.PortalClient(
            "user", "password", transport=transient, sleeper=lambda _delay: None
        )
        with self.assertRaisesRegex(portal.PortalError, "after 3 attempts"):
            client.status(
                self.identity, DEPLOYMENT_ID, portal.Deadline(60), attempts=3
            )
        self.assertEqual(calls, 3)

        self.assertIsInstance(
            portal._network_error(ssl.SSLCertVerificationError("bad certificate")),
            portal.PortalError,
        )
        self.assertNotIsInstance(
            portal._network_error(ssl.SSLCertVerificationError("bad certificate")),
            portal._TransientPortalError,
        )

    def test_one_monotonic_deadline_includes_transport_time(self) -> None:
        now = [0.0]
        deadline = portal.Deadline(1.0, clock=lambda: now[0])

        def slow(method, url, headers, content_length, chunks, ignored_deadline):
            now[0] = 2.0
            body = status_payload(self.identity)
            return response(200, url, "application/json", body)

        client = portal.PortalClient("user", "password", transport=slow)
        with self.assertRaisesRegex(portal.PortalError, "deadline"):
            client.status(self.identity, DEPLOYMENT_ID, deadline, attempts=1)

    def test_failed_remote_diagnostics_are_never_copied_to_logs(self) -> None:
        secret = "CENTRAL_PASSWORD\x1b[31m"

        def failed(method, url, headers, content_length, chunks, deadline):
            body = status_payload(
                self.identity,
                "FAILED",
                errors=[{"errorDetail": secret, "reason": "raw response"}],
            )
            return response(200, url, "application/json", body)

        client = portal.PortalClient("user", "password", transport=failed)
        with self.assertRaises(portal.PortalError) as raised:
            client.wait_for_state(
                self.identity,
                DEPLOYMENT_ID,
                "VALIDATED",
                portal.Deadline(60),
                maximum_polls=1,
            )
        message = str(raised.exception)
        self.assertNotIn(secret, message)
        self.assertNotIn("errorDetail", message)
        self.assertNotIn("\x1b", message)


if __name__ == "__main__":
    unittest.main()
