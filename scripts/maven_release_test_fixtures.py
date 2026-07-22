"""Deterministic, structurally realistic fixtures for Maven release evidence tests."""

from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import stat
import struct
import subprocess
import tempfile
import textwrap
import warnings
import zipfile
from pathlib import Path

from release_contract import (
    BASE_SUFFIXES,
    CHECKSUMS,
    GROUP_ID,
    GROUP_PATH,
    MODULES,
    expected_module_dependencies,
    expected_pom_dependencies,
)


VERSION = "1.2.3-rc.1+build.7"
COMMIT = "a" * 40
DEPLOYMENT_ID = "123e4567-e89b-12d3-a456-426614174000"


class EphemeralSigningKey:
    """One real throwaway GPG key for cryptographic release tests."""

    def __init__(self, identity: str = "Procwright Release Test <release-test@example.invalid>"):
        self._temporary = tempfile.TemporaryDirectory(prefix="procwright-test-gpg-")
        self.home = Path(self._temporary.name)
        self.home.chmod(0o700)
        self.environment = {
            **os.environ,
            "GNUPGHOME": str(self.home),
            "HOME": str(self.home),
            "LANG": "C",
            "LC_ALL": "C",
        }
        self._run(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--no-tty",
                "--passphrase",
                "",
                "--quick-generate-key",
                identity,
                "rsa2048",
                "sign",
                "1d",
            ]
        )
        listed = self._run(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--with-colons",
                "--fingerprint",
                "--list-secret-keys",
            ]
        ).stdout.decode("ascii")
        self.fingerprint = next(
            line.split(":")[9]
            for line in listed.splitlines()
            if line.startswith("fpr:")
        )
        self.public_key = self._run(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--quiet",
                "--armor",
                "--export",
                self.fingerprint,
            ]
        ).stdout

    def export_secret_key(self) -> bytes:
        return self._run(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--quiet",
                "--armor",
                "--export-secret-keys",
                self.fingerprint,
            ]
        ).stdout

    def _run(
        self, command: list[str], *, input_payload: bytes | None = None
    ) -> subprocess.CompletedProcess[bytes]:
        result = subprocess.run(
            command,
            check=False,
            input=input_payload,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=self.environment,
            timeout=120,
        )
        if result.returncode != 0:
            raise RuntimeError(
                "ephemeral GPG fixture failed: "
                + result.stderr.decode("utf-8", errors="replace")
            )
        return result

    def sign(self, payload: bytes) -> bytes:
        return self._run(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--no-tty",
                "--quiet",
                "--armor",
                "--local-user",
                self.fingerprint,
                "--detach-sign",
                "--output",
                "-",
            ],
            input_payload=payload,
        ).stdout

    def close(self) -> None:
        self._temporary.cleanup()


def artifact_path(module: str, suffix: str, version: str = VERSION) -> str:
    return f"{GROUP_PATH}/{module}/{version}/{module}-{version}{suffix}"


def zip_payload(entries: list[tuple[str, bytes, int]]) -> bytes:
    output = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, payload, mode in entries:
                info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = mode << 16
                archive.writestr(info, payload)
    return output.getvalue()


