import hashlib
import io
import json
import os
import ssl
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
import zipfile
from pathlib import Path
from unittest import mock


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import maven_central_repository as repository
import maven_release_evidence as evidence
import release_contract
import verify_maven_central_staged_bundle as release_bundle
from maven_central_portal import (
    PortalIdentity,
    deployment_evidence,
    deployment_evidence_bytes,
    parse_deployment_evidence,
)
from maven_release_test_fixtures import (
    COMMIT,
    DEPLOYMENT_ID,
    VERSION,
    _module_info_class,
    armored_signature,
    artifact_path,
    closed_release_payloads,
    jar_payload,
    pom_payload,
    refresh_base_sidecars,
    refresh_module_metadata,
    replace_base_payload,
    replace_raw_zip_name,
    write_bundle,
    zip_payload,
)
from release_contract import MODULES, expected_release_paths


def insert_unindexed_zip_bytes(payload: bytes, offset: int, inserted: bytes) -> bytes:
    archive = bytearray(payload)
    eocd_offset = archive.rfind(b"PK\x05\x06")
    if eocd_offset < 0:
        raise AssertionError("fixture ZIP has no EOCD")
    total_entries = struct.unpack_from("<H", archive, eocd_offset + 10)[0]
    central_offset = struct.unpack_from("<L", archive, eocd_offset + 16)[0]
    cursor = central_offset
    for _index in range(total_entries):
        if archive[cursor : cursor + 4] != b"PK\x01\x02":
            raise AssertionError("fixture ZIP has an invalid central directory")
        local_offset = struct.unpack_from("<L", archive, cursor + 42)[0]
        if local_offset >= offset:
            struct.pack_into("<L", archive, cursor + 42, local_offset + len(inserted))
        name_length, extra_length, comment_length = struct.unpack_from(
            "<3H", archive, cursor + 28
        )
        cursor += 46 + name_length + extra_length + comment_length
    if offset <= central_offset:
        struct.pack_into("<L", archive, eocd_offset + 16, central_offset + len(inserted))
    return bytes(archive[:offset] + inserted + archive[offset:])


class Response:
    def __init__(
        self,
        body: bytes,
        url: str,
        content_type: str,
        *,
        status: int = 200,
        headers: dict[str, str] | None = None,
        after_read=None,
    ):
        self._body = io.BytesIO(body)
        self._url = url
        self.status = status
        self.headers = {
            "Content-Length": str(len(body)),
            "Content-Type": content_type,
        }
        if headers is not None:
            self.headers = headers
        self._after_read = after_read
        self.read_calls = 0

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def geturl(self) -> str:
        return self._url

    def getcode(self) -> int:
        return self.status

    def read(self, size: int = -1) -> bytes:
        self.read_calls += 1
        result = self._body.read(size)
        if self._after_read is not None:
            self._after_read()
        return result


class FakePortalClient:
    def __init__(self, bundle_path: Path | None = None, *, swap: bool = False):
        self.bundle_path = bundle_path
        self.swap = swap
        self.uploaded = b""
        self.source_descriptor = None

    def upload(self, source, size, filename, identity, bundle_sha256, deadline):
        self.source_descriptor = source.fileno()
        source.seek(0)
        self.uploaded = source.read(size + 1)
        if self.swap and self.bundle_path is not None:
            replacement = self.bundle_path.with_suffix(".replacement")
            replacement.write_bytes(b"replacement")
            os.replace(replacement, self.bundle_path)
        self.assert_upload = (filename, identity, bundle_sha256)
        return DEPLOYMENT_ID

    def wait_for_state(self, identity, deployment_id, target_state, deadline):
        if deployment_id != DEPLOYMENT_ID or target_state not in {"VALIDATED", "PUBLISHED"}:
            raise AssertionError("unexpected fake Portal request")
        return target_state, identity.purls


class MavenCentralStagedBundleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.bundle = self.root / f"procwright-{VERSION}-maven-central-bundle.zip"
        self.payloads = closed_release_payloads()
        write_bundle(self.bundle, self.payloads)

    def manifest_path(self) -> Path:
        path = self.root / "manifest.json"
        release_bundle.write_manifest(self.bundle, path, VERSION)
        return path

    def bundle_digest(self) -> str:
        return release_bundle.build_manifest(self.bundle, VERSION)["bundle"]["sha256"]

    def _run_generate_manifest_cli(
        self, output_name: str
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPTS_DIRECTORY / "verify_maven_central_staged_bundle.py"),
                "generate-manifest",
                "--bundle",
                str(self.bundle),
                "--version",
                VERSION,
                "--output",
                str(self.root / output_name),
            ],
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )

    def opener(self, payloads=None, *, response_mutator=None):
        remote = self.payloads if payloads is None else payloads

        def open_request(request, timeout):
            self.assertGreater(timeout, 0)
            path = urllib.parse.unquote(
                urllib.parse.urlsplit(request.full_url).path.removeprefix("/maven2/")
            )
            response = Response(
                remote[path],
                request.full_url,
                repository.artifact_content_type(path, VERSION),
            )
            if response_mutator is not None:
                response_mutator(response, path)
            return response

        return open_request

    def test_valid_manifest_covers_the_exact_closed_release_set(self) -> None:
        first = release_bundle.build_manifest(self.bundle, VERSION)
        second = release_bundle.build_manifest(self.bundle, VERSION)

        self.assertEqual(first, second)
        self.assertEqual(
            tuple(entry["path"] for entry in first["files"]),
            expected_release_paths(VERSION),
        )
        self.assertEqual(len(first["files"]), 90)
        self.assertEqual(first["modules"], list(MODULES))
        self.assertIn("server-generated metadata", first["scope"])
        self.assertEqual(first["bundle"]["size"], self.bundle.stat().st_size)

    def test_rejects_three_arbitrary_jars_and_unknown_executable(self) -> None:
        arbitrary = {
            artifact_path(module, ".jar"): b"not a real jar" for module in MODULES
        }
        write_bundle(self.bundle, arbitrary)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "artifact set is not exact"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        hostile = dict(self.payloads)
        removed = artifact_path("procwright", ".module")
        hostile.pop(removed)
        hostile[removed.removesuffix(".module") + ".exe"] = b"MZ"
        write_bundle(self.bundle, hostile)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "artifact set is not exact"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_every_missing_artifact_role(self) -> None:
        missing = (
            artifact_path("procwright", "-sources.jar"),
            artifact_path("procwright", "-javadoc.jar"),
            artifact_path("procwright", ".pom") + ".asc",
            artifact_path("procwright", ".module") + ".sha512",
        )
        for path in missing:
            with self.subTest(path=path):
                payloads = dict(self.payloads)
                payloads.pop(path)
                write_bundle(self.bundle, payloads)
                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError, "artifact set is not exact"
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_unknown_classifier_and_case_collision(self) -> None:
        unknown = dict(self.payloads)
        removed = artifact_path("procwright", "-sources.jar")
        payload = unknown.pop(removed)
        unknown[artifact_path("procwright", "-tests.jar")] = payload
        write_bundle(self.bundle, unknown)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "artifact set is not exact"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        entries = list(self.payloads.items())
        first_path, first_payload = entries[0]
        entries.pop(1)
        entries.append((first_path.upper(), first_payload))
        self.bundle.write_bytes(
            zip_payload(
                [(path, body, stat.S_IFREG | 0o644) for path, body in entries]
            )
        )
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "case-colliding"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_fake_signature_and_corrupt_signature_crc(self) -> None:
        signature_path = artifact_path("procwright", ".pom") + ".asc"
        for signature in (
            b"signature fixture text",
            armored_signature().replace(b"=", b"=A", 1),
        ):
            with self.subTest(signature=signature[:20]):
                payloads = dict(self.payloads)
                payloads[signature_path] = signature
                write_bundle(self.bundle, payloads)
                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError, "armored signature"
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_wrong_checksum_and_sidecar_filename_escape(self) -> None:
        checksum_path = artifact_path("procwright", ".jar") + ".sha256"
        digest = self.payloads[checksum_path]
        for checksum in (b"0" * 64, digest + b"  ../../dev/zero"):
            with self.subTest(checksum=checksum[-20:]):
                payloads = dict(self.payloads)
                payloads[checksum_path] = checksum
                write_bundle(self.bundle, payloads)
                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError, "checksum"
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_fake_jars_pom_dependencies_repositories_and_module_metadata(self) -> None:
        mutations = []

        fake_jar = dict(self.payloads)
        replace_base_payload(fake_jar, artifact_path("procwright", ".jar"), b"PK fake")
        mutations.append(("JAR", fake_jar))

        repository_pom = dict(self.payloads)
        pom_path = artifact_path("procwright", ".pom")
        replacement = repository_pom[pom_path].replace(
            b"</project>",
            b"<repositories><repository/></repositories></project>",
        )
        replace_base_payload(repository_pom, pom_path, replacement)
        mutations.append(("POM contains repository", repository_pom))

        wrong_dependency = dict(self.payloads)
        integration_pom = artifact_path("procwright-integrations", ".pom")
        replacement = wrong_dependency[integration_pom].replace(b"2.22.0", b"9.99.0")
        replace_base_payload(wrong_dependency, integration_pom, replacement)
        mutations.append(("POM dependencies", wrong_dependency))

        wrong_module = dict(self.payloads)
        module_path = artifact_path("procwright", ".module")
        metadata = json.loads(wrong_module[module_path])
        metadata["repositories"] = []
        replace_base_payload(
            wrong_module,
            module_path,
            json.dumps(metadata, separators=(",", ":")).encode(),
        )
        mutations.append(("module metadata schema", wrong_module))

        malformed_module = dict(self.payloads)
        metadata = json.loads(malformed_module[module_path])
        metadata["createdBy"]["gradle"]["version"] = []
        replace_base_payload(
            malformed_module,
            module_path,
            json.dumps(metadata, separators=(",", ":")).encode(),
        )
        mutations.append(("module producer identity", malformed_module))

        malformed_dependency = dict(self.payloads)
        integration_module_path = artifact_path("procwright-integrations", ".module")
        metadata = json.loads(malformed_dependency[integration_module_path])
        metadata["variants"][0]["dependencies"][0]["version"]["requires"] = []
        replace_base_payload(
            malformed_dependency,
            integration_module_path,
            json.dumps(metadata, separators=(",", ":")).encode(),
        )
        mutations.append(("module dependency schema", malformed_dependency))

        for expected, payloads in mutations:
            with self.subTest(expected=expected):
                write_bundle(self.bundle, payloads)
                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError, expected
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_pom_requires_maven_namespace_on_every_structural_and_field_element(self) -> None:
        base = pom_payload("procwright-integrations")
        mutations = {
            "top-level field": (
                b"<name>Procwright Integrations</name>",
                b'<evil:name xmlns:evil="urn:evil">Procwright Integrations</evil:name>',
            ),
            "license container": (
                b"<licenses>",
                b'<evil:licenses xmlns:evil="urn:evil">',
            ),
            "license field": (
                b"<name>Apache License, Version 2.0</name>",
                b'<evil:name xmlns:evil="urn:evil">Apache License, Version 2.0</evil:name>',
            ),
            "developer container": (
                b"<developers>",
                b'<evil:developers xmlns:evil="urn:evil">',
            ),
            "developer field": (
                b"<id>Ulviar</id>",
                b'<evil:id xmlns:evil="urn:evil">Ulviar</evil:id>',
            ),
            "SCM container": (b"<scm>", b'<evil:scm xmlns:evil="urn:evil">'),
            "SCM field": (
                b"<connection>",
                b'<evil:connection xmlns:evil="urn:evil">',
            ),
            "dependency element": (
                b"<dependency>",
                b'<evil:dependency xmlns:evil="urn:evil">',
            ),
            "dependency field": (
                b"<groupId>io.github.ulviar</groupId>",
                b'<evil:groupId xmlns:evil="urn:evil">io.github.ulviar</evil:groupId>',
            ),
        }
        for label, (old, new) in mutations.items():
            with self.subTest(element=label):
                hostile = base.replace(old, new, 1)
                if old.startswith(b"<") and b"</" not in old and old.endswith(b">"):
                    tag = old[1:-1]
                    hostile = hostile.replace(
                        b"</" + tag + b">", b"</evil:" + tag + b">", 1
                    )
                self.assertNotEqual(hostile, base)
                with self.assertRaisesRegex(
                    evidence.EvidenceError, "exact Maven namespace"
                ):
                    evidence.validate_pom(
                        hostile, "procwright-integrations", VERSION
                    )

    def test_kotlin_pom_rejects_plugin_dependency_management(self) -> None:
        base = pom_payload("procwright-kotlin")
        hostile = base.replace(
            b"</project>",
            b"<dependencyManagement><dependencies/></dependencyManagement></project>",
        )
        with self.assertRaisesRegex(
            evidence.EvidenceError, "repository or inherited dependency policy"
        ):
            evidence.validate_pom(hostile, "procwright-kotlin", VERSION)

    def test_kotlin_module_rejects_constraints_and_versionless_dependencies(self) -> None:
        module_path = artifact_path("procwright-kotlin", ".module")
        for label in ("constraints", "versionless"):
            with self.subTest(case=label):
                metadata = json.loads(self.payloads[module_path])
                variant = metadata["variants"][0]
                if label == "constraints":
                    variant["dependencyConstraints"] = []
                else:
                    variant["dependencies"][0].pop("version")
                with self.assertRaises(evidence.EvidenceError):
                    evidence.validate_module_metadata(
                        json.dumps(metadata, separators=(",", ":")).encode(),
                        "procwright-kotlin",
                        VERSION,
                        self.payloads,
                    )

    def test_compiled_module_descriptor_rejects_every_hidden_semantic_field(self) -> None:
        contract = release_contract.module_semantic_contract("procwright")
        cases = {
            "open module": {"module_flags": 0x20},
            "module version": {"module_version": "9.9.9"},
            "opens": {
                "opens": (("io.github.ulviar.procwright", 0, ()),)
            },
            "uses": {"uses": ("io.github.ulviar.procwright.HiddenService",)},
            "provides": {
                "provides": (
                    (
                        "io.github.ulviar.procwright.HiddenService",
                        ("io.github.ulviar.procwright.HiddenProvider",),
                    ),
                )
            },
        }
        for label, options in cases.items():
            with self.subTest(field=label):
                descriptor = _module_info_class(
                    contract.jpms_name,
                    contract.exports,
                    contract.requires,
                    requires_versions=contract.requires_versions,
                    **options,
                )
                hostile = self._replace_jar_entry(
                    jar_payload("procwright", ".jar"),
                    "module-info.class",
                    descriptor,
                )
                with self.assertRaisesRegex(
                    evidence.EvidenceError, "module descriptor"
                ):
                    evidence.validate_jar(hostile, "procwright", ".jar")

    def test_source_module_descriptor_rejects_open_opens_uses_and_provides(self) -> None:
        base = jar_payload("procwright", "-sources.jar")
        with zipfile.ZipFile(io.BytesIO(base)) as archive:
            source = archive.read("module-info.java").decode("utf-8")
        cases = {
            "open module": source.replace("module ", "open module ", 1),
            "opens": source.replace("}", " opens io.github.ulviar.procwright; }"),
            "uses": source.replace(
                "}", " uses io.github.ulviar.procwright.HiddenService; }"
            ),
            "provides": source.replace(
                "}",
                " provides io.github.ulviar.procwright.HiddenService with "
                "io.github.ulviar.procwright.HiddenProvider; }",
            ),
        }
        for label, descriptor in cases.items():
            with self.subTest(field=label):
                hostile = self._replace_jar_entry(
                    base, "module-info.java", descriptor.encode("utf-8")
                )
                with self.assertRaisesRegex(
                    evidence.EvidenceError, "module descriptor"
                ):
                    evidence.validate_jar(hostile, "procwright", "-sources.jar")

    @staticmethod
    def _replace_jar_entry(payload: bytes, name: str, replacement: bytes) -> bytes:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            entries = [
                (
                    info.filename,
                    replacement if info.filename == name else archive.read(info),
                    stat.S_IFREG | 0o644,
                )
                for info in archive.infolist()
            ]
        return zip_payload(entries)

    def test_rejects_cross_module_classifier_swaps_after_all_hashes_are_rebuilt(self) -> None:
        for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
            with self.subTest(suffix=suffix):
                payloads = dict(self.payloads)
                core = artifact_path("procwright", suffix)
                integrations = artifact_path("procwright-integrations", suffix)
                payloads[core], payloads[integrations] = (
                    payloads[integrations],
                    payloads[core],
                )
                refresh_base_sidecars(payloads, core)
                refresh_base_sidecars(payloads, integrations)
                refresh_module_metadata(payloads, "procwright")
                refresh_module_metadata(payloads, "procwright-integrations")
                write_bundle(self.bundle, payloads)

                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError,
                    "semantic identity|module identity|package identity|documentation identity",
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_production_cli_rejects_irrelevant_documentation_with_a_role_anchor(self) -> None:
        cases = {
            "procwright": zip_payload(
                [
                    ("index.html", b"API", stat.S_IFREG | 0o644),
                    (
                        "element-list",
                        b"io.example.unrelated\n",
                        stat.S_IFREG | 0o644,
                    ),
                    (
                        "io/github/ulviar/procwright/Procwright.html",
                        b"anchor only",
                        stat.S_IFREG | 0o644,
                    ),
                ]
            ),
            "procwright-kotlin": zip_payload(
                [
                    ("index.html", b"API", stat.S_IFREG | 0o644),
                    (
                        "procwright-kotlin/package-list",
                        b"$dokka.format:html-v1\n$dokka.linkExtension:html\n$dokka.location:io.example.unrelated////PointingToDeclaration/\x1fprocwright-kotlin/io.example.unrelated/index.html\nio.github.ulviar.procwright.kotlin\n",
                        stat.S_IFREG | 0o644,
                    ),
                    (
                        "procwright-kotlin/io.github.ulviar.procwright.kotlin/index.html",
                        b"anchor only",
                        stat.S_IFREG | 0o644,
                    ),
                ]
            ),
        }
        for module, documentation in cases.items():
            with self.subTest(module=module):
                payloads = dict(self.payloads)
                path = artifact_path(module, "-javadoc.jar")
                replace_base_payload(payloads, path, documentation)
                refresh_module_metadata(payloads, module)
                write_bundle(self.bundle, payloads)
                self.assertNotEqual(
                    self._run_generate_manifest_cli(f"{module}-docs.json").returncode,
                    0,
                )

    def test_production_cli_rejects_rehashed_module_swap_and_pom_metadata_drift(self) -> None:
        swapped = dict(self.payloads)
        core = artifact_path("procwright", ".jar")
        integrations = artifact_path("procwright-integrations", ".jar")
        swapped[core], swapped[integrations] = swapped[integrations], swapped[core]
        for path in (core, integrations):
            refresh_base_sidecars(swapped, path)
        for module in ("procwright", "procwright-integrations"):
            refresh_module_metadata(swapped, module)
        write_bundle(self.bundle, swapped)
        self.assertNotEqual(self._run_generate_manifest_cli("swap.json").returncode, 0)

        metadata_drift = dict(self.payloads)
        pom = artifact_path("procwright", ".pom")
        replace_base_payload(
            metadata_drift,
            pom,
            metadata_drift[pom].replace(
                b"<name>Procwright</name>", b"<name>Other</name>", 1
            ),
        )
        write_bundle(self.bundle, metadata_drift)
        self.assertNotEqual(self._run_generate_manifest_cli("pom.json").returncode, 0)

    def test_rejects_every_exact_pom_metadata_mutation(self) -> None:
        pom_path = artifact_path("procwright", ".pom")
        mutations = (
            (b"<name>Procwright</name>", b"<name>Other</name>"),
            (
                b"<name>Procwright</name>",
                b'<name data-extra="true">Procwright</name>',
            ),
            (
                b"<name>Procwright</name>",
                b"<name>Procwright<extra/></name>",
            ),
            (
                b"https://maven.apache.org/xsd/maven-4.0.0.xsd",
                b"https://example.invalid/maven.xsd",
            ),
            (
                b"<description>Scenario-first JVM library for safe external CLI execution and interactive process workflows.</description>",
                b"<description>Other</description>",
            ),
            (
                b"<url>https://github.com/Ulviar/Procwright</url>",
                b"<url>https://example.invalid</url>",
            ),
            (b"<name>Apache License, Version 2.0</name>", b"<name>Apache-2.0</name>"),
            (
                b"<url>https://www.apache.org/licenses/LICENSE-2.0</url>",
                b"<url>https://example.invalid/license</url>",
            ),
            (b"<id>Ulviar</id>", b"<id>other</id>"),
            (b"<name>Ulviar</name>", b"<name>Other</name>"),
            (
                b"<connection>scm:git:https://github.com/Ulviar/Procwright.git</connection>",
                b"<connection>scm:git:https://example.invalid/repository.git</connection>",
            ),
            (
                b"<developerConnection>scm:git:https://github.com/Ulviar/Procwright.git</developerConnection>",
                b"<developerConnection>scm:git:ssh://example.invalid/repository.git</developerConnection>",
            ),
            (
                b"<url>https://github.com/Ulviar/Procwright</url></scm>",
                b"<url>https://example.invalid/repository</url></scm>",
            ),
        )
        for old, new in mutations:
            with self.subTest(old=old):
                payloads = dict(self.payloads)
                self.assertIn(old, payloads[pom_path])
                replace_base_payload(
                    payloads, pom_path, payloads[pom_path].replace(old, new, 1)
                )
                write_bundle(self.bundle, payloads)
                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError, "POM metadata"
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_whitespace_wrapped_pom_dependency_values(self) -> None:
        pom_path = artifact_path("procwright-integrations", ".pom")
        for field in ("groupId", "artifactId", "version", "scope"):
            with self.subTest(field=field):
                payloads = dict(self.payloads)
                marker = f"<{field}>".encode()
                dependency_offset = payloads[pom_path].index(b"<dependencies>")
                field_offset = payloads[pom_path].index(marker, dependency_offset)
                replace_base_payload(
                    payloads,
                    pom_path,
                    payloads[pom_path][: field_offset + len(marker)]
                    + b" "
                    + payloads[pom_path][field_offset + len(marker) :],
                )
                write_bundle(self.bundle, payloads)

                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError,
                    "POM dependency",
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_unindexed_prefix_gaps_and_header_like_bytes(self) -> None:
        original = self.bundle.read_bytes()
        entries = evidence.raw_central_directory(
            io.BytesIO(original),
            len(original),
            maximum_entries=len(expected_release_paths(VERSION)),
            maximum_directory_bytes=evidence.MAX_CENTRAL_DIRECTORY_BYTES,
            maximum_entry_bytes=evidence.MAX_ENTRY_BYTES,
            maximum_total_compressed_bytes=evidence.MAX_TOTAL_COMPRESSED_BYTES,
            maximum_total_bytes=evidence.MAX_TOTAL_UNCOMPRESSED_BYTES,
            allow_directories=False,
        )
        second_local_offset = sorted(entry.local_header_offset for entry in entries)[1]
        for label, offset, inserted in (
            ("prefix", 0, b"unindexed-prefix"),
            ("gap", second_local_offset, b"unindexed-gap"),
            ("header-like", second_local_offset, b"PK\x03\x04unindexed"),
        ):
            with self.subTest(label=label):
                self.bundle.write_bytes(
                    insert_unindexed_zip_bytes(original, offset, inserted)
                )

                with self.assertRaisesRegex(
                    release_bundle.BundleVerificationError,
                    "continuous|unindexed",
                ):
                    release_bundle.build_manifest(self.bundle, VERSION)

    def test_rejects_outer_count_long_raw_nul_and_compression_bomb_before_materialization(self) -> None:
        too_many = dict(self.payloads)
        too_many["evil.exe"] = b"MZ"
        write_bundle(self.bundle, too_many)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "too many entries"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        long_name = dict(self.payloads)
        long_name.pop(next(iter(long_name)))
        long_name["a" * 600] = b"x"
        write_bundle(self.bundle, long_name)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "name length"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        checksum_path = artifact_path("procwright", ".jar") + ".sha512"
        raw = self.bundle_bytes(self.payloads)
        hostile_name = checksum_path[:-1] + "\x00"
        self.bundle.write_bytes(replace_raw_zip_name(raw, checksum_path, hostile_name))
        with self.assertRaisesRegex(release_bundle.BundleVerificationError, "NUL"):
            release_bundle.build_manifest(self.bundle, VERSION)

        non_nfc = dict(self.payloads)
        original_path = next(iter(non_nfc))
        original_payload = non_nfc.pop(original_path)
        directory = original_path.rsplit("/", 1)[0]
        non_nfc[f"{directory}/e\u0301vil"] = original_payload
        write_bundle(self.bundle, non_nfc)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "canonical NFC"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        bomb = dict(self.payloads)
        bomb[artifact_path("procwright", ".pom") + ".asc"] = b"0" * (2 * 1024 * 1024)
        write_bundle(self.bundle, bomb)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "compression-ratio"
        ):
            release_bundle.build_manifest(self.bundle, VERSION)

        write_bundle(self.bundle, self.payloads)
        with mock.patch.object(
            evidence, "MAX_BUNDLE_BYTES", self.bundle.stat().st_size - 1
        ):
            with self.assertRaisesRegex(
                release_bundle.BundleVerificationError, "size limit"
            ):
                release_bundle.build_manifest(self.bundle, VERSION)

    @staticmethod
    def bundle_bytes(payloads: dict[str, bytes]) -> bytes:
        return zip_payload(
            [
                (path, body, stat.S_IFREG | 0o644)
                for path, body in payloads.items()
            ]
        )

    def test_rejects_nested_jar_nul_case_collision_bomb_and_count(self) -> None:
        class_path = "io/github/Example.class"
        normal = zip_payload([(class_path, b"class", stat.S_IFREG | 0o644)])
        nul_name = class_path[:-1] + "\x00"
        with self.assertRaisesRegex(evidence.EvidenceError, "NUL"):
            evidence.validate_jar(
                replace_raw_zip_name(normal, class_path, nul_name), "procwright", ".jar"
            )

        collision = zip_payload(
            [
                ("A.class", b"class", stat.S_IFREG | 0o644),
                ("a.class", b"class", stat.S_IFREG | 0o644),
            ]
        )
        with self.assertRaisesRegex(evidence.EvidenceError, "case-colliding"):
            evidence.validate_jar(collision, "procwright", ".jar")

        bomb = zip_payload(
            [("Bomb.class", b"0" * (2 * 1024 * 1024), stat.S_IFREG | 0o644)]
        )
        with self.assertRaisesRegex(evidence.EvidenceError, "compression-ratio"):
            evidence.validate_jar(bomb, "procwright", ".jar")

        with mock.patch.object(evidence, "MAX_NESTED_ENTRIES", 1):
            with self.assertRaisesRegex(evidence.EvidenceError, "too many entries"):
                evidence.validate_jar(collision, "procwright", ".jar")

    def test_manifest_output_and_input_are_bounded(self) -> None:
        with mock.patch.object(evidence, "MAX_MANIFEST_BYTES", 100):
            with self.assertRaisesRegex(
                release_bundle.BundleVerificationError, "manifest.*size limit"
            ):
                release_bundle.build_manifest(self.bundle, VERSION)

        oversized = self.root / "oversized.json"
        oversized.write_bytes(b"x" * 11)
        with mock.patch.object(release_bundle, "MAX_MANIFEST_BYTES", 10):
            with self.assertRaisesRegex(
                release_bundle.BundleVerificationError, "size limit"
            ):
                release_bundle._read_manifest(oversized, VERSION)

        malformed = release_bundle.build_manifest(self.bundle, VERSION)
        malformed["bundle"]["sha256"] = []
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "bundle digest"
        ):
            release_bundle._parse_manifest(
                json.dumps(malformed, sort_keys=True, separators=(",", ":")).encode()
                + b"\n",
                VERSION,
            )

    def test_rejects_symlink_hardlink_fifo_and_dev_zero_evidence(self) -> None:
        link = self.root / "bundle-link.zip"
        try:
            link.symlink_to(self.bundle)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"symlinks unavailable: {error}")
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "regular non-symlink"
        ):
            release_bundle.build_manifest(link, VERSION)

        hardlink = self.root / "bundle-hardlink.zip"
        try:
            os.link(self.bundle, hardlink)
        except OSError as error:
            self.skipTest(f"hardlinks unavailable: {error}")
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "exactly one filesystem link"
        ):
            release_bundle.build_manifest(hardlink, VERSION)
        hardlink.unlink()

        if hasattr(os, "mkfifo"):
            fifo = self.root / "evidence.fifo"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(evidence.EvidenceError, "regular non-symlink"):
                evidence.read_stable_file(fifo, "FIFO evidence", 16)
        if Path("/dev/zero").exists():
            with self.assertRaisesRegex(
                release_bundle.BundleVerificationError, "regular non-symlink"
            ):
                release_bundle._verify_checksum_file(
                    self.bundle, Path("/dev/zero"), "0" * 64
                )

    def test_stable_file_detects_path_replacement_after_open(self) -> None:
        with self.assertRaisesRegex(evidence.EvidenceError, "replaced while open"):
            with evidence.open_stable_regular_file(
                self.bundle, "bundle", evidence.MAX_BUNDLE_BYTES
            ):
                replacement = self.root / "replacement.zip"
                replacement.write_bytes(b"replacement")
                os.replace(replacement, self.bundle)

    def test_atomic_stage_uploads_the_verified_fd_and_persists_bound_evidence(self) -> None:
        client = FakePortalClient(self.bundle)
        manifest = self.root / "manifest.json"
        checksum = self.root / f"{self.bundle.name}.sha256"
        deployment = self.root / "deployment.json"

        result = release_bundle.stage_release(
            self.bundle,
            manifest,
            checksum,
            deployment,
            VERSION,
            COMMIT,
            client=client,
            deadline_seconds=60,
        )

        self.assertEqual(client.uploaded, self.bundle.read_bytes())
        digest = hashlib.sha256(client.uploaded).hexdigest()
        self.assertEqual(result["bundleSha256"], digest)
        self.assertEqual(checksum.read_bytes(), f"{digest}  {self.bundle.name}\n".encode())
        self.assertEqual(stat.S_IMODE(self.bundle.stat().st_mode), 0o600)
        for path in (manifest, checksum, deployment):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        parsed = parse_deployment_evidence(
            deployment.read_bytes(),
            PortalIdentity(VERSION, COMMIT),
            expected_deployment_id=DEPLOYMENT_ID,
            expected_state="VALIDATED",
            expected_bundle_sha256=digest,
        )
        self.assertEqual(parsed["deploymentId"], DEPLOYMENT_ID)

    def test_atomic_stage_rejects_bundle_swap_without_persisting_evidence(self) -> None:
        client = FakePortalClient(self.bundle, swap=True)
        outputs = (
            self.root / "manifest.json",
            self.root / "bundle.sha256",
            self.root / "deployment.json",
        )
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "changed or was replaced"
        ):
            release_bundle.stage_release(
                self.bundle,
                *outputs,
                VERSION,
                COMMIT,
                client=client,
                deadline_seconds=60,
            )
        self.assertTrue(client.uploaded)
        self.assertTrue(all(not path.exists() for path in outputs))

    def test_credentials_require_an_owner_only_regular_file(self) -> None:
        credentials = self.root / "credentials"
        credentials.write_bytes(b"user\0password\0")
        credentials.chmod(0o644)
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "owner-only"
        ):
            release_bundle._read_credentials(credentials)
        credentials.chmod(0o600)
        self.assertIsNotNone(release_bundle._read_credentials(credentials))

        hardlink = self.root / "credentials-hardlink"
        try:
            os.link(credentials, hardlink)
        except OSError as error:
            self.skipTest(f"hardlinks unavailable: {error}")
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "filesystem link"
        ):
            release_bundle._read_credentials(credentials)
        hardlink.unlink()

        credentials.unlink()
        target = self.root / "credentials-target"
        target.write_bytes(b"user\0password\0")
        target.chmod(0o600)
        try:
            credentials.symlink_to(target)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"symlinks unavailable: {error}")
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "regular non-symlink"
        ):
            release_bundle._read_credentials(credentials)

    def test_downloads_and_compares_all_90_payloads_with_exact_headers(self) -> None:
        destination = self.root / "downloads"
        release_bundle.verify_published_payloads(
            self.bundle,
            self.manifest_path(),
            VERSION,
            destination,
            opener=self.opener(),
            attempts=1,
            retry_delay_seconds=0,
            overall_deadline_seconds=60,
        )
        self.assertEqual(len(list(destination.iterdir())), 90)

    def test_publication_poll_and_downloads_share_one_exhaustible_deadline(self) -> None:
        manifest, checksum, deployment = self._validated_publication_evidence()
        now = [0.0]
        downloads = 0

        class ExhaustingPortal:
            def wait_for_state(inner_self, identity, deployment_id, target_state, deadline):
                self.assertGreater(deadline.remaining(), 0)
                now[0] = 61.0
                return "PUBLISHED", identity.purls

        def opener(_request, _timeout):
            nonlocal downloads
            downloads += 1
            raise AssertionError("download must not start after poll exhausted the deadline")

        destination = self.root / "deadline-exhausted-downloads"
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "deadline expired"
        ):
            release_bundle.wait_and_verify_publication(
                self.bundle,
                manifest,
                checksum,
                deployment,
                VERSION,
                COMMIT,
                DEPLOYMENT_ID,
                self.bundle_digest(),
                destination,
                client=ExhaustingPortal(),
                opener=opener,
                deadline_seconds=60,
                clock=lambda: now[0],
            )
        self.assertEqual(downloads, 0)
        self.assertFalse(destination.exists())

    def test_download_retries_consume_the_same_post_poll_deadline(self) -> None:
        manifest, checksum, deployment = self._validated_publication_evidence()
        now = [0.0]
        downloads = 0

        class HalfBudgetPortal:
            def wait_for_state(inner_self, identity, deployment_id, target_state, deadline):
                self.assertGreater(deadline.remaining(), 0)
                now[0] = 30.0
                return "PUBLISHED", identity.purls

        def transient(request, timeout):
            nonlocal downloads
            downloads += 1
            raise urllib.error.HTTPError(request.full_url, 404, "not ready", {}, None)

        def consume_remaining_budget(_delay: float) -> None:
            now[0] = 61.0

        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "deadline"
        ):
            release_bundle.wait_and_verify_publication(
                self.bundle,
                manifest,
                checksum,
                deployment,
                VERSION,
                COMMIT,
                DEPLOYMENT_ID,
                self.bundle_digest(),
                self.root / "retry-deadline-downloads",
                client=HalfBudgetPortal(),
                opener=transient,
                attempts=12,
                retry_delay_seconds=1,
                sleeper=consume_remaining_budget,
                deadline_seconds=60,
                clock=lambda: now[0],
            )
        self.assertEqual(downloads, 1)

    def test_publication_rejects_bundle_not_matching_carried_staging_digest(self) -> None:
        manifest, checksum, deployment = self._validated_publication_evidence()
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "carried from artifact verification"
        ):
            release_bundle.wait_and_verify_publication(
                self.bundle,
                manifest,
                checksum,
                deployment,
                VERSION,
                COMMIT,
                DEPLOYMENT_ID,
                "0" * 64,
                self.root / "wrong-carried-digest-downloads",
                client=FakePortalClient(self.bundle),
            )

    def _validated_publication_evidence(self) -> tuple[Path, Path, Path]:
        manifest = self.manifest_path()
        digest = release_bundle.build_manifest(self.bundle, VERSION)["bundle"]["sha256"]
        checksum = self.root / "bundle.sha256"
        checksum.write_bytes(f"{digest}  {self.bundle.name}\n".encode("ascii"))
        deployment = self.root / "deployment.json"
        identity = PortalIdentity(VERSION, COMMIT)
        deployment.write_bytes(
            deployment_evidence_bytes(
                deployment_evidence(
                    identity,
                    DEPLOYMENT_ID,
                    "VALIDATED",
                    digest,
                    identity.purls,
                )
            )
        )
        return manifest, checksum, deployment
        for path, expected in self.payloads.items():
            self.assertEqual((destination / Path(path).name).read_bytes(), expected)

    def test_repository_content_types_match_canonical_maven_central_roles(self) -> None:
        expected = {
            ".jar": "application/java-archive",
            ".pom": "text/xml",
            ".module": "application/vnd.org.gradle.module+json",
            ".jar.asc": "text/plain",
            ".jar.sha256": "text/plain",
        }
        for suffix, content_type in expected.items():
            with self.subTest(suffix=suffix):
                self.assertEqual(
                    repository.artifact_content_type(
                        artifact_path("procwright", suffix), VERSION
                    ),
                    content_type,
                )

    def test_download_rejects_headers_length_encoding_redirect_and_byte_mismatch(self) -> None:
        cases = {
            "content-type": lambda response, _path: response.headers.update(
                {"Content-Type": "text/html"}
            ),
            "content-length": lambda response, _path: response.headers.update(
                {"Content-Length": str(int(response.headers["Content-Length"]) + 1)}
            ),
            "content-encoding": lambda response, _path: response.headers.update(
                {"Content-Encoding": "gzip"}
            ),
            "redirect": lambda response, _path: setattr(
                response, "_url", "https://example.invalid/file"
            ),
        }
        manifest = self.manifest_path()
        for name, mutation in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(release_bundle.BundleVerificationError):
                    release_bundle.verify_published_payloads(
                        self.bundle,
                        manifest,
                        VERSION,
                        self.root / f"downloads-{name}",
                        opener=self.opener(response_mutator=mutation),
                        attempts=1,
                        retry_delay_seconds=0,
                        overall_deadline_seconds=60,
                    )

        altered = dict(self.payloads)
        first = next(iter(altered))
        altered[first] = b"x" * len(altered[first])
        with self.assertRaisesRegex(
            release_bundle.BundleVerificationError, "differs from the exact staged bytes"
        ):
            release_bundle.verify_published_payloads(
                self.bundle,
                manifest,
                VERSION,
                self.root / "downloads-mismatch",
                opener=self.opener(altered),
                attempts=1,
                retry_delay_seconds=0,
                overall_deadline_seconds=60,
            )

    def test_repository_retry_policy_deadline_tls_and_escaped_url(self) -> None:
        path = artifact_path("procwright", ".pom")
        payload = self.payloads[path]
        self.assertIn("%2Bbuild.7", repository.canonical_artifact_url(path, VERSION))

        calls = 0

        def permanent(request, timeout):
            nonlocal calls
            calls += 1
            raise urllib.error.HTTPError(request.full_url, 403, "raw secret", {}, None)

        with self.assertRaisesRegex(repository.RepositoryEvidenceError, "status 403"):
            repository.download_artifact(
                path,
                VERSION,
                maximum_size=len(payload),
                opener=permanent,
                attempts=3,
                retry_delay_seconds=0,
                deadline=repository.checked_deadline(60),
            )
        self.assertEqual(calls, 1)

        calls = 0

        def transient(request, timeout):
            nonlocal calls
            calls += 1
            raise urllib.error.HTTPError(request.full_url, 404, "raw secret", {}, None)

        with self.assertRaisesRegex(repository.RepositoryEvidenceError, "after 3 attempts"):
            repository.download_artifact(
                path,
                VERSION,
                maximum_size=len(payload),
                opener=transient,
                attempts=3,
                retry_delay_seconds=0,
                deadline=repository.checked_deadline(60),
            )
        self.assertEqual(calls, 3)

        calls = 0

        def bad_tls(request, timeout):
            nonlocal calls
            calls += 1
            raise urllib.error.URLError(ssl.SSLCertVerificationError("bad certificate"))

        with self.assertRaisesRegex(repository.RepositoryEvidenceError, "TLS"):
            repository.download_artifact(
                path,
                VERSION,
                maximum_size=len(payload),
                opener=bad_tls,
                attempts=3,
                retry_delay_seconds=0,
                deadline=repository.checked_deadline(60),
            )
        self.assertEqual(calls, 1)

        now = [0.0]
        deadline = repository.checked_deadline(1.0, clock=lambda: now[0])

        def slow(request, timeout):
            return Response(
                payload,
                request.full_url,
                repository.artifact_content_type(path, VERSION),
                after_read=lambda: now.__setitem__(0, 2.0),
            )

        with self.assertRaisesRegex(repository.RepositoryEvidenceError, "deadline"):
            repository.download_artifact(
                path,
                VERSION,
                maximum_size=len(payload),
                opener=slow,
                attempts=1,
                retry_delay_seconds=0,
                deadline=deadline,
            )


if __name__ == "__main__":
    unittest.main()
