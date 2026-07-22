#!/usr/bin/env python3
"""Bounded, identity-preserving downloads from the canonical Maven repository."""

from __future__ import annotations

from collections.abc import Callable
import errno
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

from maven_central_portal import Deadline, PortalError
from release_contract import (
    CENTRAL_REPOSITORY,
    artifact_identity,
    expected_release_paths,
    require_canonical_version,
)


MAX_DOWNLOAD_ATTEMPTS = 12
MAX_RETRY_DELAY_SECONDS = 60.0
MAX_REQUEST_TIMEOUT_SECONDS = 120.0
MAX_OVERALL_DEADLINE_SECONDS = 30 * 60.0
READ_CHUNK_BYTES = 128 * 1024
TRANSIENT_HTTP_STATUSES = frozenset({404, 408, 429, *range(500, 600)})
TRANSIENT_ERRNOS = frozenset(
    {
        errno.ECONNABORTED,
        errno.ECONNREFUSED,
        errno.ECONNRESET,
        errno.EHOSTUNREACH,
        errno.ENETDOWN,
        errno.ENETUNREACH,
        errno.ETIMEDOUT,
    }
)

UrlOpener = Callable[..., object]
Sleeper = Callable[[float], None]


class RepositoryEvidenceError(RuntimeError):
    """Canonical Maven repository bytes could not be proved safely."""


class _TransientRepositoryError(RuntimeError):
    pass


def artifact_content_type(path: str, version: str) -> str:
    identity = artifact_identity(path, version)
    if identity.role in {"signature", "checksum"}:
        return "text/plain"
    if identity.base_suffix.endswith(".jar"):
        return "application/java-archive"
    if identity.base_suffix == ".pom":
        return "text/xml"
    return "application/vnd.org.gradle.module+json"


def canonical_artifact_url(path: str, version: str) -> str:
    artifact_identity(path, version)
    url = CENTRAL_REPOSITORY + urllib.parse.quote(path, safe="/")
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError as error:
        raise RepositoryEvidenceError("constructed an invalid Maven Central URL") from error
    if (
        parsed.scheme != "https"
        or parsed.hostname != "repo.maven.apache.org"
        or parsed.netloc != "repo.maven.apache.org"
        or port is not None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path.startswith("/maven2/io/github/ulviar/")
        or urllib.parse.urlunsplit(parsed) != url
    ):
        raise RepositoryEvidenceError("constructed a non-canonical Maven Central URL")
    return url


def _single_header(response: object, name: str) -> str | None:
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    if hasattr(headers, "get_all"):
        values = headers.get_all(name, [])
        if len(values) > 1:
            raise RepositoryEvidenceError(f"Maven Central repeated {name}")
        return values[0].strip() if values else None
    value = headers.get(name)
    return value.strip() if isinstance(value, str) else None


def _status(response: object) -> int:
    value = getattr(response, "status", None)
    if value is None and hasattr(response, "getcode"):
        value = response.getcode()
    if isinstance(value, bool) or not isinstance(value, int):
        raise RepositoryEvidenceError("Maven Central response has no exact HTTP status")
    return value


def _validate_headers(
    response: object,
    *,
    expected_url: str,
    expected_content_type: str,
    maximum_size: int,
    expected_size: int | None,
) -> int:
    if not hasattr(response, "geturl") or response.geturl() != expected_url:
        raise RepositoryEvidenceError(
            "Maven Central response final URL differs from the exact HTTPS request"
        )
    status = _status(response)
    if status != 200:
        if status in TRANSIENT_HTTP_STATUSES:
            raise _TransientRepositoryError(f"transient HTTP status {status}")
        raise RepositoryEvidenceError(
            f"Maven Central returned permanent HTTP status {status}"
        )
    content_type = _single_header(response, "Content-Type")
    if content_type is None or content_type.lower() != expected_content_type:
        raise RepositoryEvidenceError("Maven Central Content-Type is missing or unexpected")
    content_encoding = _single_header(response, "Content-Encoding")
    if content_encoding not in (None, "identity"):
        raise RepositoryEvidenceError("Maven Central Content-Encoding must be identity")
    if _single_header(response, "Transfer-Encoding") is not None:
        raise RepositoryEvidenceError("Maven Central response must use Content-Length")
    content_length = _single_header(response, "Content-Length")
    if (
        content_length is None
        or not content_length.isdecimal()
        or (content_length.startswith("0") and content_length != "0")
    ):
        raise RepositoryEvidenceError(
            "Maven Central Content-Length is missing or non-canonical"
        )
    declared_size = int(content_length)
    if declared_size > maximum_size:
        raise RepositoryEvidenceError("Maven Central payload exceeds its size limit")
    if expected_size is not None and declared_size != expected_size:
        raise RepositoryEvidenceError(
            "Maven Central Content-Length does not match the staged payload"
        )
    return declared_size