def _module_info_class(
    module_name: str,
    exports: tuple[str, ...],
    requires: tuple[tuple[str, int], ...],
    *,
    requires_versions: tuple[tuple[str, str | None], ...] = (),
    module_flags: int = 0,
    module_version: str | None = None,
    opens: tuple[tuple[str, int, tuple[str, ...]], ...] = (),
    uses: tuple[str, ...] = (),
    provides: tuple[tuple[str, tuple[str, ...]], ...] = (),
) -> bytes:
    constant_pool: list[bytes] = []

    def utf8(value: str) -> int:
        encoded = value.encode("utf-8")
        constant_pool.append(b"\x01" + struct.pack(">H", len(encoded)) + encoded)
        return len(constant_pool)

    module_info_utf8 = utf8("module-info")
    constant_pool.append(b"\x07" + struct.pack(">H", module_info_utf8))
    module_info_class = len(constant_pool)
    module_attribute_name = utf8("Module")
    module_name_utf8 = utf8(module_name)
    constant_pool.append(b"\x13" + struct.pack(">H", module_name_utf8))
    module_name_index = len(constant_pool)
    module_version_index = utf8(module_version) if module_version is not None else 0
    requires_indexes = []
    required_versions = dict(requires_versions)
    for required, flags in requires:
        required_utf8 = utf8(required)
        constant_pool.append(b"\x13" + struct.pack(">H", required_utf8))
        required_index = len(constant_pool)
        required_version = required_versions.get(required)
        version_index = utf8(required_version) if required_version is not None else 0
        requires_indexes.append((required_index, flags, version_index))
    export_indexes = []
    for exported in exports:
        package_utf8 = utf8(exported.replace(".", "/"))
        constant_pool.append(b"\x14" + struct.pack(">H", package_utf8))
        export_indexes.append(len(constant_pool))
    open_indexes = []
    for package, flags, targets in opens:
        package_utf8 = utf8(package.replace(".", "/"))
        constant_pool.append(b"\x14" + struct.pack(">H", package_utf8))
        package_index = len(constant_pool)
        target_indexes = []
        for target in targets:
            target_utf8 = utf8(target)
            constant_pool.append(b"\x13" + struct.pack(">H", target_utf8))
            target_indexes.append(len(constant_pool))
        open_indexes.append((package_index, flags, target_indexes))
    use_indexes = []
    for service in uses:
        service_utf8 = utf8(service.replace(".", "/"))
        constant_pool.append(b"\x07" + struct.pack(">H", service_utf8))
        use_indexes.append(len(constant_pool))
    provide_indexes = []
    for service, providers in provides:
        service_utf8 = utf8(service.replace(".", "/"))
        constant_pool.append(b"\x07" + struct.pack(">H", service_utf8))
        service_index = len(constant_pool)
        provider_indexes = []
        for provider in providers:
            provider_utf8 = utf8(provider.replace(".", "/"))
            constant_pool.append(b"\x07" + struct.pack(">H", provider_utf8))
            provider_indexes.append(len(constant_pool))
        provide_indexes.append((service_index, provider_indexes))
    module_body = struct.pack(
        ">3H", module_name_index, module_flags, module_version_index
    )
    module_body += struct.pack(">H", len(requires_indexes))
    for required_index, flags, version_index in requires_indexes:
        module_body += struct.pack(">3H", required_index, flags, version_index)
    module_body += struct.pack(">H", len(export_indexes))
    for package_index in export_indexes:
        module_body += struct.pack(">3H", package_index, 0, 0)
    module_body += struct.pack(">H", len(open_indexes))
    for package_index, flags, target_indexes in open_indexes:
        module_body += struct.pack(">3H", package_index, flags, len(target_indexes))
        module_body += b"".join(struct.pack(">H", target) for target in target_indexes)
    module_body += struct.pack(">H", len(use_indexes))
    module_body += b"".join(struct.pack(">H", service) for service in use_indexes)
    module_body += struct.pack(">H", len(provide_indexes))
    for service_index, provider_indexes in provide_indexes:
        module_body += struct.pack(">2H", service_index, len(provider_indexes))
        module_body += b"".join(
            struct.pack(">H", provider) for provider in provider_indexes
        )
    return (
        b"\xca\xfe\xba\xbe"
        + struct.pack(">2H", 0, 61)
        + struct.pack(">H", len(constant_pool) + 1)
        + b"".join(constant_pool)
        + struct.pack(">7H", 0x8000, module_info_class, 0, 0, 0, 0, 1)
        + struct.pack(">HI", module_attribute_name, len(module_body))
        + module_body
    )


