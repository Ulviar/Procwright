#!/usr/bin/env python3
"""Bounded native-HTTPS client for the Maven Central Publisher Portal."""

from __future__ import annotations

import base64
from collections.abc import Callable, Iterable
from dataclasses import dataclass
import errno
import http.client
import json
import re
import socket
import ssl
import time
import urllib.parse

from release_contract import (
    CENTRAL_REPOSITORY,
    GROUP_ID,
    PUBLISHING_TYPE,
    ReleaseContractError,
    deployment_name,
    expected_purls,
    require_canonical_version,
    require_commit_sha,
    require_deployment_id,
)


PORTAL_HOST = "central.sonatype.com"
PORTAL_ORIGIN = f"https://{PORTAL_HOST}"
UPLOAD_PATH = "/api/v1/publisher/upload"
STATUS_PATH = "/api/v1/publisher/status"
MAX_RESPONSE_BYTES = 64 * 1024
MAX_CREDENTIAL_BYTES = 4096
MAX_STATUS_ATTEMPTS = 180
MAX_REQUEST_ATTEMPTS = 4
MAX_BACKOFF_SECONDS = 10.0
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


class PortalError(RuntimeError):
    """Portal evidence could not be established without exposing remote content."""


class _TransientPortalError(RuntimeError):
    pass


@dataclass(frozen=True)
class PortalIdentity:
    version: str
    commit: str

    def __post_init__(self) -> None:
        require_canonical_version(self.version)
        require_commit_sha(self.commit)

    @property
    def deployment_name(self) -> str:
        return deployment_name(self.version, self.commit)

    @property
    def purls(self) -> tuple[str, ...]:
        return expected_purls(self.version)


@dataclass(frozen=True)
class ResponseData:
    status: int
    final_url: str
    headers: tuple[tuple[str, str], ...]
    body: bytes


class Deadline:
    """One monotonic budget shared by requests, retries, reads, and backoff."""

    def __init__(self, seconds: float, *, clock: Callable[[], float] = time.monotonic):
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or seconds <= 0:
            raise PortalError("deadline must be a positive number of seconds")
        self._clock = clock
        self._end = clock() + float(seconds)

    def remaining(self) -> float:
        remaining = self._end - self._clock()
        if remaining <= 0:
            raise PortalError("Maven Central evidence deadline expired")
        return remaining


Transport = Callable[
    [str, str, dict[str, str], int, Iterable[bytes], Deadline], ResponseData
]


def _single_header(headers: tuple[tuple[str, str], ...], name: str) -> str | None:
    matches = [value.strip() for key, value in headers if key.lower() == name.lower()]
    if len(matches) > 1:
        raise PortalError(f"Portal response repeats the {name} header")
    return matches[0] if matches else None


def _validate_response(
    response: ResponseData,
    *,
    expected_url: str,
    expected_status: int,
    expected_media_type: str,
) -> bytes:
    if response.final_url != expected_url:
        raise PortalError("Portal response URL differs from the exact HTTPS request URL")
    if response.status != expected_status:
        if response.status in TRANSIENT_HTTP_STATUSES:
            raise _TransientPortalError(f"transient HTTP status {response.status}")
        raise PortalError(f"Portal returned permanent HTTP status {response.status}")
    media_type = _single_header(response.headers, "Content-Type")
    if media_type is None or media_type.split(";", 1)[0].strip().lower() != expected_media_type:
        raise PortalError("Portal response Content-Type is missing or unexpected")
    encoding = _single_header(response.headers, "Content-Encoding")
    if encoding not in (None, "identity"):
        raise PortalError("Portal response Content-Encoding must be identity")
    if _single_header(response.headers, "Transfer-Encoding") is not None:
        raise PortalError("Portal response must use Content-Length, not Transfer-Encoding")
    content_length = _single_header(response.headers, "Content-Length")
    if (
        content_length is None
        or not content_length.isdecimal()
        or content_length.startswith("0")
        and content_length != "0"
    ):
        raise PortalError("Portal response Content-Length is missing or non-canonical")
    if int(content_length) != len(response.body):
        raise PortalError("Portal response Content-Length does not match the bounded body")
    if len(response.body) > MAX_RESPONSE_BYTES:
        raise PortalError("Portal response body exceeds its size limit")
    return response.body


def _network_error(error: BaseException) -> RuntimeError:
    if isinstance(error, (ssl.SSLCertVerificationError, ssl.CertificateError, ssl.SSLError)):
        return PortalError("Portal TLS verification failed permanently")
    if isinstance(error, (TimeoutError, socket.timeout, ConnectionError, socket.gaierror)):
        return _TransientPortalError("transient Portal network failure")
    if isinstance(error, OSError) and error.errno in TRANSIENT_ERRNOS:
        return _TransientPortalError("transient Portal socket failure")
    return PortalError("Portal request failed permanently")


