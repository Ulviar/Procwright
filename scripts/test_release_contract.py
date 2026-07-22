import sys
import unittest
from pathlib import Path


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import release_contract


class ReleaseContractTest(unittest.TestCase):
    def test_kotlin_publication_disables_implicit_dependency_metadata(self) -> None:
        repository = SCRIPTS_DIRECTORY.parent
        properties = (repository / "gradle.properties").read_text(encoding="utf-8")
        self.assertIn("kotlin.stdlib.default.dependency=false\n", properties)
        self.assertIn(
            "kotlin.stdlib.jdk.variants.version.alignment=false\n", properties
        )
        kotlin_build = (repository / "procwright-kotlin/build.gradle.kts").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            'api("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")', kotlin_build
        )
        self.assertNotIn('api(kotlin("stdlib"))', kotlin_build)

    def test_accepts_canonical_semver(self) -> None:
        for version in (
            "0.1.0",
            "1.2.3-rc.1",
            "1.2.3-alpha.beta-1",
            "1.2.3+build.7",
            "1.2.3-rc.1+build.7",
        ):
            with self.subTest(version=version):
                self.assertEqual(release_contract.require_canonical_version(version), version)

    def test_rejects_noncanonical_or_path_unsafe_versions(self) -> None:
        invalid = (
            "",
            "v1.2.3",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "1.2",
            "1.2.3-",
            "1.2.3-01",
            "1.2.3-alpha..1",
            "1.2.3+",
            "1.2.3+build..1",
            "1.2.3/../../outside",
            "1.2.3\\outside",
            "1.2.3\nnext",
            "1.2.3$(id)",
            "1.2.3+" + "a" * 129,
        )
        for version in invalid:
            with self.subTest(version=version):
                with self.assertRaises(release_contract.ReleaseContractError):
                    release_contract.require_canonical_version(version)

    def test_requires_lowercase_full_commit_sha(self) -> None:
        valid = "0123456789abcdef0123456789abcdef01234567"
        self.assertEqual(release_contract.require_commit_sha(valid), valid)
        for value in ("", valid[:-1], valid.upper(), "g" * 40, valid + "\n"):
            with self.subTest(value=value):
                with self.assertRaises(release_contract.ReleaseContractError):
                    release_contract.require_commit_sha(value)

    def test_derives_one_closed_release_artifact_set(self) -> None:
        artifacts = release_contract.expected_release_artifacts("1.2.3")
        self.assertEqual(len(artifacts), 90)
        self.assertEqual(len({artifact.path for artifact in artifacts}), 90)
        self.assertEqual(
            {artifact.role for artifact in artifacts},
            {"base", "signature", "checksum"},
        )
        self.assertEqual(
            len([artifact for artifact in artifacts if artifact.role == "base"]), 15
        )
        self.assertEqual(len(release_contract.expected_generated_metadata_paths()), 15)

    def test_release_path_cli_emits_the_stable_canonical_contract(self) -> None:
        from contextlib import redirect_stdout
        from io import StringIO

        output = StringIO()
        with redirect_stdout(output):
            result = release_contract.main(["release-paths", "1.2.3"])
        self.assertEqual(result, 0)
        self.assertEqual(
            tuple(output.getvalue().splitlines()),
            release_contract.expected_release_paths("1.2.3"),
        )

    def test_derives_exact_release_identity_and_dependencies(self) -> None:
        commit = "0123456789abcdef0123456789abcdef01234567"
        self.assertEqual(
            release_contract.deployment_name("1.2.3", commit),
            f"{release_contract.GROUP_ID}:procwright:1.2.3:{commit}",
        )
        self.assertEqual(len(release_contract.expected_purls("1.2.3")), 3)
        self.assertIn(
            (release_contract.GROUP_ID, "procwright", "1.2.3", "compile"),
            release_contract.expected_pom_dependencies(
                "procwright-integrations", "1.2.3"
            ),
        )
        self.assertEqual(
            release_contract.expected_pom_dependencies("procwright", "1.2.3"),
            (("org.jspecify", "jspecify", "1.0.0", "compile"),),
        )
        self.assertEqual(
            release_contract.expected_module_dependencies(
                "procwright", "1.2.3", "apiElements"
            ),
            (("org.jspecify", "jspecify", "1.0.0"),),
        )
        self.assertEqual(
            release_contract.expected_module_dependencies(
                "procwright", "1.2.3", "runtimeElements"
            ),
            (),
        )

    def test_module_semantic_contract_is_literal_and_module_specific(self) -> None:
        expected = {
            "procwright": {
                "jpms_name": "io.github.ulviar.procwright",
                "package_root": "io/github/ulviar/procwright/",
                "primary_anchor": "io/github/ulviar/procwright/Procwright.class",
                "source_anchor": "io/github/ulviar/procwright/Procwright.java",
                "documentation_anchor": "io/github/ulviar/procwright/Procwright.html",
                "pom_name": "Procwright",
                "pom_description": "Scenario-first JVM library for safe external CLI execution and interactive process workflows.",
                "exports": (
                    "io.github.ulviar.procwright",
                    "io.github.ulviar.procwright.command",
                    "io.github.ulviar.procwright.diagnostics",
                    "io.github.ulviar.procwright.session",
                    "io.github.ulviar.procwright.terminal",
                ),
                "requires": (
                    ("java.base", 0x8000),
                    ("org.jspecify", 0x60),
                ),
                "requires_versions": (
                    ("java.base", "17"),
                    ("org.jspecify", None),
                ),
                "module_flags": 0,
                "module_version": None,
                "opens": (),
                "uses": (),
                "provides": (),
                "documentation_module_indexed": False,
            },
            "procwright-integrations": {
                "jpms_name": "io.github.ulviar.procwright.integrations",
                "package_root": "io/github/ulviar/procwright/integration/",
                "primary_anchor": "io/github/ulviar/procwright/integration/ProtocolAdapters.class",
                "source_anchor": "io/github/ulviar/procwright/integration/ProtocolAdapters.java",
                "documentation_anchor": "io/github/ulviar/procwright/integration/ProtocolAdapters.html",
                "pom_name": "Procwright Integrations",
                "pom_description": "Optional protocol and tool-adapter helpers for Procwright.",
                "exports": ("io.github.ulviar.procwright.integration",),
                "requires": (
                    ("com.fasterxml.jackson.databind", 0x20),
                    ("io.github.ulviar.procwright", 0x20),
                    ("java.base", 0x8000),
                    ("org.jspecify", 0x60),
                ),
                "requires_versions": (
                    ("com.fasterxml.jackson.databind", "2.22.0"),
                    ("io.github.ulviar.procwright", None),
                    ("java.base", "17"),
                    ("org.jspecify", None),
                ),
                "module_flags": 0,
                "module_version": None,
                "opens": (),
                "uses": (),
                "provides": (),
                "documentation_module_indexed": True,
            },
            "procwright-kotlin": {
                "jpms_name": "io.github.ulviar.procwright.kotlin",
                "package_root": "io/github/ulviar/procwright/kotlin/",
                "primary_anchor": "io/github/ulviar/procwright/kotlin/ProcwrightDsl.class",
                "source_anchor": "io/github/ulviar/procwright/kotlin/CoroutineExtensions.kt",
                "documentation_anchor": "procwright-kotlin/io.github.ulviar.procwright.kotlin/index.html",
                "pom_name": "Procwright Kotlin",
                "pom_description": "Optional Kotlin ergonomics for Procwright scenario workflows.",
                "exports": ("io.github.ulviar.procwright.kotlin",),
                "requires": (
                    ("io.github.ulviar.procwright", 0x20),
                    ("java.base", 0x8000),
                    ("kotlin.stdlib", 0x20),
                    ("kotlinx.coroutines.core", 0x20),
                ),
                "requires_versions": (
                    ("io.github.ulviar.procwright", None),
                    ("java.base", "17"),
                    ("kotlin.stdlib", None),
                    ("kotlinx.coroutines.core", None),
                ),
                "module_flags": 0,
                "module_version": None,
                "opens": (),
                "uses": (),
                "provides": (),
                "documentation_module_indexed": False,
            },
        }
        observed = {}
        for module in release_contract.MODULES:
            contract = release_contract.module_semantic_contract(module)
            observed[module] = {
                "jpms_name": contract.jpms_name,
                "package_root": contract.package_root,
                "primary_anchor": contract.primary_anchor,
                "source_anchor": contract.source_anchor,
                "documentation_anchor": contract.documentation_anchor,
                "pom_name": contract.pom_name,
                "pom_description": contract.pom_description,
                "exports": contract.exports,
                "requires": contract.requires,
                "requires_versions": contract.requires_versions,
                "module_flags": contract.module_flags,
                "module_version": contract.module_version,
                "opens": contract.opens,
                "uses": contract.uses,
                "provides": contract.provides,
                "documentation_module_indexed": contract.documentation_module_indexed,
            }
        self.assertEqual(observed, expected)

    def test_canonical_pom_metadata_is_literal(self) -> None:
        self.assertEqual(release_contract.PROJECT_URL, "https://github.com/Ulviar/Procwright")
        self.assertEqual(release_contract.LICENSE_NAME, "Apache License, Version 2.0")
        self.assertEqual(
            release_contract.LICENSE_URL,
            "https://www.apache.org/licenses/LICENSE-2.0",
        )
        self.assertEqual(
            release_contract.SCM_CONNECTION,
            "scm:git:https://github.com/Ulviar/Procwright.git",
        )
        self.assertEqual(
            release_contract.SCM_DEVELOPER_CONNECTION,
            "scm:git:https://github.com/Ulviar/Procwright.git",
        )
        self.assertEqual(release_contract.SCM_URL, "https://github.com/Ulviar/Procwright")
        self.assertEqual(release_contract.DEVELOPER_ID, "Ulviar")
        self.assertEqual(release_contract.DEVELOPER_NAME, "Ulviar")
        self.assertEqual(
            release_contract.POM_SCHEMA_LOCATION,
            "http://maven.apache.org/POM/4.0.0 "
            "https://maven.apache.org/xsd/maven-4.0.0.xsd",
        )


if __name__ == "__main__":
    unittest.main()