def jar_payload(module: str, suffix: str) -> bytes:
    if suffix == ".jar":
        if module == "procwright":
            entries = [
                ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n", stat.S_IFREG | 0o644),
                (
                    "module-info.class",
                    _module_info_class(
                        "io.github.ulviar.procwright",
                        (
                            "io.github.ulviar.procwright",
                            "io.github.ulviar.procwright.command",
                            "io.github.ulviar.procwright.diagnostics",
                            "io.github.ulviar.procwright.session",
                            "io.github.ulviar.procwright.terminal",
                        ),
                        (
                            ("java.base", 0x8000),
                            ("org.jspecify", 0x60),
                        ),
                        requires_versions=(
                            ("java.base", "17"),
                            ("org.jspecify", None),
                        ),
                    ),
                    stat.S_IFREG | 0o644,
                ),
                ("io/github/ulviar/procwright/Procwright.class", b"\xca\xfe\xba\xbe core", stat.S_IFREG | 0o644),
            ]
        elif module == "procwright-integrations":
            entries = [
                ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n", stat.S_IFREG | 0o644),
                (
                    "module-info.class",
                    _module_info_class(
                        "io.github.ulviar.procwright.integrations",
                        ("io.github.ulviar.procwright.integration",),
                        (
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
                    ),
                    stat.S_IFREG | 0o644,
                ),
                (
                    "io/github/ulviar/procwright/integration/ProtocolAdapters.class",
                    b"\xca\xfe\xba\xbe integrations",
                    stat.S_IFREG | 0o644,
                ),
            ]
        else:
            entries = [
                (
                    "META-INF/MANIFEST.MF",
                    b"Manifest-Version: 1.0\r\n\r\n",
                    stat.S_IFREG | 0o644,
                ),
                (
                    "module-info.class",
                    _module_info_class(
                        "io.github.ulviar.procwright.kotlin",
                        ("io.github.ulviar.procwright.kotlin",),
                        (
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
                    ),
                    stat.S_IFREG | 0o644,
                ),
                ("META-INF/procwright-kotlin.kotlin_module", b"kotlin fixture", stat.S_IFREG | 0o644),
                (
                    "io/github/ulviar/procwright/kotlin/ProcwrightDsl.class",
                    b"\xca\xfe\xba\xbe kotlin",
                    stat.S_IFREG | 0o644,
                ),
            ]
    elif suffix == "-sources.jar":
        if module == "procwright":
            entries = [
                (
                    "module-info.java",
                    b"module io.github.ulviar.procwright { requires static transitive org.jspecify; exports io.github.ulviar.procwright; exports io.github.ulviar.procwright.command; exports io.github.ulviar.procwright.diagnostics; exports io.github.ulviar.procwright.session; exports io.github.ulviar.procwright.terminal; }\n",
                    stat.S_IFREG | 0o644,
                ),
                (
                    "io/github/ulviar/procwright/Procwright.java",
                    b"package io.github.ulviar.procwright; public final class Procwright {}\n",
                    stat.S_IFREG | 0o644,
                ),
            ]
        elif module == "procwright-integrations":
            entries = [
                (
                    "module-info.java",
                    b"module io.github.ulviar.procwright.integrations { requires transitive io.github.ulviar.procwright; requires transitive com.fasterxml.jackson.databind; requires static transitive org.jspecify; exports io.github.ulviar.procwright.integration; }\n",
                    stat.S_IFREG | 0o644,
                ),
                (
                    "io/github/ulviar/procwright/integration/ProtocolAdapters.java",
                    b"package io.github.ulviar.procwright.integration; public final class ProtocolAdapters {}\n",
                    stat.S_IFREG | 0o644,
                ),
            ]
        else:
            entries = [
                (
                    "module-info.java",
                    b"module io.github.ulviar.procwright.kotlin { requires transitive io.github.ulviar.procwright; requires transitive kotlin.stdlib; requires transitive kotlinx.coroutines.core; exports io.github.ulviar.procwright.kotlin; }\n",
                    stat.S_IFREG | 0o644,
                ),
                (
                    "io/github/ulviar/procwright/kotlin/CoroutineExtensions.kt",
                    b"package io.github.ulviar.procwright.kotlin\npublic fun fixture() = Unit\n",
                    stat.S_IFREG | 0o644,
                )
            ]
    elif suffix == "-javadoc.jar":
        entries = [("index.html", b"<!doctype html><title>API</title>\n", stat.S_IFREG | 0o644)]
        if module == "procwright":
            entries.extend(
                [
                    (
                        "element-list",
                        b"io.github.ulviar.procwright\nio.github.ulviar.procwright.command\nio.github.ulviar.procwright.diagnostics\nio.github.ulviar.procwright.session\nio.github.ulviar.procwright.terminal\n",
                        stat.S_IFREG | 0o644,
                    ),
                    ("io/github/ulviar/procwright/Procwright.html", b"<!doctype html><title>Procwright</title>\n", stat.S_IFREG | 0o644),
                ]
            )
        elif module == "procwright-integrations":
            prefix = "io.github.ulviar.procwright.integrations/"
            entries.extend(
                [
                    (
                        "element-list",
                        b"module:io.github.ulviar.procwright.integrations\nio.github.ulviar.procwright.integration\n",
                        stat.S_IFREG | 0o644,
                    ),
                    (prefix + "module-summary.html", b"<!doctype html><title>Integrations module</title>\n", stat.S_IFREG | 0o644),
                    (prefix + "io/github/ulviar/procwright/integration/ProtocolAdapters.html", b"<!doctype html><title>ProtocolAdapters</title>\n", stat.S_IFREG | 0o644),
                ]
            )
        else:
            entries.extend(
                [
                    (
                        "procwright-kotlin/package-list",
                        b"$dokka.format:html-v1\n$dokka.linkExtension:html\n$dokka.location:io.github.ulviar.procwright.kotlin////PointingToDeclaration/\x1fprocwright-kotlin/io.github.ulviar.procwright.kotlin/index.html\nio.github.ulviar.procwright.kotlin\n",
                        stat.S_IFREG | 0o644,
                    ),
                    ("procwright-kotlin/io.github.ulviar.procwright.kotlin/index.html", b"<!doctype html><title>Procwright Kotlin</title>\n", stat.S_IFREG | 0o644),
                ]
            )
    else:
        raise ValueError(f"unsupported JAR suffix: {suffix}")
    return zip_payload(entries)


def pom_payload(module: str, version: str = VERSION) -> bytes:
    dependencies = "".join(
        "<dependency>"
        f"<groupId>{group}</groupId>"
        f"<artifactId>{artifact}</artifactId>"
        f"<version>{dependency_version}</version>"
        f"<scope>{scope}</scope>"
        "</dependency>"
        for group, artifact, dependency_version, scope in expected_pom_dependencies(
            module, version
        )
    )
    dependency_block = f"<dependencies>{dependencies}</dependencies>" if dependencies else ""
    metadata = {
        "procwright": (
            "Procwright",
            "Scenario-first JVM library for safe external CLI execution and interactive process workflows.",
        ),
        "procwright-integrations": (
            "Procwright Integrations",
            "Optional protocol and tool-adapter helpers for Procwright.",
        ),
        "procwright-kotlin": (
            "Procwright Kotlin",
            "Optional Kotlin ergonomics for Procwright scenario workflows.",
        ),
    }
    name, description = metadata[module]
    return (
        '<project xmlns="http://maven.apache.org/POM/4.0.0" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 '
        'https://maven.apache.org/xsd/maven-4.0.0.xsd">'
        "<modelVersion>4.0.0</modelVersion>"
        f"<groupId>{GROUP_ID}</groupId>"
        f"<artifactId>{module}</artifactId>"
        f"<version>{version}</version>"
        f"<name>{name}</name>"
        f"<description>{description}</description>"
        "<url>https://github.com/Ulviar/Procwright</url>"
        "<licenses><license><name>Apache License, Version 2.0</name>"
        "<url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>"
        "<developers><developer><id>Ulviar</id><name>Ulviar</name></developer></developers>"
        "<scm><connection>scm:git:https://github.com/Ulviar/Procwright.git</connection>"
        "<developerConnection>scm:git:https://github.com/Ulviar/Procwright.git</developerConnection>"
        "<url>https://github.com/Ulviar/Procwright</url></scm>"
        f"{dependency_block}"
        "</project>"
    ).encode("utf-8")


def _hashes(payload: bytes) -> dict[str, object]:
    return {
        "name": "",
        "url": "",
        "size": len(payload),
        "sha512": hashlib.sha512(payload).hexdigest(),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "sha1": hashlib.sha1(payload, usedforsecurity=False).hexdigest(),
        "md5": hashlib.md5(payload, usedforsecurity=False).hexdigest(),
    }


def _variant_attributes(module: str, name: str) -> dict[str, object]:
    if name in {"sourcesElements", "javadocElements"}:
        return {
            "org.gradle.category": "documentation",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.docstype": "sources" if name == "sourcesElements" else "javadoc",
            "org.gradle.usage": "java-runtime",
        }
    attributes: dict[str, object] = {
        "org.gradle.category": "library",
        "org.gradle.dependency.bundling": "external",
        "org.gradle.jvm.version": 17,
        "org.gradle.libraryelements": "jar",
        "org.gradle.usage": "java-api" if name == "apiElements" else "java-runtime",
    }
    if module == "procwright-kotlin":
        attributes["org.gradle.jvm.environment"] = "standard-jvm"
        attributes["org.jetbrains.kotlin.platform.type"] = "jvm"
    return attributes


def module_payload(
    module: str, payloads: dict[str, bytes], version: str = VERSION
) -> bytes:
    variant_names = ["apiElements", "runtimeElements", "sourcesElements"]
    if module != "procwright-kotlin":
        variant_names.append("javadocElements")
    variants = []
    for name in variant_names:
        dependencies = [
            {
                "group": group,
                "module": dependency,
                "version": {"requires": dependency_version},
            }
            for group, dependency, dependency_version in (
                expected_module_dependencies(module, version, name)
                if name in {"apiElements", "runtimeElements"}
                else ()
            )
        ]
        if name == "sourcesElements":
            suffix = "-sources.jar"
        elif name == "javadocElements":
            suffix = "-javadoc.jar"
        else:
            suffix = ".jar"
        filename = f"{module}-{version}{suffix}"
        file_identity = _hashes(payloads[artifact_path(module, suffix, version)])
        file_identity["name"] = filename
        file_identity["url"] = filename
        variant = {
            "name": name,
            "attributes": _variant_attributes(module, name),
            "files": [file_identity],
        }
        if name in {"apiElements", "runtimeElements"} and dependencies:
            variant["dependencies"] = dependencies
        variants.append(variant)
    metadata = {
        "formatVersion": "1.1",
        "component": {
            "group": GROUP_ID,
            "module": module,
            "version": version,
            "attributes": {"org.gradle.status": "release"},
        },
        "createdBy": {"gradle": {"version": "9.5.1"}},
        "variants": variants,
    }
    return (json.dumps(metadata, separators=(",", ":"), sort_keys=True) + "\n").encode()


def _crc24(payload: bytes) -> int:
    value = 0xB704CE
    for byte in payload:
        value ^= byte << 16
        for _ in range(8):
            value <<= 1
            if value & 0x1000000:
                value ^= 0x1864CFB
    return value & 0xFFFFFF


def armored_signature() -> bytes:
    creation_time = b"\x05\x02\x00\x00\x00\x01"
    issuer = b"\x09\x10" + b"\x01\x23\x45\x67\x89\xab\xcd\xef"
    mpi = b"\x00\x01\x01"
    body = (
        b"\x04\x00\x01\x0a"
        + len(creation_time).to_bytes(2, "big")
        + creation_time
        + len(issuer).to_bytes(2, "big")
        + issuer
        + b"\x00\x00"
        + mpi
    )
    packet = b"\xc2" + bytes((len(body),)) + body
    encoded = textwrap.wrap(base64.b64encode(packet).decode("ascii"), width=64)
    checksum = base64.b64encode(_crc24(packet).to_bytes(3, "big")).decode("ascii")
    return (
        "-----BEGIN PGP SIGNATURE-----\n\n"
        + "\n".join(encoded)
        + f"\n={checksum}\n-----END PGP SIGNATURE-----\n"
    ).encode("ascii")


def refresh_base_sidecars(payloads: dict[str, bytes], base_path: str) -> None:
    payloads[base_path + ".asc"] = armored_signature()
    for algorithm, _length in CHECKSUMS:
        payloads[base_path + "." + algorithm] = hashlib.new(
            algorithm, payloads[base_path]
        ).hexdigest().encode("ascii")


def replace_base_payload(
    payloads: dict[str, bytes], base_path: str, replacement: bytes
) -> None:
    payloads[base_path] = replacement
    refresh_base_sidecars(payloads, base_path)


def closed_release_payloads(version: str = VERSION) -> dict[str, bytes]:
    payloads: dict[str, bytes] = {}
    for module in MODULES:
        for suffix in BASE_SUFFIXES:
            if suffix.endswith(".jar"):
                payloads[artifact_path(module, suffix, version)] = jar_payload(
                    module, suffix
                )
            elif suffix == ".pom":
                payloads[artifact_path(module, suffix, version)] = pom_payload(
                    module, version
                )
        payloads[artifact_path(module, ".module", version)] = module_payload(
            module, payloads, version
        )
        for suffix in BASE_SUFFIXES:
            refresh_base_sidecars(payloads, artifact_path(module, suffix, version))
    return dict(sorted(payloads.items()))


def cryptographically_sign_payloads(
    payloads: dict[str, bytes], signing_key: EphemeralSigningKey, version: str = VERSION
) -> None:
    for module in MODULES:
        for suffix in BASE_SUFFIXES:
            path = artifact_path(module, suffix, version)
            payloads[path + ".asc"] = signing_key.sign(payloads[path])


def refresh_module_metadata(
    payloads: dict[str, bytes], module: str, version: str = VERSION
) -> None:
    path = artifact_path(module, ".module", version)
    payloads[path] = module_payload(module, payloads, version)
    refresh_base_sidecars(payloads, path)


def write_bundle(path: Path, payloads: dict[str, bytes]) -> None:
    entries = [
        (name, payload, stat.S_IFREG | 0o644)
        for name, payload in payloads.items()
    ]
    path.write_bytes(zip_payload(entries))


def replace_raw_zip_name(payload: bytes, old: str, new: str) -> bytes:
    old_bytes = old.encode("utf-8")
    new_bytes = new.encode("utf-8")
    if len(old_bytes) != len(new_bytes):
        raise ValueError("raw ZIP replacement names must have equal byte lengths")
    if payload.count(old_bytes) != 2:
        raise ValueError("expected one local and one central ZIP name")
    return payload.replace(old_bytes, new_bytes)
