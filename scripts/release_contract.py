#!/usr/bin/env python3
"""Canonical release input validation shared by release workflows and scripts."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import re
import sys
import urllib.parse


MAX_VERSION_LENGTH = 128
SEMVER_PATTERN = re.compile(
    r"(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)"
    r"(?:-(?:"
    r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*"
    r"))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)
COMMIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
DEPLOYMENT_ID_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    re.IGNORECASE,
)

GROUP_ID = "io.github.ulviar"
GROUP_PATH = GROUP_ID.replace(".", "/")
MODULES = ("procwright", "procwright-integrations", "procwright-kotlin")
BASE_SUFFIXES = (".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")
CHECKSUMS = (
    ("md5", 32),
    ("sha1", 40),
    ("sha256", 64),
    ("sha512", 128),
)
CENTRAL_REPOSITORY = "https://repo.maven.apache.org/maven2/"
PUBLISHING_TYPE = "USER_MANAGED"

PROJECT_URL = "https://github.com/Ulviar/Procwright"
LICENSE_NAME = "Apache License, Version 2.0"
LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
SCM_CONNECTION = "scm:git:https://github.com/Ulviar/Procwright.git"
SCM_DEVELOPER_CONNECTION = "scm:git:https://github.com/Ulviar/Procwright.git"
SCM_URL = "https://github.com/Ulviar/Procwright"
DEVELOPER_ID = "Ulviar"
DEVELOPER_NAME = "Ulviar"
POM_SCHEMA_LOCATION = (
    "http://maven.apache.org/POM/4.0.0 "
    "https://maven.apache.org/xsd/maven-4.0.0.xsd"
)

POM_EXTERNAL_DEPENDENCIES = {
    "procwright": (
        ("org.jspecify", "jspecify", "1.0.0", "compile"),
    ),
    "procwright-integrations": (
        ("com.fasterxml.jackson.core", "jackson-databind", "2.22.0", "compile"),
        ("org.jspecify", "jspecify", "1.0.0", "compile"),
    ),
    "procwright-kotlin": (
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0", "compile"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21", "compile"),
    ),
}

MODULE_VARIANT_EXTERNAL_DEPENDENCIES = {
    "procwright": {
        "apiElements": (("org.jspecify", "jspecify", "1.0.0"),),
        "runtimeElements": (),
    },
    "procwright-integrations": {
        "apiElements": (
            ("com.fasterxml.jackson.core", "jackson-databind", "2.22.0"),
            ("org.jspecify", "jspecify", "1.0.0"),
        ),
        "runtimeElements": (
            ("com.fasterxml.jackson.core", "jackson-databind", "2.22.0"),
        ),
    },
    "procwright-kotlin": {
        "apiElements": (
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.11.0"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21"),
        ),
        "runtimeElements": (
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.11.0"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21"),
        ),
    },
}


class ReleaseContractError(ValueError):
    """A release input is not canonical or safe to use as an identifier."""


@dataclass(frozen=True)
class ArtifactIdentity:
    """One path in the closed Maven Central release set."""

    path: str
    module: str
    base_suffix: str
    role: str
    checksum_algorithm: str | None = None

    @property
    def filename(self) -> str:
        return self.path.rsplit("/", 1)[-1]

    @property
    def base_path(self) -> str:
        if self.role == "base":
            return self.path
        if self.role == "signature":
            return self.path.removesuffix(".asc")
        return self.path.rsplit(".", 1)[0]


@dataclass(frozen=True)
class ModuleSemanticContract:
    """Canonical user-visible identity of one published Procwright module."""

    jpms_name: str
    package_root: str
    primary_anchor: str
    source_anchor: str
    documentation_anchor: str
    pom_name: str
    pom_description: str
    exports: tuple[str, ...]
    requires: tuple[tuple[str, int], ...]
    requires_versions: tuple[tuple[str, str | None], ...]
    module_flags: int
    module_version: str | None
    opens: tuple[tuple[str, int, tuple[str, ...]], ...]
    uses: tuple[str, ...]
    provides: tuple[tuple[str, tuple[str, ...]], ...]
    source_language: str
    explicit_module: bool
    documentation_module_indexed: bool


MODULE_SEMANTIC_CONTRACTS = {
    "procwright": ModuleSemanticContract(
        jpms_name="io.github.ulviar.procwright",
        package_root="io/github/ulviar/procwright/",
        primary_anchor="io/github/ulviar/procwright/Procwright.class",
        source_anchor="io/github/ulviar/procwright/Procwright.java",
        documentation_anchor="io/github/ulviar/procwright/Procwright.html",
        pom_name="Procwright",
        pom_description=(
            "Scenario-first JVM library for safe external CLI execution and "
            "interactive process workflows."
        ),
        exports=(
            "io.github.ulviar.procwright",
            "io.github.ulviar.procwright.command",
            "io.github.ulviar.procwright.diagnostics",
            "io.github.ulviar.procwright.session",
            "io.github.ulviar.procwright.terminal",
        ),
        requires=(
            ("java.base", 0x8000),
            ("org.jspecify", 0x60),
        ),
        requires_versions=(
            ("java.base", "17"),
            ("org.jspecify", None),
        ),
        module_flags=0,
        module_version=None,
        opens=(),
        uses=(),
        provides=(),
        source_language="java",
        explicit_module=True,
        documentation_module_indexed=False,
    ),
    "procwright-integrations": ModuleSemanticContract(
        jpms_name="io.github.ulviar.procwright.integrations",
        package_root="io/github/ulviar/procwright/integration/",
        primary_anchor=(
            "io/github/ulviar/procwright/integration/ProtocolAdapters.class"
        ),
        source_anchor=(
            "io/github/ulviar/procwright/integration/ProtocolAdapters.java"
        ),
        documentation_anchor=(
            "io/github/ulviar/procwright/integration/ProtocolAdapters.html"
        ),
        pom_name="Procwright Integrations",
        pom_description="Optional JSON and byte-framing adapters for Procwright protocol sessions.",
        exports=("io.github.ulviar.procwright.integration",),
        requires=(
            ("com.fasterxml.jackson.databind", 0x20),
            ("io.github.ulviar.procwright", 0x20),
            ("java.base", 0x8000),
            ("org.jspecify", 0x60),
        ),
        requires_versions=(
            ("com.fasterxml.jackson.databind", "2.22.0"),
            ("io.github.ulviar.procwright", None),
            ("java.base", "17"),
            ("org.jspecify", None),
        ),
        module_flags=0,
        module_version=None,
        opens=(),
        uses=(),
        provides=(),
        source_language="java",
        explicit_module=True,
        documentation_module_indexed=True,
    ),
    "procwright-kotlin": ModuleSemanticContract(
        jpms_name="io.github.ulviar.procwright.kotlin",
        package_root="io/github/ulviar/procwright/kotlin/",
        primary_anchor="io/github/ulviar/procwright/kotlin/ProcwrightDsl.class",
        source_anchor=(
            "io/github/ulviar/procwright/kotlin/CoroutineExtensions.kt"
        ),
        documentation_anchor=(
            "procwright-kotlin/io.github.ulviar.procwright.kotlin/index.html"
        ),
        pom_name="Procwright Kotlin",
        pom_description="Optional Kotlin ergonomics for Procwright scenario workflows.",
        exports=("io.github.ulviar.procwright.kotlin",),
        requires=(
            ("io.github.ulviar.procwright", 0x20),
            ("java.base", 0x8000),
            ("kotlin.stdlib", 0x20),
            ("kotlinx.coroutines.core", 0x20),
        ),
        requires_versions=(
            ("io.github.ulviar.procwright", None),
            ("java.base", "17"),
            ("kotlin.stdlib", None),
            ("kotlinx.coroutines.core", None),
        ),
        module_flags=0,
        module_version=None,
        opens=(),
        uses=(),
        provides=(),
        source_language="kotlin",
        explicit_module=True,
        documentation_module_indexed=False,
    ),
}


def module_semantic_contract(module: str) -> ModuleSemanticContract:
    try:
        return MODULE_SEMANTIC_CONTRACTS[module]
    except KeyError as error:
        raise ReleaseContractError(f"unknown release module: {module!r}") from error


def require_canonical_version(version: str) -> str:
    if (
        not isinstance(version, str)
        or len(version) > MAX_VERSION_LENGTH
        or SEMVER_PATTERN.fullmatch(version) is None
    ):
        raise ReleaseContractError(
            f"release version must be canonical path-safe SemVer without a leading v: {version!r}"
        )
    return version


def require_commit_sha(commit: str) -> str:
    if not isinstance(commit, str) or COMMIT_SHA_PATTERN.fullmatch(commit) is None:
        raise ReleaseContractError(
            "release commit must be a lowercase full 40-character Git SHA"
        )
    return commit


def require_deployment_id(deployment_id: str) -> str:
    if (
        not isinstance(deployment_id, str)
        or DEPLOYMENT_ID_PATTERN.fullmatch(deployment_id) is None
    ):
        raise ReleaseContractError("Central deployment ID must be a canonical UUID")
    return deployment_id.lower()


def expected_base_paths(version: str) -> tuple[str, ...]:
    version = require_canonical_version(version)
    return tuple(
        sorted(
            f"{GROUP_PATH}/{module}/{version}/{module}-{version}{suffix}"
            for module in MODULES
            for suffix in BASE_SUFFIXES
        )
    )


def expected_release_artifacts(version: str) -> tuple[ArtifactIdentity, ...]:
    version = require_canonical_version(version)
    artifacts = []
    for module in MODULES:
        directory = f"{GROUP_PATH}/{module}/{version}"
        for suffix in BASE_SUFFIXES:
            base_path = f"{directory}/{module}-{version}{suffix}"
            artifacts.append(ArtifactIdentity(base_path, module, suffix, "base"))
            artifacts.append(
                ArtifactIdentity(base_path + ".asc", module, suffix, "signature")
            )
            for algorithm, _length in CHECKSUMS:
                artifacts.append(
                    ArtifactIdentity(
                        base_path + "." + algorithm,
                        module,
                        suffix,
                        "checksum",
                        algorithm,
                    )
                )
    return tuple(sorted(artifacts, key=lambda artifact: artifact.path))


def expected_release_paths(version: str) -> tuple[str, ...]:
    return tuple(artifact.path for artifact in expected_release_artifacts(version))


def expected_generated_metadata_paths() -> tuple[str, ...]:
    suffixes = ("", ".md5", ".sha1", ".sha256", ".sha512")
    return tuple(
        sorted(
            f"{GROUP_PATH}/{module}/maven-metadata.xml{suffix}"
            for module in MODULES
            for suffix in suffixes
        )
    )


def artifact_identity(path: str, version: str) -> ArtifactIdentity:
    identities = {artifact.path: artifact for artifact in expected_release_artifacts(version)}
    try:
        return identities[path]
    except KeyError as error:
        raise ReleaseContractError(f"path is outside the closed release artifact set: {path!r}") from error


def expected_pom_dependencies(
    module: str, version: str
) -> tuple[tuple[str, str, str, str], ...]:
    require_canonical_version(version)
    if module not in MODULES:
        raise ReleaseContractError(f"unknown release module: {module!r}")
    internal = (
        ((GROUP_ID, "procwright", version, "compile"),)
        if module != "procwright"
        else ()
    )
    return tuple(sorted(internal + POM_EXTERNAL_DEPENDENCIES[module]))


def expected_module_dependencies(
    module: str, version: str, variant: str = "apiElements"
) -> tuple[tuple[str, str, str], ...]:
    require_canonical_version(version)
    if module not in MODULES:
        raise ReleaseContractError(f"unknown release module: {module!r}")
    if variant not in ("apiElements", "runtimeElements"):
        raise ReleaseContractError(f"unknown Gradle module variant: {variant!r}")
    internal = (((GROUP_ID, "procwright", version),) if module != "procwright" else ())
    return tuple(
        sorted(internal + MODULE_VARIANT_EXTERNAL_DEPENDENCIES[module][variant])
    )


def deployment_name(version: str, commit: str) -> str:
    return f"{GROUP_ID}:procwright:{require_canonical_version(version)}:{require_commit_sha(commit)}"


def expected_purls(version: str) -> tuple[str, ...]:
    version = require_canonical_version(version)
    encoded_version = urllib.parse.quote(version, safe=".-_~")
    return tuple(
        f"pkg:maven/{GROUP_ID}/{module}@{encoded_version}" for module in MODULES
    )


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="kind", required=True)
    version_parser = subparsers.add_parser("version")
    version_parser.add_argument("value")
    commit_parser = subparsers.add_parser("commit")
    commit_parser.add_argument("value")
    paths_parser = subparsers.add_parser("release-paths")
    paths_parser.add_argument("value")
    options = parser.parse_args(arguments)
    try:
        if options.kind == "version":
            require_canonical_version(options.value)
        elif options.kind == "commit":
            require_commit_sha(options.value)
        else:
            print("\n".join(expected_release_paths(options.value)))
    except ReleaseContractError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
