"""Verify detached OpenPGP signatures against one explicitly approved key."""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from collections.abc import Mapping


MAX_PUBLIC_KEY_BYTES = 128 * 1024
MAX_SIGNATURE_BYTES = 128 * 1024
MAX_GPG_OUTPUT_BYTES = 128 * 1024
GPG_TIMEOUT_SECONDS = 120


class OpenPgpVerificationError(RuntimeError):
    """Approved OpenPGP trust material or a detached signature is invalid."""


def require_fingerprint(value: str) -> str:
    if re.fullmatch(r"[0-9A-F]{40}", value) is None:
        raise OpenPgpVerificationError(
            "approved signing fingerprint must be 40 uppercase hexadecimal characters"
        )
    return value


def _run_gpg(
    arguments: list[str],
    environment: dict[str, str],
    *,
    input_payload: bytes | None = None,
    pass_fds: tuple[int, ...] = (),
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            arguments,
            check=False,
            input=input_payload,
            stdin=subprocess.DEVNULL if input_payload is None else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            pass_fds=pass_fds,
            env=environment,
            timeout=GPG_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise OpenPgpVerificationError("GPG execution failed") from error
    if (
        len(result.stdout) > MAX_GPG_OUTPUT_BYTES
        or len(result.stderr) > MAX_GPG_OUTPUT_BYTES
    ):
        raise OpenPgpVerificationError("GPG output exceeds its size limit")
    return result


def _verification_environment(home: str) -> dict[str, str]:
    return {
        "GNUPGHOME": home,
        "HOME": home,
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
    }


def _import_approved_key(
    public_key: bytes, fingerprint: str, environment: dict[str, str]
) -> None:
    if (
        not public_key
        or len(public_key) > MAX_PUBLIC_KEY_BYTES
        or b"-----BEGIN PGP PUBLIC KEY BLOCK-----" not in public_key
        or b"PRIVATE KEY" in public_key
    ):
        raise OpenPgpVerificationError(
            "approved signing key must be a bounded armored public key"
        )
    imported = _run_gpg(
        [
            "gpg",
            "--no-options",
            "--batch",
            "--no-tty",
            "--quiet",
            "--import-options",
            "import-clean,import-minimal",
            "--import",
        ],
        environment,
        input_payload=public_key,
    )
    if imported.returncode != 0:
        raise OpenPgpVerificationError("approved signing key could not be imported")

    listed = _run_gpg(
        [
            "gpg",
            "--no-options",
            "--batch",
            "--no-tty",
            "--with-colons",
            "--fingerprint",
            "--list-keys",
        ],
        environment,
    )
    primary_fingerprints: list[str] = []
    awaiting_primary_fingerprint = False
    public_key_count = 0
    for line in listed.stdout.decode("ascii", errors="replace").splitlines():
        fields = line.split(":")
        if fields[0] == "pub":
            public_key_count += 1
            awaiting_primary_fingerprint = True
        elif fields[0] == "fpr" and awaiting_primary_fingerprint:
            primary_fingerprints.append(fields[9] if len(fields) > 9 else "")
            awaiting_primary_fingerprint = False
    secrets = _run_gpg(
        [
            "gpg",
            "--no-options",
            "--batch",
            "--no-tty",
            "--with-colons",
            "--list-secret-keys",
        ],
        environment,
    )
    if (
        listed.returncode != 0
        or public_key_count != 1
        or primary_fingerprints != [fingerprint]
        or secrets.returncode not in (0, 2)
        or any(
            line.startswith("sec:")
            for line in secrets.stdout.decode("ascii", errors="replace").splitlines()
        )
    ):
        raise OpenPgpVerificationError(
            "approved signing keyring does not contain exactly the expected public key"
        )


def _verify_signature(
    artifact: bytes,
    signature: bytes,
    fingerprint: str,
    environment: dict[str, str],
    artifact_path: str,
) -> None:
    if not signature or len(signature) > MAX_SIGNATURE_BYTES:
        raise OpenPgpVerificationError(
            f"detached signature is missing or oversized for {artifact_path}"
        )
    with tempfile.TemporaryFile() as artifact_file, tempfile.TemporaryFile() as signature_file:
        artifact_file.write(artifact)
        artifact_file.flush()
        artifact_file.seek(0)
        signature_file.write(signature)
        signature_file.flush()
        signature_file.seek(0)
        result = _run_gpg(
            [
                "gpg",
                "--no-options",
                "--batch",
                "--no-tty",
                "--quiet",
                "--no-auto-key-retrieve",
                "--status-fd",
                "1",
                "--verify",
                f"/dev/fd/{signature_file.fileno()}",
                f"/dev/fd/{artifact_file.fileno()}",
            ],
            environment,
            pass_fds=(signature_file.fileno(), artifact_file.fileno()),
        )
    status = result.stdout.decode("ascii", errors="replace")
    valid_signatures = [
        line.split()
        for line in status.splitlines()
        if line.startswith("[GNUPG:] VALIDSIG ")
    ]
    good_signatures = [
        line
        for line in status.splitlines()
        if line.startswith("[GNUPG:] GOODSIG ")
    ]
    rejected_statuses = (
        "BADSIG",
        "ERRSIG",
        "NO_PUBKEY",
        "EXPSIG",
        "EXPKEYSIG",
        "REVKEYSIG",
        "KEYREVOKED",
    )
    has_rejected_status = any(
        line.startswith(f"[GNUPG:] {marker} ")
        for line in status.splitlines()
        for marker in rejected_statuses
    )
    valid = valid_signatures[0] if len(valid_signatures) == 1 else ()
    if (
        result.returncode != 0
        or len(good_signatures) != 1
        or has_rejected_status
        or len(valid) != 12
        or re.fullmatch(r"[0-9A-F]{40}", valid[2]) is None
        or valid[11] != fingerprint
    ):
        raise OpenPgpVerificationError(
            f"detached signature is not trusted for {artifact_path}"
        )


def verify_release_signatures(
    payloads: Mapping[str, bytes],
    base_paths: tuple[str, ...],
    approved_public_key: bytes,
    approved_fingerprint: str,
) -> None:
    """Verify every base artifact with one isolated, exact approved keyring."""
    fingerprint = require_fingerprint(approved_fingerprint)
    with tempfile.TemporaryDirectory(prefix="procwright-central-gpg-") as directory:
        os.chmod(directory, 0o700)
        environment = _verification_environment(directory)
        _import_approved_key(approved_public_key, fingerprint, environment)
        for base_path in base_paths:
            signature_path = base_path + ".asc"
            artifact = payloads.get(base_path)
            signature = payloads.get(signature_path)
            if artifact is None or signature is None:
                raise OpenPgpVerificationError(
                    f"release signature pair is incomplete for {base_path}"
                )
            _verify_signature(
                artifact,
                signature,
                fingerprint,
                environment,
                base_path,
            )