def _read_response_body(response: http.client.HTTPResponse, deadline: Deadline) -> bytes:
    content_length = response.getheader("Content-Length")
    if content_length is not None:
        if not content_length.isdecimal() or int(content_length) > MAX_RESPONSE_BYTES:
            raise PortalError("Portal response Content-Length exceeds its bound")
    body = bytearray()
    while True:
        if response.fp is not None and getattr(response.fp, "raw", None) is not None:
            socket_object = getattr(response.fp.raw, "_sock", None)
            if socket_object is not None:
                socket_object.settimeout(deadline.remaining())
        chunk = response.read(min(16 * 1024, MAX_RESPONSE_BYTES + 1 - len(body)))
        if not chunk:
            break
        body.extend(chunk)
        if len(body) > MAX_RESPONSE_BYTES:
            raise PortalError("Portal response body exceeds its size limit")
    return bytes(body)


def native_transport(
    method: str,
    url: str,
    headers: dict[str, str],
    content_length: int,
    chunks: Iterable[bytes],
    deadline: Deadline,
) -> ResponseData:
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname != PORTAL_HOST
        or parsed.port is not None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise PortalError("Portal transport received a non-canonical HTTPS URL")
    connection = http.client.HTTPSConnection(
        PORTAL_HOST,
        timeout=deadline.remaining(),
        context=ssl.create_default_context(),
    )
    try:
        connection.putrequest(
            method,
            parsed.path + ("?" + parsed.query if parsed.query else ""),
            skip_accept_encoding=True,
        )
        for name, value in headers.items():
            connection.putheader(name, value)
        connection.putheader("Content-Length", str(content_length))
        connection.endheaders()
        sent = 0
        for chunk in chunks:
            if not chunk:
                continue
            if connection.sock is not None:
                connection.sock.settimeout(deadline.remaining())
            connection.send(chunk)
            sent += len(chunk)
            if sent > content_length:
                raise PortalError("Portal request body exceeds its declared size")
        if sent != content_length:
            raise PortalError("Portal request body does not match its declared size")
        if connection.sock is not None:
            connection.sock.settimeout(deadline.remaining())
        response = connection.getresponse()
        body = _read_response_body(response, deadline)
        return ResponseData(
            response.status,
            url,
            tuple(response.getheaders()),
            body,
        )
    except PortalError:
        raise
    except Exception as error:
        raise _network_error(error) from error
    finally:
        connection.close()