def _classify_network_error(error: BaseException) -> RuntimeError:
    reason = error.reason if isinstance(error, urllib.error.URLError) else error
    if isinstance(
        reason,
        (ssl.SSLCertVerificationError, ssl.CertificateError, ssl.SSLError),
    ):
        return RepositoryEvidenceError("Maven Central TLS verification failed permanently")
    if isinstance(reason, (TimeoutError, socket.timeout, ConnectionError, socket.gaierror)):
        return _TransientRepositoryError("transient Maven Central network failure")
    if isinstance(reason, OSError) and reason.errno in TRANSIENT_ERRNOS:
        return _TransientRepositoryError("transient Maven Central socket failure")
    return RepositoryEvidenceError("Maven Central request failed permanently")


def _set_read_timeout(response: object, timeout: float) -> None:
    candidates = (
        getattr(response, "fp", None),
        getattr(getattr(response, "fp", None), "raw", None),
        getattr(getattr(getattr(response, "fp", None), "raw", None), "_sock", None),
    )
    for candidate in reversed(candidates):
        setter = getattr(candidate, "settimeout", None)
        if callable(setter):
            setter(timeout)
            return


def _download_once(
    path: str,
    version: str,
    *,
    maximum_size: int,
    expected_size: int | None,
    opener: UrlOpener,
    request_timeout_seconds: float,
    deadline: Deadline,
) -> bytes:
    url = canonical_artifact_url(path, version)
    content_type = artifact_content_type(path, version)
    request = urllib.request.Request(
        url,
        headers={
            "Accept": content_type,
            "Accept-Encoding": "identity",
            "User-Agent": "procwright-maven-evidence/2",
        },
        method="GET",
    )
    try:
        try:
            response_context = opener(
                request, timeout=min(request_timeout_seconds, deadline.remaining())
            )
        except urllib.error.HTTPError as error:
            if error.code in TRANSIENT_HTTP_STATUSES:
                raise _TransientRepositoryError(
                    f"transient HTTP status {error.code}"
                ) from error
            raise RepositoryEvidenceError(
                f"Maven Central returned permanent HTTP status {error.code}"
            ) from error
        except Exception as error:
            raise _classify_network_error(error) from error
        with response_context as response:
            declared_size = _validate_headers(
                response,
                expected_url=url,
                expected_content_type=content_type,
                maximum_size=maximum_size,
                expected_size=expected_size,
            )
            payload = bytearray()
            while True:
                timeout = min(request_timeout_seconds, deadline.remaining())
                _set_read_timeout(response, timeout)
                try:
                    chunk = response.read(
                        min(READ_CHUNK_BYTES, declared_size + 1 - len(payload))
                    )
                except Exception as error:
                    raise _classify_network_error(error) from error
                deadline.remaining()
                if not chunk:
                    break
                payload.extend(chunk)
                if len(payload) > declared_size:
                    raise RepositoryEvidenceError(
                        "Maven Central payload exceeds its declared Content-Length"
                    )
            if len(payload) != declared_size:
                raise RepositoryEvidenceError(
                    "Maven Central payload length differs from Content-Length"
                )
            return bytes(payload)
    except (_TransientRepositoryError, RepositoryEvidenceError):
        raise


