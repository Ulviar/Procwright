#!/usr/bin/env python3
"""Run a bounded real-artifact, real-GPG local release roundtrip."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from maven_release_evidence import EvidenceError, validate_base_payloads
from release_contract import (
    expected_base_paths,
    expected_generated_metadata_paths,
    expected_release_paths,
    require_canonical_version,
)


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
COMMIT = "0123456789abcdef0123456789abcdef01234567"


class RealReleaseTestError(RuntimeError):
    """The real local release path did not preserve its exact contract."""


class _StreamingZipSink:
    """Collect a non-seekable ZIP stream like the GitHub artifact uploader."""

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


def _run(
    arguments: list[str],
    *,
    cwd: Path,
    environment: dict[str, str] | None = None,
    timeout: int = 120,
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            arguments,
            cwd=cwd,
            env=environment,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RealReleaseTestError(f"could not execute {arguments[0]}") from error
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace")[:2000]
        raise RealReleaseTestError(
            f"command failed without completing the local release proof: {stderr}"
        )
    return result


def _generate_test_key(root: Path) -> tuple[Path, Path, str, str]:
    gnupg_home = root / "source-gnupg"
    gnupg_home.mkdir(mode=0o700)
    passphrase = root / "source-passphrase"
    passphrase.write_text("test-passphrase", encoding="utf-8")
    passphrase.chmod(0o600)
    environment = {**os.environ, "GNUPGHOME": str(gnupg_home)}
    _run(
        [
            "gpg",
            "--batch",
            "--pinentry-mode",
            "loopback",
            "--passphrase-file",
            str(passphrase),
            "--quick-generate-key",
            "Procwright E2E Release <release-e2e@example.invalid>",
            "rsa2048",
            "cert",
            "1d",
        ],
        cwd=root,
        environment=environment,
        timeout=30,
    )
    listed = _run(
        ["gpg", "--batch", "--with-colons", "--list-secret-keys"],
        cwd=root,
        environment=environment,
        timeout=30,
    )
    fingerprint = next(
        (
            line.split(":")[9]
            for line in listed.stdout.decode("ascii").splitlines()
            if line.startswith("fpr:")
        ),
        "",
    )
    if len(fingerprint) != 40:
        raise RealReleaseTestError("test signing key has no canonical fingerprint")
    _run(
        [
            "gpg",
            "--batch",
            "--pinentry-mode",
            "loopback",
            "--passphrase-file",
            str(passphrase),
            "--quick-add-key",
            fingerprint,
            "rsa2048",
            "sign",
            "1d",
        ],
        cwd=root,
        environment=environment,
        timeout=30,
    )
    exported = _run(
        [
            "gpg",
            "--batch",
            "--pinentry-mode",
            "loopback",
            "--passphrase-file",
            str(passphrase),
            "--armor",
            "--export-secret-keys",
            fingerprint,
        ],
        cwd=root,
        environment=environment,
        timeout=30,
    ).stdout
    try:
        signing_key = exported.decode("ascii")
    except UnicodeDecodeError as error:
        raise RealReleaseTestError("test signing key export is not ASCII armor") from error
    if "BEGIN PGP PRIVATE KEY BLOCK" not in signing_key:
        raise RealReleaseTestError("test signing key export is incomplete")
    return gnupg_home, passphrase, fingerprint, signing_key


def _write_python_recorder(directory: Path) -> Path:
    directory.mkdir(mode=0o700)
    recorder = directory / "python3"
    recorder.write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        "{\n"
        "  printf 'CALL\\0'\n"
        "  printf '%s\\0' \"$@\"\n"
        "  printf 'END\\0'\n"
        "} >> \"${PROCWRIGHT_ARGV_RECORD}\"\n"
        "exec \"${PROCWRIGHT_REAL_PYTHON}\" \"$@\"\n",
        encoding="utf-8",
    )
    recorder.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
    return recorder


def _parse_recorded_calls(path: Path) -> list[list[str]]:
    fields = path.read_bytes().split(b"\0")
    calls: list[list[str]] = []
    current: list[str] | None = None
    for field in fields:
        if field == b"CALL":
            if current is not None:
                raise RealReleaseTestError("nested Python argv record")
            current = []
        elif field == b"END":
            if current is None:
                raise RealReleaseTestError("unterminated Python argv record")
            calls.append(current)
            current = None
        elif field:
            if current is None:
                raise RealReleaseTestError("Python argv record has data outside a call")
            current.append(field.decode("utf-8"))
    if current is not None:
        raise RealReleaseTestError("Python argv record is truncated")
    return calls


def run_real_release(repository: Path, version: str) -> None:
    version = require_canonical_version(version)
    repository = repository.resolve(strict=True)
    expected_bases = expected_base_paths(version)
    actual_base_payloads = {
        path: (repository / path).read_bytes() for path in expected_bases
    }
    if any(not payload for payload in actual_base_payloads.values()):
        raise RealReleaseTestError("actual Gradle publication contains an empty base artifact")
    try:
        validate_base_payloads(actual_base_payloads, version)
    except EvidenceError as error:
        raise RealReleaseTestError(
            "actual Gradle publication does not match the exact release contract"
        ) from error
    for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module"):
        def matches(path: str) -> bool:
            return (
                path.endswith(f"-{version}.jar")
                if suffix == ".jar"
                else path.endswith(suffix)
            )

        digests = {
            hashlib.sha256(payload).hexdigest()
            for path, payload in actual_base_payloads.items()
            if matches(path)
        }
        if len(digests) != 3:
            raise RealReleaseTestError(
                f"actual module artifacts are not differentiated for {suffix}"
            )

    with tempfile.TemporaryDirectory(prefix="pw-release-", dir="/tmp") as temporary:
        root = Path(temporary)
        target_repository = root / ".procwright-target/build/maven-central/repository"
        shutil.copytree(repository, target_repository, copy_function=shutil.copy2)
        trusted_scripts = root / ".procwright-trusted/scripts"
        shutil.copytree(
            SCRIPTS_DIRECTORY,
            trusted_scripts,
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
        )
        python = sys.executable
        handoff_cli = trusted_scripts / "release_handoff.py"
        _run(
            [
                python,
                str(handoff_cli),
                "prepare",
                "--repository",
                ".procwright-target/build/maven-central/repository",
                "--output",
                ".procwright-target/build/release-handoff",
                "--version",
                version,
                "--commit",
                COMMIT,
                "--workflow-sha",
                COMMIT,
            ],
            cwd=root,
        )
        handoff_directory = root / ".procwright-handoff"
        handoff_directory.mkdir()
        github_artifact = handoff_directory / f"procwright-{version}-github-artifact.zip"
        sink = _StreamingZipSink()
        with zipfile.ZipFile(sink, "w", compression=zipfile.ZIP_STORED) as archive:
            for source in sorted(
                (root / ".procwright-target/build/release-handoff").iterdir()
            ):
                archive.write(source, source.name)
        github_artifact.write_bytes(sink.payload())
        github_artifact_digest = hashlib.sha256(github_artifact.read_bytes()).hexdigest()
        github_artifact_id = "123456789"
        github_artifact_name = f"procwright-{version}-{COMMIT}-unsigned-release"
        _run(
            [
                python,
                str(handoff_cli),
                "verify",
                "--handoff",
                ".procwright-handoff",
                "--output-repository",
                "build/maven-central/repository",
                "--version",
                version,
                "--commit",
                COMMIT,
                "--workflow-sha",
                COMMIT,
                "--github-artifact-digest",
                github_artifact_digest,
                "--github-artifact-id",
                github_artifact_id,
                "--github-artifact-name",
                github_artifact_name,
            ],
            cwd=root,
        )

        _source_home, _passphrase, _fingerprint, signing_key = _generate_test_key(root)
        runner_temp = root / "runner"
        runner_temp.mkdir(mode=0o700)
        argv_record = root / "python-argv.bin"
        recorder_directory = root / "recording-bin"
        _write_python_recorder(recorder_directory)
        environment = {
            **os.environ,
            "PATH": f"{recorder_directory}{os.pathsep}{os.environ.get('PATH', '')}",
            "PROCWRIGHT_ARGV_RECORD": str(argv_record),
            "PROCWRIGHT_REAL_PYTHON": python,
            "PROCWRIGHT_RELEASE_VERSION": version,
            "PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST": github_artifact_digest,
            "PROCWRIGHT_HANDOFF_ARTIFACT_ID": github_artifact_id,
            "PROCWRIGHT_HANDOFF_ARTIFACT_NAME": github_artifact_name,
            "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT": _fingerprint,
            "RUNNER_TEMP": str(runner_temp),
            "SIGNING_KEY": signing_key,
            "SIGNING_PASSWORD": "test-passphrase",
        }
        signing_script = trusted_scripts / "release/sign_release_handoff.sh"
        _run(["bash", str(signing_script)], cwd=root, environment=environment)

        trusted_root = (root / ".procwright-trusted").resolve()
        expected_calls = [
            [str(trusted_root / "scripts/release_contract.py"), "version", version],
            [
                str(trusted_root / "scripts/release_handoff.py"),
                "sign-finalize",
                "--repository",
                "build/maven-central/repository",
                "--manifest",
                f"build/maven-central/procwright-{version}-verified-unsigned-manifest.json",
                "--bundle",
                f"build/maven-central/procwright-{version}-maven-central-bundle.zip",
                "--version",
                version,
                "--gnupg-home",
                str(runner_temp / "procwright-release-gnupg"),
                "--fingerprint",
                _fingerprint,
                "--passphrase-file",
                str(runner_temp / "procwright-release-signing-passphrase"),
            ],
        ]
        if _parse_recorded_calls(argv_record) != expected_calls:
            raise RealReleaseTestError("signing wrapper Python argv is not the exact contract")

        bundle = root / f"build/maven-central/procwright-{version}-maven-central-bundle.zip"
        _run(
            [
                python,
                str(handoff_cli),
                "verify-signing-evidence",
                "--bundle",
                f"build/maven-central/procwright-{version}-maven-central-bundle.zip",
                "--evidence",
                f"build/maven-central/procwright-{version}-signing-evidence.json",
                "--public-key",
                f"build/maven-central/procwright-{version}-signing-public-key.asc",
                "--version",
                version,
                "--fingerprint",
                _fingerprint,
                "--github-artifact",
                ".procwright-handoff",
                "--github-artifact-digest",
                github_artifact_digest,
                "--github-artifact-id",
                github_artifact_id,
                "--github-artifact-name",
                github_artifact_name,
            ],
            cwd=root,
        )
        staged_manifest = root / "staged-manifest.json"
        _run(
            [
                python,
                str(trusted_scripts / "verify_maven_central_staged_bundle.py"),
                "generate-manifest",
                "--bundle",
                str(bundle),
                "--version",
                version,
                "--output",
                str(staged_manifest),
            ],
            cwd=root,
        )
        unsigned_manifest = json.loads(
            (
                root
                / f".procwright-target/build/release-handoff/procwright-{version}-unsigned-manifest.json"
            ).read_text(encoding="utf-8")
        )
        if len(unsigned_manifest["entries"]) != 15:
            raise RealReleaseTestError("unsigned handoff does not bind exactly 15 bases")
        for entry in unsigned_manifest["entries"]:
            if hashlib.sha256(actual_base_payloads[entry["path"]]).hexdigest() != entry[
                "sha256"
            ]:
                raise RealReleaseTestError("unsigned handoff base digest differs from Gradle output")
        final_manifest = json.loads(staged_manifest.read_text(encoding="utf-8"))
        if tuple(entry["path"] for entry in final_manifest["files"]) != expected_release_paths(
            version
        ):
            raise RealReleaseTestError("staged verifier did not prove the canonical 90 paths")

        local_postcondition_repository = root / "local-postcondition-repository"
        with zipfile.ZipFile(bundle) as archive:
            for name in expected_release_paths(version):
                destination = local_postcondition_repository / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))
        for name in expected_generated_metadata_paths():
            destination = local_postcondition_repository / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target_repository / name, destination)
        _run(
            [
                python,
                str(trusted_scripts / "verify_local_maven_central_bundle.py"),
                "--repository",
                str(local_postcondition_repository),
                "--bundle",
                str(bundle),
                "--version",
                version,
            ],
            cwd=root,
        )


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    options = parser.parse_args(arguments)
    try:
        run_real_release(options.repository, options.version)
    except (RealReleaseTestError, OSError, ValueError) as error:
        print(f"Real local release test failed: {error}", file=sys.stderr)
        return 1
    print(
        "Verified actual 3-module, 15-base, 90-file release roundtrip and "
        "local Gradle postcondition."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