def _json_without_duplicates(payload: bytes) -> dict:
    def reject_duplicates(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise PortalError("Portal status JSON contains duplicate keys")
            value[key] = item
        return value

    try:
        value = json.loads(payload, object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PortalError("Portal status response is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise PortalError("Portal status response must be a JSON object")
    return value


def parse_status(
    payload: bytes,
    identity: PortalIdentity,
    deployment_id: str,
) -> tuple[str, tuple[str, ...]]:
    try:
        deployment_id = require_deployment_id(deployment_id)
    except ReleaseContractError as error:
        raise PortalError("Portal deployment ID is malformed") from error
    status = _json_without_duplicates(payload)
    state = status.get("deploymentState")
    allowed_states = {
        "PENDING",
        "VALIDATING",
        "VALIDATED",
        "PUBLISHING",
        "PUBLISHED",
        "FAILED",
    }
    expected_keys = {
        "deploymentId",
        "deploymentName",
        "deploymentState",
        "purls",
    } | ({"errors"} if state == "FAILED" else set())
    if set(status) != expected_keys:
        raise PortalError("Portal status JSON has missing or extra identity fields")
    if (
        status.get("deploymentId") != deployment_id
        or status.get("deploymentName") != identity.deployment_name
        or not isinstance(state, str)
        or state not in allowed_states
    ):
        raise PortalError("Portal status identity does not match the requested deployment")
    purls = status.get("purls")
    if (
        not isinstance(purls, list)
        or len(purls) > len(identity.purls)
        or any(not isinstance(purl, str) for purl in purls)
        or len(set(purls)) != len(purls)
    ):
        raise PortalError("Portal status purls are malformed")
    observed_purls = tuple(sorted(purls))
    expected = tuple(sorted(identity.purls))
    if state in {"VALIDATED", "PUBLISHING", "PUBLISHED"} and observed_purls != expected:
        raise PortalError("Portal status purls do not match all release modules")
    if state in {"PENDING", "VALIDATING"} and not set(observed_purls) <= set(expected):
        raise PortalError("Portal status contains an unrelated package identity")
    if state == "FAILED":
        errors = status.get("errors")
        if not isinstance(errors, list) or len(errors) > 100:
            raise PortalError("Portal failed-status diagnostics are malformed")
    return state, observed_purls


def deployment_evidence(
    identity: PortalIdentity,
    deployment_id: str,
    state: str,
    bundle_sha256: str,
    purls: tuple[str, ...],
) -> dict:
    try:
        deployment_id = require_deployment_id(deployment_id)
    except ReleaseContractError as error:
        raise PortalError("persisted deployment ID is malformed") from error
    if state not in {"VALIDATED", "PUBLISHED"}:
        raise PortalError("only terminal validated or published states may be persisted")
    if tuple(sorted(purls)) != tuple(sorted(identity.purls)):
        raise PortalError("persisted deployment purls do not match the release")
    if not isinstance(bundle_sha256, str) or len(bundle_sha256) != 64 or any(
        character not in "0123456789abcdef" for character in bundle_sha256
    ):
        raise PortalError("persisted bundle digest is malformed")
    return {
        "bundleSha256": bundle_sha256,
        "deploymentId": deployment_id,
        "deploymentName": identity.deployment_name,
        "deploymentState": state,
        "namespace": GROUP_ID,
        "publishingType": PUBLISHING_TYPE,
        "purls": list(sorted(purls)),
        "repository": CENTRAL_REPOSITORY,
        "schema": "procwright-central-deployment-evidence/v1",
        "version": identity.version,
    }


def deployment_evidence_bytes(evidence: dict) -> bytes:
    return (
        json.dumps(evidence, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def deployment_id_from_evidence(payload: bytes) -> str:
    """Extract only a bounded canonical UUID before exact evidence validation."""
    if not isinstance(payload, bytes) or len(payload) > MAX_RESPONSE_BYTES:
        raise PortalError("persisted deployment evidence exceeds its size limit")
    evidence = _json_without_duplicates(payload)
    try:
        return require_deployment_id(evidence.get("deploymentId", ""))
    except ReleaseContractError as error:
        raise PortalError("persisted deployment ID is malformed") from error


def parse_deployment_evidence(
    payload: bytes,
    identity: PortalIdentity,
    *,
    expected_deployment_id: str,
    expected_state: str,
    expected_bundle_sha256: str,
) -> dict:
    if not isinstance(payload, bytes) or len(payload) > MAX_RESPONSE_BYTES:
        raise PortalError("persisted deployment evidence exceeds its size limit")
    evidence = _json_without_duplicates(payload)
    expected = deployment_evidence(
        identity,
        expected_deployment_id,
        expected_state,
        expected_bundle_sha256,
        identity.purls,
    )
    if evidence != expected or payload != deployment_evidence_bytes(expected):
        raise PortalError("persisted deployment evidence is not exact or canonical")
    return evidence


class PortalClient:
    """Portal client that keeps credentials in HTTPS headers inside this process only."""

    def __init__(
        self,
        username: str,
        password: str,
        *,
        transport: Transport = native_transport,
        sleeper: Callable[[float], None] = time.sleep,
    ):
        if (
            not username
            or not password
            or len(username.encode("utf-8")) > MAX_CREDENTIAL_BYTES
            or len(password.encode("utf-8")) > MAX_CREDENTIAL_BYTES
            or ":" in username
            or any(ord(character) < 32 or ord(character) == 127 for character in username + password)
        ):
            raise PortalError("Central credentials are missing or contain control characters")
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self._authorization = f"Bearer {token}"
        self._transport = transport
        self._sleeper = sleeper

    def _headers(self, content_type: str) -> dict[str, str]:
        return {
            "Accept": "application/json, text/plain;q=0.9",
            "Accept-Encoding": "identity",
            "Authorization": self._authorization,
            "Content-Type": content_type,
            "User-Agent": "procwright-central-evidence/1",
        }

    def upload(
        self,
        source: BinaryIO,
        size: int,
        filename: str,
        identity: PortalIdentity,
        bundle_sha256: str,
        deadline: Deadline,
    ) -> str:
        if (
            filename != f"procwright-{identity.version}-maven-central-bundle.zip"
            or isinstance(size, bool)
            or not isinstance(size, int)
            or size <= 0
            or not re.fullmatch(r"[0-9a-f]{64}", bundle_sha256)
        ):
            raise PortalError("Portal bundle identity is malformed")
        boundary = f"procwright-{bundle_sha256}"
        boundary_bytes = boundary.encode("ascii")
        source.seek(0)
        while True:
            deadline.remaining()
            chunk = source.read(128 * 1024)
            if not chunk:
                break
            if boundary_bytes in chunk:
                raise PortalError("deterministic multipart boundary collides with bundle bytes")
        prefix = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="bundle"; filename="{filename}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode("ascii")
        suffix = f"\r\n--{boundary}--\r\n".encode("ascii")

        def chunks() -> Iterable[bytes]:
            yield prefix
            source.seek(0)
            remaining = size
            while remaining:
                deadline.remaining()
                chunk = source.read(min(128 * 1024, remaining))
                if not chunk:
                    raise PortalError("bundle ended during Portal upload")
                remaining -= len(chunk)
                yield chunk
            if source.read(1):
                raise PortalError("bundle grew during Portal upload")
            yield suffix

        query = urllib.parse.urlencode(
            {"name": identity.deployment_name, "publishingType": PUBLISHING_TYPE},
            quote_via=urllib.parse.quote,
            safe="",
        )
        url = f"{PORTAL_ORIGIN}{UPLOAD_PATH}?{query}"
        try:
            response = self._transport(
                "POST",
                url,
                self._headers(f"multipart/form-data; boundary={boundary}"),
                len(prefix) + size + len(suffix),
                chunks(),
                deadline,
            )
            deadline.remaining()
            body = _validate_response(
                response,
                expected_url=url,
                expected_status=201,
                expected_media_type="text/plain",
            )
        except _TransientPortalError as error:
            raise PortalError(
                "Portal upload outcome is uncertain; inspect the Portal before retrying"
            ) from error
        try:
            deployment_id = body.decode("ascii")
        except UnicodeDecodeError as error:
            raise PortalError("Portal upload response is not an ASCII deployment ID") from error
        if deployment_id.endswith("\n"):
            deployment_id = deployment_id[:-1]
        try:
            return require_deployment_id(deployment_id)
        except ReleaseContractError as error:
            raise PortalError("Portal upload response is not a canonical deployment ID") from error

    def status(
        self,
        identity: PortalIdentity,
        deployment_id: str,
        deadline: Deadline,
        *,
        attempts: int = MAX_REQUEST_ATTEMPTS,
    ) -> tuple[str, tuple[str, ...]]:
        try:
            deployment_id = require_deployment_id(deployment_id)
        except ReleaseContractError as error:
            raise PortalError("Portal deployment ID is malformed") from error
        if not 1 <= attempts <= MAX_REQUEST_ATTEMPTS:
            raise PortalError("Portal request attempt count is outside its bound")
        url = f"{PORTAL_ORIGIN}{STATUS_PATH}?id={deployment_id}"
        last_error = None
        for attempt in range(1, attempts + 1):
            try:
                response = self._transport(
                    "POST",
                    url,
                    self._headers("application/octet-stream"),
                    0,
                    (),
                    deadline,
                )
                deadline.remaining()
                payload = _validate_response(
                    response,
                    expected_url=url,
                    expected_status=200,
                    expected_media_type="application/json",
                )
                return parse_status(payload, identity, deployment_id)
            except _TransientPortalError as error:
                last_error = error
                if attempt < attempts:
                    delay = min(float(2 ** (attempt - 1)), MAX_BACKOFF_SECONDS)
                    if delay >= deadline.remaining():
                        raise PortalError("Portal evidence deadline expired before retry") from error
                    self._sleeper(delay)
        raise PortalError(f"Portal status remained unavailable after {attempts} attempts") from last_error

    def wait_for_state(
        self,
        identity: PortalIdentity,
        deployment_id: str,
        target_state: str,
        deadline: Deadline,
        *,
        poll_seconds: float = 10.0,
        maximum_polls: int = MAX_STATUS_ATTEMPTS,
    ) -> tuple[str, tuple[str, ...]]:
        if target_state not in {"VALIDATED", "PUBLISHED"}:
            raise PortalError("unsupported Portal target state")
        if not 1 <= maximum_polls <= MAX_STATUS_ATTEMPTS or poll_seconds < 0:
            raise PortalError("Portal polling bounds are invalid")
        allowed_before = {
            "VALIDATED": {"PENDING", "VALIDATING"},
            "PUBLISHED": {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"},
        }[target_state]
        for poll in range(maximum_polls):
            state, purls = self.status(identity, deployment_id, deadline)
            if state == target_state:
                return state, purls
            if state == "FAILED":
                raise PortalError("Portal deployment entered FAILED state")
            if state not in allowed_before:
                raise PortalError("Portal deployment entered an unexpected state")
            if poll + 1 < maximum_polls and poll_seconds:
                if poll_seconds >= deadline.remaining():
                    raise PortalError("Portal evidence deadline expired before next poll")
                self._sleeper(poll_seconds)
        raise PortalError(f"Portal deployment did not reach {target_state} within its poll limit")