def download_artifact(
    path: str,
    version: str,
    *,
    maximum_size: int,
    expected_size: int | None = None,
    opener: UrlOpener = urllib.request.urlopen,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
    retry_delay_seconds: float = 5.0,
    request_timeout_seconds: float = 30.0,
    deadline: Deadline,
    sleeper: Sleeper = time.sleep,
) -> bytes:
    if isinstance(attempts, bool) or not isinstance(attempts, int) or not 1 <= attempts <= MAX_DOWNLOAD_ATTEMPTS:
        raise RepositoryEvidenceError("download attempts are outside their bound")
    if not 0 <= retry_delay_seconds <= MAX_RETRY_DELAY_SECONDS:
        raise RepositoryEvidenceError("retry delay is outside its bound")
    if not 0 < request_timeout_seconds <= MAX_REQUEST_TIMEOUT_SECONDS:
        raise RepositoryEvidenceError("request timeout is outside its bound")
    require_canonical_version(version)
    last_error: BaseException | None = None
    for attempt in range(1, attempts + 1):
        try:
            return _download_once(
                path,
                version,
                maximum_size=maximum_size,
                expected_size=expected_size,
                opener=opener,
                request_timeout_seconds=request_timeout_seconds,
                deadline=deadline,
            )
        except PortalError as error:
            raise RepositoryEvidenceError(str(error)) from error
        except _TransientRepositoryError as error:
            last_error = error
            if attempt < attempts:
                try:
                    remaining = deadline.remaining()
                except PortalError as deadline_error:
                    raise RepositoryEvidenceError(str(deadline_error)) from deadline_error
                if retry_delay_seconds >= remaining:
                    raise RepositoryEvidenceError(
                        "overall Maven Central deadline expired before retry"
                    ) from error
                sleeper(retry_delay_seconds)
                try:
                    deadline.remaining()
                except PortalError as deadline_error:
                    raise RepositoryEvidenceError(str(deadline_error)) from deadline_error
    raise RepositoryEvidenceError(
        f"transient Maven Central download remained unavailable after {attempts} attempts"
    ) from last_error


def download_release(
    version: str,
    *,
    size_limit: Callable[[str], int],
    opener: UrlOpener = urllib.request.urlopen,
    attempts: int = MAX_DOWNLOAD_ATTEMPTS,
    retry_delay_seconds: float = 5.0,
    request_timeout_seconds: float = 30.0,
    overall_deadline_seconds: float = 10 * 60.0,
    sleeper: Sleeper = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> dict[str, bytes]:
    version = require_canonical_version(version)
    if not 0 < overall_deadline_seconds <= MAX_OVERALL_DEADLINE_SECONDS:
        raise RepositoryEvidenceError("overall deadline is outside its bound")
    deadline = checked_deadline(overall_deadline_seconds, clock=clock)
    return {
        path: download_artifact(
            path,
            version,
            maximum_size=size_limit(path),
            opener=opener,
            attempts=attempts,
            retry_delay_seconds=retry_delay_seconds,
            request_timeout_seconds=request_timeout_seconds,
            deadline=deadline,
            sleeper=sleeper,
        )
        for path in expected_release_paths(version)
    }


def checked_deadline(seconds: float, *, clock: Callable[[], float] = time.monotonic) -> Deadline:
    if not 0 < seconds <= MAX_OVERALL_DEADLINE_SECONDS:
        raise RepositoryEvidenceError("overall deadline is outside its bound")
    try:
        return Deadline(seconds, clock=clock)
    except PortalError as error:
        raise RepositoryEvidenceError(str(error)) from error


__all__ = [
    "MAX_DOWNLOAD_ATTEMPTS",
    "MAX_OVERALL_DEADLINE_SECONDS",
    "MAX_REQUEST_TIMEOUT_SECONDS",
    "MAX_RETRY_DELAY_SECONDS",
    "RepositoryEvidenceError",
    "artifact_content_type",
    "canonical_artifact_url",
    "checked_deadline",
    "download_artifact",
    "download_release",
]
