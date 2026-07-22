#!/usr/bin/env python3
"""Stage and verify the exact closed Procwright Maven Central release bundle."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import re
import sys
import time
import urllib.request
from pathlib import Path
from typing import BinaryIO, Callable

from maven_central_portal import (
    Deadline,
    PortalClient,
    PortalError,
    PortalIdentity,
    deployment_evidence,
    deployment_evidence_bytes,
    parse_deployment_evidence,
)
from maven_central_repository import (
    MAX_DOWNLOAD_ATTEMPTS,
    MAX_OVERALL_DEADLINE_SECONDS,
    MAX_REQUEST_TIMEOUT_SECONDS,
    MAX_RETRY_DELAY_SECONDS,
    RepositoryEvidenceError,
    checked_deadline,
    download_artifact,
)
from maven_release_evidence import (
    CHUNK_BYTES,
    MANIFEST_SCHEMA,
    MANIFEST_SCOPE,
    MAX_BUNDLE_BYTES,
    MAX_MANIFEST_BYTES,
    EvidenceError,
    artifact_size_limit,
    atomic_write,
    inspect_bundle,
    inspect_open_bundle,
    manifest_bytes,
    open_stable_regular_file,
    parse_manifest,
    read_stable_file,
    snapshot_replace,
    verify_manifest as verify_evidence_manifest,
)
from release_contract import (
    MODULES,
    ReleaseContractError,
    artifact_identity,
    expected_release_paths,
    require_canonical_version,
    require_commit_sha,
    require_deployment_id,
)


ALLOWED_MODULES = MODULES
MAX_CREDENTIAL_BYTES = 16 * 1024

UrlOpener = Callable[..., object]
Sleeper = Callable[[float], None]


class BundleVerificationError(RuntimeError):
    """The staged bundle or its persistent Maven Central evidence is invalid."""


def _translate(error: BaseException) -> BundleVerificationError:
    return BundleVerificationError(str(error))


def build_manifest(bundle_path: Path | str, version: str) -> dict:
    try:
        manifest, _payloads = inspect_bundle(bundle_path, version)
        return manifest
    except (EvidenceError, ReleaseContractError) as error:
        raise _translate(error) from error


def write_manifest(
    bundle_path: Path | str, output_path: Path | str, version: str
) -> dict:
    manifest = build_manifest(bundle_path, version)
    try:
        atomic_write(
            output_path,
            manifest_bytes(manifest),
            maximum_size=MAX_MANIFEST_BYTES,
        )
    except EvidenceError as error:
        raise _translate(error) from error
    return manifest


def _parse_manifest(content: bytes, version: str) -> dict:
    try:
        return parse_manifest(content, version)
    except (EvidenceError, ReleaseContractError) as error:
        raise _translate(error) from error


def _read_manifest(path: Path, version: str) -> tuple[dict, bytes]:
    try:
        content = read_stable_file(path, "staged bundle manifest", MAX_MANIFEST_BYTES)
        return parse_manifest(content, version), content
    except (EvidenceError, ReleaseContractError) as error:
        raise _translate(error) from error


def verify_manifest(
    bundle_path: Path | str, manifest_path: Path | str, version: str
) -> dict:
    try:
        return verify_evidence_manifest(bundle_path, manifest_path, version)
    except (EvidenceError, ReleaseContractError) as error:
        raise _translate(error) from error


def _hash_stream(stream: BinaryIO, expected_size: int) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while True:
        chunk = stream.read(CHUNK_BYTES)
        if not chunk:
            break
        size += len(chunk)
        if size > expected_size:
            raise BundleVerificationError(
                "stream exceeds its declared uncompressed size"
            )
        digest.update(chunk)
    return digest.hexdigest(), size


def _create_destination(path: Path) -> Path:
    if path.exists() or path.is_symlink():
        raise BundleVerificationError("evidence destination must not pre-exist")
    try:
        parent = path.parent.lstat()
    except OSError as error:
        raise BundleVerificationError(
            "evidence destination parent does not exist"
        ) from error
    if not os.path.isdir(path.parent) or os.path.islink(path.parent):
        raise BundleVerificationError(
            "evidence destination parent must be a real directory"
        )
    if hasattr(os, "getuid") and parent.st_uid != os.getuid():
        raise BundleVerificationError("evidence destination parent has the wrong owner")
    if parent.st_mode & 0o022:
        raise BundleVerificationError(
            "evidence destination parent must not be group/world writable"
        )
    path.mkdir(mode=0o700)
    return path.resolve(strict=True)


def verify_published_payloads(
    bundle_path: Path | str,
    manifest_path: Path | str,
    version: str,
    destination_path: Path | str,
    *,
    opener: UrlOpener = urllib.request.urlopen,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
    retry_delay_seconds: float = 5.0,
    timeout_seconds: float = 30.0,
    overall_deadline_seconds: float = 10 * 60.0,
    sleeper: Sleeper = time.sleep,
    clock: Callable[[], float] = time.monotonic,
    deadline: Deadline | None = None,
) -> dict:
    """Download and compare every staged file under one monotonic deadline."""
    try:
        version = require_canonical_version(version)
        manifest_payload = read_stable_file(
            manifest_path, "staged bundle manifest", MAX_MANIFEST_BYTES
        )
        provided_manifest = parse_manifest(manifest_payload, version)
        shared_deadline = (
            checked_deadline(overall_deadline_seconds, clock=clock)
            if deadline is None
            else deadline
        )
        shared_deadline.remaining()
        destination = _create_destination(Path(destination_path))
        with open_stable_regular_file(
            bundle_path, "staged bundle", MAX_BUNDLE_BYTES
        ) as source:
            expected_manifest, payloads = inspect_open_bundle(
                source, os.fstat(source.fileno()).st_size, version
            )
            if not hmac.compare_digest(
                manifest_payload, manifest_bytes(expected_manifest)
            ):
                raise BundleVerificationError(
                    "manifest does not match the exact staged bundle bytes"
                )
            destination_names: set[str] = set()
            for path in expected_release_paths(version):
                filename = path.rsplit("/", 1)[-1]
                if filename.casefold() in destination_names:
                    raise BundleVerificationError(
                        "release filenames collide in the evidence destination"
                    )
                destination_names.add(filename.casefold())
                identity = artifact_identity(path, version)
                downloaded = download_artifact(
                    path,
                    version,
                    maximum_size=artifact_size_limit(identity),
                    expected_size=len(payloads[path]),
                    opener=opener,
                    attempts=attempts,
                    retry_delay_seconds=retry_delay_seconds,
                    request_timeout_seconds=timeout_seconds,
                    deadline=shared_deadline,
                    sleeper=sleeper,
                )
                if not hmac.compare_digest(downloaded, payloads[path]):
                    raise BundleVerificationError(
                        "published payload differs from the exact staged bytes"
                    )
                atomic_write(
                    destination / filename,
                    downloaded,
                    maximum_size=artifact_size_limit(identity),
                )
        return provided_manifest
    except (
        EvidenceError,
        PortalError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        raise _translate(error) from error


def _checksum_bytes(digest: str, bundle: Path) -> bytes:
    return f"{digest}  {bundle.name}\n".encode("ascii")


def _require_new_outputs(paths: tuple[Path, ...]) -> None:
    if len({path.resolve(strict=False) for path in paths}) != len(paths):
        raise BundleVerificationError("release evidence output paths must be distinct")
    for path in paths:
        if path.exists() or path.is_symlink():
            raise BundleVerificationError(f"release evidence output must be new: {path}")


def stage_release(
    bundle_path: Path | str,
    manifest_path: Path | str,
    checksum_path: Path | str,
    deployment_path: Path | str,
    version: str,
    commit: str,
    *,
    client: PortalClient,
    deadline_seconds: float = 30 * 60.0,
) -> dict:
    """Snapshot, verify, identify, and upload one stable bundle inode."""
    bundle = Path(bundle_path)
    manifest_output = Path(manifest_path)
    checksum_output = Path(checksum_path)
    deployment_output = Path(deployment_path)
    identity = PortalIdentity(
        require_canonical_version(version), require_commit_sha(commit)
    )
    _require_new_outputs((manifest_output, checksum_output, deployment_output))
    try:
        deadline = checked_deadline(deadline_seconds)
        snapshot_digest = snapshot_replace(bundle)
        with open_stable_regular_file(
            bundle, "staged bundle", MAX_BUNDLE_BYTES
        ) as source:
            size = os.fstat(source.fileno()).st_size
            manifest, _payloads = inspect_open_bundle(source, size, identity.version)
            digest = manifest["bundle"]["sha256"]
            if not hmac.compare_digest(digest, snapshot_digest):
                raise BundleVerificationError("private bundle snapshot digest changed")
            deployment_id = client.upload(
                source,
                size,
                bundle.name,
                identity,
                digest,
                deadline,
            )
            state, purls = client.wait_for_state(
                identity, deployment_id, "VALIDATED", deadline
            )
        evidence = deployment_evidence(
            identity, deployment_id, state, digest, purls
        )
        atomic_write(
            manifest_output,
            manifest_bytes(manifest),
            maximum_size=MAX_MANIFEST_BYTES,
        )
        atomic_write(
            checksum_output,
            _checksum_bytes(digest, bundle),
            maximum_size=256,
        )
        atomic_write(
            deployment_output,
            deployment_evidence_bytes(evidence),
            maximum_size=MAX_MANIFEST_BYTES,
        )
        return evidence
    except (
        EvidenceError,
        PortalError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        raise _translate(error) from error


def _verify_checksum_file(bundle: Path, checksum_path: Path, digest: str) -> None:
    try:
        checksum = read_stable_file(checksum_path, "bundle checksum", 256)
    except EvidenceError as error:
        raise _translate(error) from error
    if not hmac.compare_digest(checksum, _checksum_bytes(digest, bundle)):
        raise BundleVerificationError("bundle checksum sidecar identity is invalid")


def wait_for_publication(
    bundle_path: Path | str,
    manifest_path: Path | str,
    checksum_path: Path | str,
    deployment_path: Path | str,
    version: str,
    commit: str,
    deployment_id: str,
    expected_bundle_sha256: str,
    *,
    client: PortalClient,
    deadline_seconds: float = 30 * 60.0,
    deadline: Deadline | None = None,
    clock: Callable[[], float] = time.monotonic,
) -> dict:
    bundle = Path(bundle_path)
    identity = PortalIdentity(
        require_canonical_version(version), require_commit_sha(commit)
    )
    deployment_id = require_deployment_id(deployment_id)
    if re.fullmatch(r"[0-9a-f]{64}", expected_bundle_sha256) is None:
        raise BundleVerificationError("expected staged bundle digest is malformed")
    try:
        manifest = verify_evidence_manifest(bundle, manifest_path, identity.version)
        digest = manifest["bundle"]["sha256"]
        if not hmac.compare_digest(digest, expected_bundle_sha256):
            raise BundleVerificationError(
                "staged bundle differs from the digest carried from artifact verification"
            )
        _verify_checksum_file(bundle, Path(checksum_path), digest)
        parse_deployment_evidence(
            read_stable_file(
                deployment_path, "deployment evidence", MAX_MANIFEST_BYTES
            ),
            identity,
            expected_deployment_id=deployment_id,
            expected_state="VALIDATED",
            expected_bundle_sha256=digest,
        )
        shared_deadline = (
            checked_deadline(deadline_seconds, clock=clock)
            if deadline is None
            else deadline
        )
        state, purls = client.wait_for_state(
            identity, deployment_id, "PUBLISHED", shared_deadline
        )
        evidence = deployment_evidence(
            identity, deployment_id, state, digest, purls
        )
        atomic_write(
            deployment_path,
            deployment_evidence_bytes(evidence),
            maximum_size=MAX_MANIFEST_BYTES,
            replace=True,
        )
        return evidence
    except (
        EvidenceError,
        PortalError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        raise _translate(error) from error


def wait_and_verify_publication(
    bundle_path: Path | str,
    manifest_path: Path | str,
    checksum_path: Path | str,
    deployment_path: Path | str,
    version: str,
    commit: str,
    deployment_id: str,
    expected_bundle_sha256: str,
    destination_path: Path | str,
    *,
    client: PortalClient,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
    retry_delay_seconds: float = 5.0,
    timeout_seconds: float = 30.0,
    deadline_seconds: float = 30 * 60.0,
    opener: UrlOpener = urllib.request.urlopen,
    sleeper: Sleeper = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> dict:
    """Prove publication state and all 90 bytes under one monotonic budget."""
    try:
        deadline = checked_deadline(deadline_seconds, clock=clock)
        wait_for_publication(
            bundle_path,
            manifest_path,
            checksum_path,
            deployment_path,
            version,
            commit,
            deployment_id,
            expected_bundle_sha256,
            client=client,
            deadline_seconds=deadline_seconds,
            deadline=deadline,
            clock=clock,
        )
        deadline.remaining()
        return verify_published_payloads(
            bundle_path,
            manifest_path,
            version,
            destination_path,
            opener=opener,
            attempts=attempts,
            retry_delay_seconds=retry_delay_seconds,
            timeout_seconds=timeout_seconds,
            overall_deadline_seconds=deadline_seconds,
            sleeper=sleeper,
            clock=clock,
            deadline=deadline,
        )
    except (
        BundleVerificationError,
        EvidenceError,
        PortalError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        if isinstance(error, BundleVerificationError):
            raise
        raise _translate(error) from error


def _read_credentials(path: Path) -> PortalClient:
    try:
        with open_stable_regular_file(
            path, "Central credential file", MAX_CREDENTIAL_BYTES
        ) as source:
            metadata = os.fstat(source.fileno())
            if metadata.st_mode & 0o077:
                raise BundleVerificationError(
                    "Central credential file must be owner-only"
                )
            payload = source.read(MAX_CREDENTIAL_BYTES + 1)
    except EvidenceError as error:
        raise _translate(error) from error
    except OSError as error:
        raise BundleVerificationError("cannot read Central credential file") from error
    if len(payload) > MAX_CREDENTIAL_BYTES:
        raise BundleVerificationError("Central credential file exceeds its size limit")
    fields = payload.split(b"\0")
    if len(fields) != 3 or fields[-1] or not fields[0] or not fields[1]:
        raise BundleVerificationError("Central credential file has an invalid shape")
    try:
        username, password = (field.decode("utf-8") for field in fields[:2])
    except UnicodeDecodeError as error:
        raise BundleVerificationError("Central credentials are not valid UTF-8") from error
    return PortalClient(username, password)


def _write_github_output(name: str, value: str) -> None:
    output = os.environ.get("GITHUB_OUTPUT", "")
    if not output:
        return
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or "\n" in value or "\r" in value:
        raise BundleVerificationError("unsafe GitHub output value")
    with open(output, "a", encoding="utf-8", newline="\n") as destination:
        destination.write(f"{name}={value}\n")


def _build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("generate-manifest", "verify-manifest"):
        selected = subparsers.add_parser(command)
        selected.add_argument("--bundle", type=Path, required=True)
        selected.add_argument("--version", required=True)
        selected.add_argument(
            "--output" if command == "generate-manifest" else "--manifest",
            type=Path,
            required=True,
        )
    stage = subparsers.add_parser("stage-central")
    for option in ("bundle", "manifest", "checksum", "deployment", "credentials-file"):
        stage.add_argument(f"--{option}", type=Path, required=True)
    stage.add_argument("--version", required=True)
    stage.add_argument("--commit", required=True)
    stage.add_argument("--deadline-seconds", type=float, default=30 * 60.0)
    published = subparsers.add_parser("wait-and-verify-central")
    for option in (
        "bundle",
        "manifest",
        "checksum",
        "deployment",
        "credentials-file",
        "destination",
    ):
        published.add_argument(f"--{option}", type=Path, required=True)
    published.add_argument("--version", required=True)
    published.add_argument("--commit", required=True)
    published.add_argument("--deployment-id", required=True)
    published.add_argument("--expected-bundle-sha256", required=True)
    published.add_argument("--attempts", type=int, default=MAX_DOWNLOAD_ATTEMPTS)
    published.add_argument("--retry-delay-seconds", type=float, default=5.0)
    published.add_argument("--timeout-seconds", type=float, default=30.0)
    published.add_argument("--deadline-seconds", type=float, default=30 * 60.0)
    return parser


def _safe_reason(error: BaseException) -> str:
    reason = "".join(
        character if 32 <= ord(character) < 127 else "?" for character in str(error)
    )
    return reason[:240] or "verification failed"


def main(argv: list[str] | None = None) -> int:
    arguments = _build_argument_parser().parse_args(argv)
    try:
        if arguments.command == "generate-manifest":
            manifest = write_manifest(
                arguments.bundle, arguments.output, arguments.version
            )
        elif arguments.command == "verify-manifest":
            manifest = verify_manifest(
                arguments.bundle, arguments.manifest, arguments.version
            )
        elif arguments.command == "stage-central":
            client = _read_credentials(arguments.credentials_file)
            evidence = stage_release(
                arguments.bundle,
                arguments.manifest,
                arguments.checksum,
                arguments.deployment,
                arguments.version,
                arguments.commit,
                client=client,
                deadline_seconds=arguments.deadline_seconds,
            )
            _write_github_output("bundle_sha256", evidence["bundleSha256"])
            _write_github_output("deployment_id", evidence["deploymentId"])
            print(f"Maven Central deployment state: {evidence['deploymentState']}")
            return 0
        elif arguments.command == "wait-and-verify-central":
            client = _read_credentials(arguments.credentials_file)
            evidence = wait_and_verify_publication(
                arguments.bundle,
                arguments.manifest,
                arguments.checksum,
                arguments.deployment,
                arguments.version,
                arguments.commit,
                arguments.deployment_id,
                arguments.expected_bundle_sha256,
                arguments.destination,
                client=client,
                attempts=arguments.attempts,
                retry_delay_seconds=arguments.retry_delay_seconds,
                timeout_seconds=arguments.timeout_seconds,
                deadline_seconds=arguments.deadline_seconds,
            )
            print(f"Maven Central deployment state: {evidence['deploymentState']}")
            return 0
        else:
            raise AssertionError("unhandled Maven Central evidence command")
        print(
            f"Verified {len(manifest['files'])} exact staged release files; "
            "server-generated metadata is outside this byte proof."
        )
        return 0
    except (
        BundleVerificationError,
        PortalError,
        ReleaseContractError,
        RepositoryEvidenceError,
    ) as error:
        print(f"Maven Central evidence failed: {_safe_reason(error)}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
