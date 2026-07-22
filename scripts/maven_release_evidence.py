#!/usr/bin/env python3
"""Bounded structural and semantic evidence for one Procwright Maven release."""

from __future__ import annotations

import base64
import binascii
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import io
import json
import os
import posixpath
import re
import secrets
import stat
import struct
import unicodedata
import zipfile
from pathlib import Path
from typing import BinaryIO, Iterator
from xml.etree import ElementTree

from release_contract import (
    BASE_SUFFIXES,
    CHECKSUMS,
    DEVELOPER_ID,
    DEVELOPER_NAME,
    GROUP_ID,
    LICENSE_NAME,
    LICENSE_URL,
    MODULES,
    POM_SCHEMA_LOCATION,
    PROJECT_URL,
    SCM_CONNECTION,
    SCM_DEVELOPER_CONNECTION,
    SCM_URL,
    ArtifactIdentity,
    expected_module_dependencies,
    expected_base_paths,
    expected_pom_dependencies,
    expected_release_artifacts,
    expected_release_paths,
    module_semantic_contract,
    require_canonical_version,
)


MANIFEST_SCHEMA = "procwright-maven-release-evidence/v2"
MANIFEST_SCOPE = (
    "every file in the closed Procwright Maven release bundle; Maven Central "
    "server-generated metadata outside that bundle is not claimed"
)
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
MAX_MANIFEST_BYTES = 256 * 1024
MAX_CENTRAL_DIRECTORY_BYTES = 2 * 1024 * 1024
MAX_RAW_NAME_BYTES = 512
MAX_ENTRY_BYTES = 64 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_TOTAL_COMPRESSED_BYTES = 256 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MAX_NESTED_ENTRIES = 20_000
MAX_NESTED_CENTRAL_DIRECTORY_BYTES = 4 * 1024 * 1024
MAX_NESTED_ENTRY_BYTES = 32 * 1024 * 1024
MAX_NESTED_TOTAL_BYTES = 128 * 1024 * 1024
MAX_SIGNATURE_BYTES = 64 * 1024
MAX_POM_BYTES = 1024 * 1024
MAX_MODULE_BYTES = 4 * 1024 * 1024
CHUNK_BYTES = 128 * 1024

EOCD = struct.Struct("<4s4H2LH")
CENTRAL_HEADER = struct.Struct("<4s6H3L5H2L")
LOCAL_HEADER = struct.Struct("<4s5H3L2H")
SIGNED_DATA_DESCRIPTOR = struct.Struct("<4s3L")


class EvidenceError(RuntimeError):
    """Release bytes violate the bounded evidence contract."""


@dataclass(frozen=True)
class RawZipEntry:
    """Security-relevant central-directory fields before zipfile normalization."""

    name: str
    raw_name: bytes
    flags: int
    compression: int
    compressed_size: int
    uncompressed_size: int
    local_header_offset: int


@dataclass(frozen=True)
class StableFileIdentity:
    """Identity and metadata that must remain stable while a file is consumed."""

    device: int
    inode: int
    size: int
    mode: int
    links: int
    owner: int
    modified_ns: int
    changed_ns: int

    @classmethod
    def from_stat(cls, metadata: os.stat_result) -> "StableFileIdentity":
        return cls(
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_size,
            metadata.st_mode,
            metadata.st_nlink,
            metadata.st_uid,
            metadata.st_mtime_ns,
            metadata.st_ctime_ns,
        )


def _require_safe_metadata(
    metadata: os.stat_result, label: str, maximum_size: int
) -> StableFileIdentity:
    identity = StableFileIdentity.from_stat(metadata)
    if not stat.S_ISREG(identity.mode):
        raise EvidenceError(f"{label} must be a regular non-symlink file")
    if identity.links != 1:
        raise EvidenceError(f"{label} must have exactly one filesystem link")
    if hasattr(os, "getuid") and identity.owner != os.getuid():
        raise EvidenceError(f"{label} must be owned by the current user")
    if identity.mode & (stat.S_IWGRP | stat.S_IWOTH | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH):
        raise EvidenceError(f"{label} has unsafe write or execute mode bits")
    if identity.size < 0 or identity.size > maximum_size:
        raise EvidenceError(f"{label} exceeds its {maximum_size}-byte size limit")
    return identity


@contextmanager
def open_stable_regular_file(
    path: Path | str, label: str, maximum_size: int
) -> Iterator[BinaryIO]:
    """Open one owned, single-link inode and prove it did not change or get replaced."""
    file_path = Path(path)
    try:
        before = _require_safe_metadata(file_path.lstat(), label, maximum_size)
    except OSError as error:
        raise EvidenceError(f"cannot inspect {label}") from error
    flags = os.O_RDONLY
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(file_path, flags)
    except OSError as error:
        raise EvidenceError(f"cannot safely open {label}") from error
    opened = False
    try:
        after_open = _require_safe_metadata(os.fstat(descriptor), label, maximum_size)
        if after_open != before:
            raise EvidenceError(f"{label} changed between lstat and open")
        with os.fdopen(descriptor, "rb", closefd=True) as source:
            opened = True
            yield source
            try:
                after_read = _require_safe_metadata(
                    os.fstat(source.fileno()), label, maximum_size
                )
                path_after_read = _require_safe_metadata(
                    file_path.lstat(), label, maximum_size
                )
            except (OSError, EvidenceError) as error:
                raise EvidenceError(f"{label} changed or was replaced while open") from error
            if after_read != before or path_after_read != before:
                raise EvidenceError(f"{label} changed or was replaced while open")
    finally:
        if not opened:
            os.close(descriptor)


def read_stable_file(path: Path | str, label: str, maximum_size: int) -> bytes:
    with open_stable_regular_file(path, label, maximum_size) as source:
        payload = source.read(maximum_size + 1)
    if len(payload) > maximum_size:
        raise EvidenceError(f"{label} exceeds its size limit")
    return payload


def _require_output_parent(path: Path) -> int:
    parent = path.parent
    try:
        metadata = parent.lstat()
    except OSError as error:
        raise EvidenceError(f"cannot inspect output parent: {parent}") from error
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise EvidenceError(f"output parent must be a real directory: {parent}")
    if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
        raise EvidenceError(f"output parent must be owned by the current user: {parent}")
    if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        raise EvidenceError(f"output parent must not be group/world writable: {parent}")
    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    return os.open(parent, flags)


def atomic_write(
    path: Path | str,
    payload: bytes,
    *,
    maximum_size: int,
    replace: bool = False,
) -> None:
    """Write an owner-only file through fsync and atomic same-directory rename."""
    destination = Path(path)
    if len(payload) > maximum_size:
        raise EvidenceError("atomic output exceeds its size limit")
    if not replace and (destination.exists() or destination.is_symlink()):
        raise EvidenceError(f"atomic output must not pre-exist: {destination}")
    if replace and (destination.exists() or destination.is_symlink()):
        with open_stable_regular_file(destination, "replaced evidence", maximum_size):
            pass
    parent_descriptor = _require_output_parent(destination)
    temporary = destination.with_name(
        f".{destination.name}.{secrets.token_hex(12)}.tmp"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = -1
    try:
        descriptor = os.open(temporary, flags, 0o600)
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise EvidenceError("atomic output write made no progress")
            view = view[written:]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        os.replace(temporary, destination)
        os.fsync(parent_descriptor)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(parent_descriptor)


def snapshot_replace(path: Path | str, maximum_size: int = MAX_BUNDLE_BYTES) -> str:
    """Replace a source file atomically with a private byte-for-byte snapshot."""
    source_path = Path(path)
    temporary = source_path.with_name(
        f".{source_path.name}.{secrets.token_hex(12)}.snapshot"
    )
    parent_descriptor = _require_output_parent(source_path)
    digest = hashlib.sha256()
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    output_descriptor = -1
    try:
        output_descriptor = os.open(temporary, flags, 0o600)
        with open_stable_regular_file(source_path, "staged bundle", maximum_size) as source:
            copied = 0
            while True:
                chunk = source.read(CHUNK_BYTES)
                if not chunk:
                    break
                copied += len(chunk)
                if copied > maximum_size:
                    raise EvidenceError("staged bundle exceeds its size limit")
                digest.update(chunk)
                view = memoryview(chunk)
                while view:
                    written = os.write(output_descriptor, view)
                    if written <= 0:
                        raise EvidenceError("snapshot write made no progress")
                    view = view[written:]
        os.fsync(output_descriptor)
        os.close(output_descriptor)
        output_descriptor = -1
        os.replace(temporary, source_path)
        os.fsync(parent_descriptor)
        return digest.hexdigest()
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    finally:
        if output_descriptor >= 0:
            os.close(output_descriptor)
        os.close(parent_descriptor)


def _read_exact_at(source: BinaryIO, offset: int, size: int, label: str) -> bytes:
    source.seek(offset)
    payload = source.read(size)
    if len(payload) != size:
        raise EvidenceError(f"truncated {label}")
    return payload


def _normalize_archive_name(name: str, *, allow_directory: bool) -> str:
    if not name or len(name.encode("utf-8")) > MAX_RAW_NAME_BYTES:
        raise EvidenceError("archive entry name is empty or too long")
    if "\x00" in name or "\\" in name or any(ord(character) < 32 for character in name):
        raise EvidenceError("archive entry name contains NUL, control, or backslash")
    if name.startswith("/") or re.match(r"[A-Za-z]:", name):
        raise EvidenceError("archive entry name is absolute")
    directory = name.endswith("/")
    if directory and not allow_directory:
        raise EvidenceError("outer release bundle must not contain directory entries")
    stripped = name[:-1] if directory else name
    components = stripped.split("/")
    if not stripped or any(component in ("", ".", "..") for component in components):
        raise EvidenceError("archive entry name contains unsafe path components")
    normalized = unicodedata.normalize("NFC", posixpath.normpath(stripped))
    if normalized != stripped:
        raise EvidenceError("archive entry name is not canonical NFC")
    return normalized + ("/" if directory else "")


def raw_central_directory(
    source: BinaryIO,
    file_size: int,
    *,
    maximum_entries: int,
    maximum_directory_bytes: int,
    maximum_entry_bytes: int,
    maximum_total_compressed_bytes: int,
    maximum_total_bytes: int,
    allow_directories: bool,
    allow_signed_data_descriptors: bool = False,
) -> list[RawZipEntry]:
    """Parse raw central-directory names and bounds before zipfile materializes entries."""
    if file_size < EOCD.size:
        raise EvidenceError("archive is too small to contain an end record")
    tail_size = min(file_size, 65_535 + EOCD.size)
    tail = _read_exact_at(source, file_size - tail_size, tail_size, "ZIP tail")
    marker = tail.rfind(b"PK\x05\x06")
    if marker < 0:
        raise EvidenceError("archive has no end-of-central-directory record")
    eocd_offset = file_size - tail_size + marker
    values = EOCD.unpack_from(tail, marker)
    (
        signature,
        disk,
        central_disk,
        disk_entries,
        total_entries,
        central_size,
        central_offset,
        comment_length,
    ) = values
    if signature != b"PK\x05\x06" or disk != 0 or central_disk != 0:
        raise EvidenceError("multi-disk ZIP archives are not allowed")
    if disk_entries != total_entries or total_entries in (0xFFFF,):
        raise EvidenceError("ZIP64 or inconsistent entry counts are not allowed")
    if total_entries > maximum_entries:
        raise EvidenceError("archive contains too many entries")
    if comment_length != 0 or eocd_offset + EOCD.size != file_size:
        raise EvidenceError("archive comments or trailing bytes are not allowed")
    if central_size > maximum_directory_bytes:
        raise EvidenceError("archive central directory exceeds its size limit")
    if central_offset + central_size != eocd_offset:
        raise EvidenceError("archive central-directory bounds are inconsistent")
    central = _read_exact_at(source, central_offset, central_size, "central directory")
    cursor = 0
    result = []
    seen_names: set[str] = set()
    seen_casefold: set[str] = set()
    local_ranges: list[tuple[int, int]] = []
    total_compressed = 0
    total_uncompressed = 0
    for _index in range(total_entries):
        if cursor + CENTRAL_HEADER.size > len(central):
            raise EvidenceError("truncated central-directory header")
        fields = CENTRAL_HEADER.unpack_from(central, cursor)
        if fields[0] != b"PK\x01\x02":
            raise EvidenceError("invalid central-directory signature")
        flags = fields[3]
        compression = fields[4]
        compressed_size = fields[8]
        uncompressed_size = fields[9]
        name_length = fields[10]
        extra_length = fields[11]
        entry_comment_length = fields[12]
        disk_start = fields[13]
        local_offset = fields[16]
        record_size = CENTRAL_HEADER.size + name_length + extra_length + entry_comment_length
        if name_length == 0 or name_length > MAX_RAW_NAME_BYTES:
            raise EvidenceError("raw central-directory name length is unsafe")
        if cursor + record_size > len(central):
            raise EvidenceError("truncated central-directory record")
        if extra_length or entry_comment_length or disk_start:
            raise EvidenceError("ZIP extra fields, comments, and split entries are not allowed")
        raw_name = central[
            cursor + CENTRAL_HEADER.size : cursor + CENTRAL_HEADER.size + name_length
        ]
        if b"\x00" in raw_name:
            raise EvidenceError("raw central-directory name contains NUL")
        try:
            name = raw_name.decode("utf-8" if flags & 0x800 else "cp437")
        except UnicodeDecodeError as error:
            raise EvidenceError("raw central-directory name cannot be decoded") from error
        has_non_ascii = any(byte >= 0x80 for byte in raw_name)
        if has_non_ascii and not flags & 0x800:
            raise EvidenceError("ZIP UTF-8 flag does not match the raw entry name")
        if flags & 0x1:
            raise EvidenceError("encrypted ZIP entries are not allowed")
        uses_data_descriptor = bool(flags & 0x8)
        if uses_data_descriptor and not allow_signed_data_descriptors:
            raise EvidenceError("ZIP data descriptors are not allowed")
        allowed_flags = 0x800 | (0x8 if allow_signed_data_descriptors else 0)
        if flags & ~allowed_flags:
            raise EvidenceError("ZIP entry uses unsupported general-purpose flags")
        canonical_name = _normalize_archive_name(name, allow_directory=allow_directories)
        if canonical_name in seen_names:
            raise EvidenceError("archive contains duplicate names")
        folded = canonical_name.casefold()
        if folded in seen_casefold:
            raise EvidenceError("archive contains Unicode or case-colliding names")
        seen_names.add(canonical_name)
        seen_casefold.add(folded)
        if compression not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise EvidenceError("archive uses an unsupported compression method")
        if compressed_size > maximum_entry_bytes or uncompressed_size > maximum_entry_bytes:
            raise EvidenceError("archive entry exceeds its per-entry size limit")
        if uncompressed_size and compressed_size == 0:
            raise EvidenceError("archive entry has an impossible compression ratio")
        if compressed_size and uncompressed_size > compressed_size * MAX_COMPRESSION_RATIO:
            raise EvidenceError("archive entry exceeds its compression-ratio limit")
        total_compressed += compressed_size
        total_uncompressed += uncompressed_size
        if total_compressed > maximum_total_compressed_bytes:
            raise EvidenceError("archive compressed total exceeds its size limit")
        if total_uncompressed > maximum_total_bytes:
            raise EvidenceError("archive uncompressed total exceeds its size limit")
        if local_offset >= central_offset:
            raise EvidenceError("archive local header points into central metadata")
        local = _read_exact_at(source, local_offset, LOCAL_HEADER.size, "local header")
        local_fields = LOCAL_HEADER.unpack(local)
        if local_fields[0] != b"PK\x03\x04":
            raise EvidenceError("archive local-header signature is invalid")
        local_name_length = local_fields[9]
        local_extra_length = local_fields[10]
        if local_name_length != name_length or local_extra_length:
            raise EvidenceError("local and central archive name bounds differ")
        local_name = _read_exact_at(
            source, local_offset + LOCAL_HEADER.size, local_name_length, "local name"
        )
        if (
            local_fields[1] != fields[2]
            or local_fields[2] != flags
            or local_fields[3] != compression
            or local_fields[4] != fields[5]
            or local_fields[5] != fields[6]
            or local_name != raw_name
        ):
            raise EvidenceError("local and central archive names or metadata differ")
        data_end = local_offset + LOCAL_HEADER.size + local_name_length + compressed_size
        if uses_data_descriptor:
            if any(local_fields[index] != 0 for index in (6, 7, 8)):
                raise EvidenceError(
                    "streamed ZIP local size fields must be delegated to its data descriptor"
                )
            descriptor = _read_exact_at(
                source,
                data_end,
                SIGNED_DATA_DESCRIPTOR.size,
                "signed ZIP data descriptor",
            )
            descriptor_fields = SIGNED_DATA_DESCRIPTOR.unpack(descriptor)
            if descriptor_fields != (
                b"PK\x07\x08",
                fields[7],
                compressed_size,
                uncompressed_size,
            ):
                raise EvidenceError(
                    "ZIP data descriptor does not match central archive metadata"
                )
            data_end += SIGNED_DATA_DESCRIPTOR.size
        elif (
            local_fields[6] != fields[7]
            or local_fields[7] != compressed_size
            or local_fields[8] != uncompressed_size
        ):
            raise EvidenceError("local and central archive sizes or CRC differ")
        if data_end > central_offset:
            raise EvidenceError("archive entry data overlaps central metadata")
        local_ranges.append((local_offset, data_end))
        result.append(
            RawZipEntry(
                canonical_name,
                raw_name,
                flags,
                compression,
                compressed_size,
                uncompressed_size,
                local_offset,
            )
        )
        cursor += record_size
    if cursor != len(central):
        raise EvidenceError("central directory contains unparsed trailing metadata")
    ordered_ranges = sorted(local_ranges)
    if any(
        current_start < previous_end
        for (_previous_start, previous_end), (current_start, _current_end) in zip(
            ordered_ranges, ordered_ranges[1:]
        )
    ):
        raise EvidenceError("archive local entries overlap")
    expected_start = 0
    for start, end in ordered_ranges:
        if start != expected_start:
            raise EvidenceError(
                "archive local records do not provide continuous indexed coverage"
            )
        expected_start = end
    if expected_start != central_offset:
        raise EvidenceError(
            "archive contains unindexed bytes before the central directory"
        )
    return result


def _read_zip_payload(
    archive: zipfile.ZipFile, info: zipfile.ZipInfo, maximum_size: int
) -> bytes:
    if info.file_size > maximum_size:
        raise EvidenceError(f"archive payload exceeds its size limit: {info.filename}")
    try:
        with archive.open(info, "r") as source:
            payload = source.read(maximum_size + 1)
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        raise EvidenceError(f"cannot decompress archive payload: {info.filename}") from error
    if len(payload) != info.file_size or len(payload) > maximum_size:
        raise EvidenceError(f"archive payload size is inconsistent: {info.filename}")
    return payload


def artifact_size_limit(identity: ArtifactIdentity) -> int:
    if identity.role == "checksum":
        return 256
    if identity.role == "signature":
        return MAX_SIGNATURE_BYTES
    if identity.base_suffix == ".pom":
        return MAX_POM_BYTES
    if identity.base_suffix == ".module":
        return MAX_MODULE_BYTES
    return MAX_ENTRY_BYTES


def inspect_open_bundle(
    source: BinaryIO, file_size: int, version: str
) -> tuple[dict, dict[str, bytes]]:
    version = require_canonical_version(version)
    expected_paths = expected_release_paths(version)
    raw_entries = raw_central_directory(
        source,
        file_size,
        maximum_entries=len(expected_paths),
        maximum_directory_bytes=MAX_CENTRAL_DIRECTORY_BYTES,
        maximum_entry_bytes=MAX_ENTRY_BYTES,
        maximum_total_compressed_bytes=MAX_TOTAL_COMPRESSED_BYTES,
        maximum_total_bytes=MAX_TOTAL_UNCOMPRESSED_BYTES,
        allow_directories=False,
    )
    raw_names = tuple(sorted(entry.name for entry in raw_entries))
    if raw_names != expected_paths:
        missing = sorted(set(expected_paths) - set(raw_names))
        extra = sorted(set(raw_names) - set(expected_paths))
        raise EvidenceError(
            f"release artifact set is not exact; missing={missing}, extra={extra}"
        )
    source.seek(0)
    try:
        with zipfile.ZipFile(source, "r") as archive:
            infos = archive.infolist()
            if len(infos) != len(raw_entries):
                raise EvidenceError("zipfile and raw central-directory counts differ")
            payloads: dict[str, bytes] = {}
            total = 0
            identities = {
                artifact.path: artifact for artifact in expected_release_artifacts(version)
            }
            for raw, info in zip(raw_entries, infos, strict=True):
                if info.orig_filename != raw.name or info.filename != raw.name:
                    raise EvidenceError("zipfile truncated or changed a raw entry name")
                file_type = stat.S_IFMT(info.external_attr >> 16)
                if info.is_dir() or file_type not in (0, stat.S_IFREG):
                    raise EvidenceError("release bundle contains a non-regular entry")
                if info.flag_bits & 0x1:
                    raise EvidenceError("release bundle contains an encrypted entry")
                identity = identities[raw.name]
                payload = _read_zip_payload(archive, info, artifact_size_limit(identity))
                total += len(payload)
                if total > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise EvidenceError("release bundle payload total exceeds its limit")
                payloads[raw.name] = payload
    except (OSError, RuntimeError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError("cannot inspect the staged release ZIP") from error
    validate_release_payloads(payloads, version)
    source.seek(0)
    digest = hashlib.sha256()
    remaining = file_size
    while remaining:
        chunk = source.read(min(CHUNK_BYTES, remaining))
        if not chunk:
            raise EvidenceError("bundle ended while computing its identity")
        digest.update(chunk)
        remaining -= len(chunk)
    if source.read(1):
        raise EvidenceError("bundle grew while computing its identity")
    files = [
        {
            "path": path,
            "sha256": hashlib.sha256(payloads[path]).hexdigest(),
            "size": len(payloads[path]),
        }
        for path in sorted(payloads)
    ]
    manifest = {
        "bundle": {"sha256": digest.hexdigest(), "size": file_size},
        "files": files,
        "modules": list(MODULES),
        "schema": MANIFEST_SCHEMA,
        "scope": MANIFEST_SCOPE,
        "version": version,
    }
    if len(manifest_bytes(manifest)) > MAX_MANIFEST_BYTES:
        raise EvidenceError("canonical manifest exceeds its output size limit")
    return manifest, payloads


def inspect_bundle(path: Path | str, version: str) -> tuple[dict, dict[str, bytes]]:
    with open_stable_regular_file(path, "staged bundle", MAX_BUNDLE_BYTES) as source:
        size = os.fstat(source.fileno()).st_size
        return inspect_open_bundle(source, size, version)


def manifest_bytes(manifest: dict) -> bytes:
    payload = (
        json.dumps(
            manifest,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    if len(payload) > MAX_MANIFEST_BYTES:
        raise EvidenceError("canonical manifest exceeds its output size limit")
    return payload


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"JSON contains duplicate key: {key!r}")
        result[key] = value
    return result


def parse_manifest(payload: bytes, version: str) -> dict:
    if len(payload) > MAX_MANIFEST_BYTES:
        raise EvidenceError("manifest exceeds its size limit")
    try:
        manifest = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError("manifest is not canonical UTF-8 JSON") from error
    if not isinstance(manifest, dict) or set(manifest) != {
        "bundle",
        "files",
        "modules",
        "schema",
        "scope",
        "version",
    }:
        raise EvidenceError("manifest has an invalid top-level schema")
    if (
        manifest.get("schema") != MANIFEST_SCHEMA
        or manifest.get("scope") != MANIFEST_SCOPE
        or manifest.get("version") != require_canonical_version(version)
        or manifest.get("modules") != list(MODULES)
    ):
        raise EvidenceError("manifest identity does not match the release contract")
    bundle = manifest.get("bundle")
    if not isinstance(bundle, dict) or set(bundle) != {"sha256", "size"}:
        raise EvidenceError("manifest bundle identity is malformed")
    bundle_digest = bundle.get("sha256")
    if not isinstance(bundle_digest, str) or re.fullmatch(
        r"[0-9a-f]{64}", bundle_digest
    ) is None:
        raise EvidenceError("manifest bundle digest is malformed")
    if (
        isinstance(bundle.get("size"), bool)
        or not isinstance(bundle.get("size"), int)
        or not 0 < bundle["size"] <= MAX_BUNDLE_BYTES
    ):
        raise EvidenceError("manifest bundle size is malformed")
    expected_paths = expected_release_paths(version)
    files = manifest.get("files")
    if not isinstance(files, list) or len(files) != len(expected_paths):
        raise EvidenceError("manifest file count does not match the release contract")
    observed = []
    for entry in files:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256", "size"}:
            raise EvidenceError("manifest file record is malformed")
        path = entry.get("path")
        if not isinstance(path, str):
            raise EvidenceError("manifest path is not text")
        observed.append(path)
        entry_digest = entry.get("sha256")
        if not isinstance(entry_digest, str) or re.fullmatch(
            r"[0-9a-f]{64}", entry_digest
        ) is None:
            raise EvidenceError("manifest file digest is malformed")
        size = entry.get("size")
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise EvidenceError("manifest file size is malformed")
    if tuple(observed) != expected_paths:
        raise EvidenceError("manifest paths do not equal the closed artifact set")
    if payload != manifest_bytes(manifest):
        raise EvidenceError("manifest is not in canonical deterministic form")
    return manifest


def verify_manifest(
    bundle_path: Path | str, manifest_path: Path | str, version: str
) -> dict:
    provided_bytes = read_stable_file(
        manifest_path, "staged bundle manifest", MAX_MANIFEST_BYTES
    )
    provided = parse_manifest(provided_bytes, version)
    expected, _payloads = inspect_bundle(bundle_path, version)
    if not hmac.compare_digest(provided_bytes, manifest_bytes(expected)):
        raise EvidenceError("manifest does not match the exact staged bundle bytes")
    return provided


def _crc24(payload: bytes) -> int:
    value = 0xB704CE
    for byte in payload:
        value ^= byte << 16
        for _ in range(8):
            value <<= 1
            if value & 0x1000000:
                value ^= 0x1864CFB
    return value & 0xFFFFFF


def _packet_body(packet: bytes) -> bytes:
    if len(packet) < 2 or not packet[0] & 0x80:
        raise EvidenceError("OpenPGP signature armor has no packet header")
    first = packet[0]
    cursor = 1
    if first & 0x40:
        if first & 0x3F != 2:
            raise EvidenceError("OpenPGP armor does not contain a signature packet")
        length = packet[cursor]
        cursor += 1
        if length < 192:
            body_length = length
        elif length < 224:
            if cursor >= len(packet):
                raise EvidenceError("truncated OpenPGP packet length")
            body_length = ((length - 192) << 8) + packet[cursor] + 192
            cursor += 1
        elif length == 255:
            if cursor + 4 > len(packet):
                raise EvidenceError("truncated OpenPGP packet length")
            body_length = int.from_bytes(packet[cursor : cursor + 4], "big")
            cursor += 4
        else:
            raise EvidenceError("partial OpenPGP packet lengths are not allowed")
    else:
        if (first >> 2) & 0x0F != 2:
            raise EvidenceError("OpenPGP armor does not contain a signature packet")
        length_type = first & 0x03
        length_bytes = (1, 2, 4, 0)[length_type]
        if not length_bytes or cursor + length_bytes > len(packet):
            raise EvidenceError("indeterminate or truncated OpenPGP packet length")
        body_length = int.from_bytes(packet[cursor : cursor + length_bytes], "big")
        cursor += length_bytes
    if body_length <= 0 or cursor + body_length != len(packet):
        raise EvidenceError("OpenPGP signature packet length is inconsistent")
    return packet[cursor:]


def _parse_openpgp_subpackets(payload: bytes, label: str) -> tuple[int, ...]:
    cursor = 0
    packet_types = []
    while cursor < len(payload):
        first = payload[cursor]
        cursor += 1
        if first < 192:
            length = first
        elif first < 224:
            if cursor >= len(payload):
                raise EvidenceError(f"OpenPGP {label} subpacket length is truncated")
            length = ((first - 192) << 8) + payload[cursor] + 192
            cursor += 1
        elif first == 255:
            if cursor + 4 > len(payload):
                raise EvidenceError(f"OpenPGP {label} subpacket length is truncated")
            length = int.from_bytes(payload[cursor : cursor + 4], "big")
            cursor += 4
        else:
            raise EvidenceError("OpenPGP partial subpacket lengths are not allowed")
        if length < 1 or cursor + length > len(payload):
            raise EvidenceError(f"OpenPGP {label} subpacket is empty or truncated")
        packet_type = payload[cursor] & 0x7F
        if packet_type == 0:
            raise EvidenceError(f"OpenPGP {label} subpacket type is invalid")
        packet_types.append(packet_type)
        cursor += length
    return tuple(packet_types)


def _parse_signature_mpis(payload: bytes, expected_count: int) -> None:
    cursor = 0
    for _index in range(expected_count):
        if cursor + 2 > len(payload):
            raise EvidenceError("OpenPGP signature MPI length is truncated")
        bit_length = int.from_bytes(payload[cursor : cursor + 2], "big")
        cursor += 2
        byte_length = (bit_length + 7) // 8
        if bit_length == 0 or cursor + byte_length > len(payload):
            raise EvidenceError("OpenPGP signature MPI is empty or truncated")
        first_byte = payload[cursor]
        if first_byte == 0 or first_byte.bit_length() != ((bit_length - 1) % 8) + 1:
            raise EvidenceError("OpenPGP signature MPI bit length is non-canonical")
        cursor += byte_length
    if cursor != len(payload):
        raise EvidenceError("OpenPGP signature packet has trailing material")


def validate_armored_signature(payload: bytes) -> None:
    if not payload or len(payload) > MAX_SIGNATURE_BYTES or b"\r" in payload:
        raise EvidenceError("armored signature size or line endings are invalid")
    try:
        text = payload.decode("ascii")
    except UnicodeDecodeError as error:
        raise EvidenceError("armored signature is not ASCII") from error
    lines = text.splitlines()
    if (
        len(lines) < 5
        or lines[0] != "-----BEGIN PGP SIGNATURE-----"
        or lines[-1] != "-----END PGP SIGNATURE-----"
    ):
        raise EvidenceError("armored signature boundary is invalid")
    cursor = 1
    while cursor < len(lines) and lines[cursor]:
        if not re.fullmatch(r"(?:Version|Comment): [ -~]{1,200}", lines[cursor]):
            raise EvidenceError("armored signature header is invalid")
        cursor += 1
    if cursor >= len(lines) or lines[cursor] != "":
        raise EvidenceError("armored signature is missing its header separator")
    cursor += 1
    body_lines = []
    while cursor < len(lines) - 2 and not lines[cursor].startswith("="):
        if not re.fullmatch(r"[A-Za-z0-9+/]{1,76}={0,2}", lines[cursor]):
            raise EvidenceError("armored signature body is not canonical base64")
        body_lines.append(lines[cursor])
        cursor += 1
    if cursor != len(lines) - 2 or not re.fullmatch(r"=[A-Za-z0-9+/]{4}", lines[cursor]):
        raise EvidenceError("armored signature CRC is missing or malformed")
    try:
        packet = base64.b64decode("".join(body_lines), validate=True)
        expected_crc = base64.b64decode(lines[cursor][1:], validate=True)
    except binascii.Error as error:
        raise EvidenceError("armored signature base64 cannot be decoded") from error
    if len(expected_crc) != 3 or not hmac.compare_digest(
        expected_crc, _crc24(packet).to_bytes(3, "big")
    ):
        raise EvidenceError("armored signature CRC does not match its packet")
    body = _packet_body(packet)
    if len(body) < 12 or body[0] != 4:
        raise EvidenceError("only structurally complete OpenPGP v4 signatures are accepted")
    signature_type = body[1]
    public_key_algorithm = body[2]
    hash_algorithm = body[3]
    if signature_type != 0 or hash_algorithm not in {8, 9, 10}:
        raise EvidenceError("OpenPGP signature type or digest algorithm is unsupported")
    mpi_counts = {1: 1, 2: 1, 3: 1, 17: 2, 19: 2, 22: 2}
    if public_key_algorithm not in mpi_counts:
        raise EvidenceError("OpenPGP signature public-key algorithm is unsupported")
    hashed_length = int.from_bytes(body[4:6], "big")
    unhashed_offset = 6 + hashed_length
    if unhashed_offset + 2 > len(body):
        raise EvidenceError("OpenPGP hashed subpacket area is truncated")
    hashed_types = _parse_openpgp_subpackets(body[6:unhashed_offset], "hashed")
    if 2 not in hashed_types:
        raise EvidenceError("OpenPGP signature has no hashed creation-time subpacket")
    unhashed_length = int.from_bytes(body[unhashed_offset : unhashed_offset + 2], "big")
    unhashed_end = unhashed_offset + 2 + unhashed_length
    if unhashed_end + 2 > len(body):
        raise EvidenceError("OpenPGP signature material is truncated")
    _parse_openpgp_subpackets(
        body[unhashed_offset + 2 : unhashed_end], "unhashed"
    )
    _parse_signature_mpis(body[unhashed_end + 2 :], mpi_counts[public_key_algorithm])


def _parse_checksum(payload: bytes, algorithm: str, filename: str) -> str:
    expected_length = dict(CHECKSUMS)[algorithm]
    if b"\r" in payload:
        raise EvidenceError("checksum sidecar uses non-canonical line endings")
    try:
        text = payload.decode("ascii")
    except UnicodeDecodeError as error:
        raise EvidenceError("checksum sidecar is not ASCII") from error
    if text.endswith("\n"):
        text = text[:-1]
    match = re.fullmatch(
        rf"([0-9a-f]{{{expected_length}}})(?:  ([0-9A-Za-z._+\-]+))?", text
    )
    if match is None or (match.group(2) is not None and match.group(2) != filename):
        raise EvidenceError("checksum sidecar digest or filename is invalid")
    return match.group(1)


def _safe_nested_zip(payload: bytes, role: str) -> dict[str, bytes]:
    if len(payload) > MAX_ENTRY_BYTES:
        raise EvidenceError(f"{role} JAR exceeds its stored size limit")
    source = io.BytesIO(payload)
    raw = raw_central_directory(
        source,
        len(payload),
        maximum_entries=MAX_NESTED_ENTRIES,
        maximum_directory_bytes=MAX_NESTED_CENTRAL_DIRECTORY_BYTES,
        maximum_entry_bytes=MAX_NESTED_ENTRY_BYTES,
        maximum_total_compressed_bytes=MAX_ENTRY_BYTES,
        maximum_total_bytes=MAX_NESTED_TOTAL_BYTES,
        allow_directories=True,
    )
    source.seek(0)
    contents: dict[str, bytes] = {}
    total = 0
    try:
        with zipfile.ZipFile(source, "r") as archive:
            infos = archive.infolist()
            if len(infos) != len(raw):
                raise EvidenceError("nested JAR raw and parsed entry counts differ")
            for raw_entry, info in zip(raw, infos, strict=True):
                if info.orig_filename != raw_entry.name or info.filename != raw_entry.name:
                    raise EvidenceError("nested JAR entry name was truncated or normalized")
                file_type = stat.S_IFMT(info.external_attr >> 16)
                allowed = (0, stat.S_IFDIR) if info.is_dir() else (0, stat.S_IFREG)
                if file_type not in allowed or info.flag_bits & 0x1:
                    raise EvidenceError("nested JAR contains a special or encrypted entry")
                if info.is_dir():
                    continue
                content = _read_zip_payload(archive, info, MAX_NESTED_ENTRY_BYTES)
                total += len(content)
                if total > MAX_NESTED_TOTAL_BYTES:
                    raise EvidenceError("nested JAR content exceeds its total size limit")
                contents[info.filename] = content
    except (OSError, RuntimeError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        if isinstance(error, EvidenceError):
            raise
        raise EvidenceError(f"{role} JAR is corrupt") from error
    if not contents:
        raise EvidenceError(f"{role} JAR has no regular files")
    return contents


class _ClassReader:
    def __init__(self, payload: bytes):
        self.payload = payload
        self.cursor = 0

    def read(self, size: int) -> bytes:
        end = self.cursor + size
        if size < 0 or end > len(self.payload):
            raise EvidenceError("module-info.class is truncated")
        value = self.payload[self.cursor : end]
        self.cursor = end
        return value

    def u1(self) -> int:
        return self.read(1)[0]

    def u2(self) -> int:
        return int.from_bytes(self.read(2), "big")

    def u4(self) -> int:
        return int.from_bytes(self.read(4), "big")


@dataclass(frozen=True)
class _ModuleDescriptor:
    name: str
    flags: int
    version: str | None
    exports: tuple[tuple[str, int, tuple[str, ...]], ...]
    requires: tuple[tuple[str, int, str | None], ...]
    opens: tuple[tuple[str, int, tuple[str, ...]], ...]
    uses: tuple[str, ...]
    provides: tuple[tuple[str, tuple[str, ...]], ...]


def _module_descriptor(payload: bytes) -> _ModuleDescriptor:
    reader = _ClassReader(payload)
    if reader.read(4) != b"\xca\xfe\xba\xbe":
        raise EvidenceError("module-info.class has no class-file magic")
    reader.read(4)
    pool_count = reader.u2()
    if pool_count < 2 or pool_count > 65_535:
        raise EvidenceError("module-info.class constant pool is malformed")
    pool: list[tuple[int, object] | None] = [None] * pool_count
    index = 1
    while index < pool_count:
        tag = reader.u1()
        if tag == 1:
            length = reader.u2()
            try:
                value: object = reader.read(length).decode("utf-8")
            except UnicodeDecodeError as error:
                raise EvidenceError("module-info.class contains invalid UTF-8") from error
        elif tag in (3, 4):
            value = reader.read(4)
        elif tag in (5, 6):
            value = reader.read(8)
            pool[index] = (tag, value)
            index += 1
            if index >= pool_count:
                raise EvidenceError("module-info.class has a truncated wide constant")
            pool[index] = None
            index += 1
            continue
        elif tag in (7, 8, 16, 19, 20):
            value = reader.u2()
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (reader.u2(), reader.u2())
        elif tag == 15:
            value = (reader.u1(), reader.u2())
        else:
            raise EvidenceError("module-info.class uses an unsupported constant-pool tag")
        pool[index] = (tag, value)
        index += 1

    def cp(index_value: int, tag: int) -> object:
        if not 0 < index_value < len(pool):
            raise EvidenceError("module-info.class constant-pool index is invalid")
        entry = pool[index_value]
        if entry is None or entry[0] != tag:
            raise EvidenceError("module-info.class constant-pool type is invalid")
        return entry[1]

    def utf8(index_value: int) -> str:
        value = cp(index_value, 1)
        if not isinstance(value, str):
            raise EvidenceError("module-info.class UTF-8 constant is malformed")
        return value

    access = reader.u2()
    this_class = reader.u2()
    reader.u2()
    class_name_index = cp(this_class, 7)
    if not isinstance(class_name_index, int) or utf8(class_name_index) != "module-info":
        raise EvidenceError("module-info.class has the wrong class identity")
    if access != 0x8000:
        raise EvidenceError("module-info.class access flags are not exact")
    for _section in range(3):
        count = reader.u2()
        if count:
            raise EvidenceError("module-info.class contains members or interfaces")
    attributes = reader.u2()
    module_attributes: list[bytes] = []
    for _index in range(attributes):
        name = utf8(reader.u2())
        body = reader.read(reader.u4())
        if name == "Module":
            module_attributes.append(body)
    if reader.cursor != len(payload) or len(module_attributes) != 1:
        raise EvidenceError("module-info.class has no unique Module attribute")
    module_reader = _ClassReader(module_attributes[0])
    module_index = cp(module_reader.u2(), 19)
    if not isinstance(module_index, int):
        raise EvidenceError("module-info.class module identity is malformed")
    module_name = utf8(module_index)
    module_flags = module_reader.u2()
    module_version_index = module_reader.u2()
    module_version = utf8(module_version_index) if module_version_index else None
    requires = []
    for _index in range(module_reader.u2()):
        required_index = cp(module_reader.u2(), 19)
        if not isinstance(required_index, int):
            raise EvidenceError("module-info.class dependency identity is malformed")
        flags = module_reader.u2()
        version_index = module_reader.u2()
        required_version = utf8(version_index) if version_index else None
        requires.append((utf8(required_index), flags, required_version))
    exports = []
    for _index in range(module_reader.u2()):
        package_index = cp(module_reader.u2(), 20)
        if not isinstance(package_index, int):
            raise EvidenceError("module-info.class export identity is malformed")
        export_flags = module_reader.u2()
        targets = []
        for _target in range(module_reader.u2()):
            target_index = cp(module_reader.u2(), 19)
            if not isinstance(target_index, int):
                raise EvidenceError("module-info.class export target is malformed")
            targets.append(utf8(target_index))
        exports.append(
            (
                utf8(package_index).replace("/", "."),
                export_flags,
                tuple(sorted(targets)),
            )
        )
    opens = []
    for _index in range(module_reader.u2()):
        package_index = cp(module_reader.u2(), 20)
        if not isinstance(package_index, int):
            raise EvidenceError("module-info.class open-package identity is malformed")
        open_flags = module_reader.u2()
        targets = []
        for _target in range(module_reader.u2()):
            target_index = cp(module_reader.u2(), 19)
            if not isinstance(target_index, int):
                raise EvidenceError("module-info.class open-package target is malformed")
            targets.append(utf8(target_index))
        opens.append(
            (
                utf8(package_index).replace("/", "."),
                open_flags,
                tuple(sorted(targets)),
            )
        )
    uses = []
    for _index in range(module_reader.u2()):
        class_index = cp(module_reader.u2(), 7)
        if not isinstance(class_index, int):
            raise EvidenceError("module-info.class service use is malformed")
        uses.append(utf8(class_index).replace("/", "."))
    provides = []
    for _index in range(module_reader.u2()):
        service_index = cp(module_reader.u2(), 7)
        if not isinstance(service_index, int):
            raise EvidenceError("module-info.class provided service is malformed")
        providers = []
        for _provider in range(module_reader.u2()):
            provider_index = cp(module_reader.u2(), 7)
            if not isinstance(provider_index, int):
                raise EvidenceError("module-info.class service provider is malformed")
            providers.append(utf8(provider_index).replace("/", "."))
        provides.append(
            (
                utf8(service_index).replace("/", "."),
                tuple(sorted(providers)),
            )
        )
    if module_reader.cursor != len(module_reader.payload):
        raise EvidenceError("module-info.class Module attribute has trailing bytes")
    return _ModuleDescriptor(
        module_name,
        module_flags,
        module_version,
        tuple(sorted(exports)),
        tuple(sorted(requires)),
        tuple(sorted(opens)),
        tuple(sorted(uses)),
        tuple(sorted(provides)),
    )


_MODULE_SOURCE_TOKEN = re.compile(
    r"\s+|//[^\r\n]*(?:\r?\n|$)|/\*.*?\*/|"
    r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*|[{},;]|.",
    re.DOTALL,
)


class _ModuleSourceReader:
    def __init__(self, source: str):
        self.tokens = [
            match.group(0)
            for match in _MODULE_SOURCE_TOKEN.finditer(source)
            if not match.group(0).isspace()
            and not match.group(0).startswith("//")
            and not match.group(0).startswith("/*")
        ]
        self.cursor = 0

    def accept(self, value: str) -> bool:
        if self.cursor < len(self.tokens) and self.tokens[self.cursor] == value:
            self.cursor += 1
            return True
        return False

    def take(self, label: str) -> str:
        if self.cursor >= len(self.tokens):
            raise EvidenceError(f"sources module-info.java is missing {label}")
        value = self.tokens[self.cursor]
        self.cursor += 1
        return value

    def expect(self, value: str) -> None:
        if not self.accept(value):
            raise EvidenceError(f"sources module-info.java expected {value!r}")

    def name(self, label: str) -> str:
        value = self.take(label)
        if re.fullmatch(
            r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*",
            value,
        ) is None:
            raise EvidenceError(f"sources module-info.java has an invalid {label}")
        return value


def _source_module_descriptor(source: str) -> _ModuleDescriptor:
    reader = _ModuleSourceReader(source)
    module_flags = 0x20 if reader.accept("open") else 0
    reader.expect("module")
    module_name = reader.name("module name")
    reader.expect("{")
    exports = []
    requires = []
    opens = []
    uses = []
    provides = []
    while not reader.accept("}"):
        directive = reader.take("module directive")
        if directive == "requires":
            flags = 0
            seen_modifiers = set()
            while reader.cursor < len(reader.tokens) and reader.tokens[reader.cursor] in {
                "static",
                "transitive",
            }:
                modifier = reader.take("requires modifier")
                if modifier in seen_modifiers:
                    raise EvidenceError("sources module-info.java repeats a requires modifier")
                seen_modifiers.add(modifier)
                flags |= 0x40 if modifier == "static" else 0x20
            requires.append((reader.name("required module"), flags, None))
            reader.expect(";")
        elif directive in {"exports", "opens"}:
            package = reader.name(f"{directive} package")
            targets = []
            if reader.accept("to"):
                targets.append(reader.name(f"{directive} target"))
                while reader.accept(","):
                    targets.append(reader.name(f"{directive} target"))
            record = (package, 0, tuple(sorted(targets)))
            (exports if directive == "exports" else opens).append(record)
            reader.expect(";")
        elif directive == "uses":
            uses.append(reader.name("used service"))
            reader.expect(";")
        elif directive == "provides":
            service = reader.name("provided service")
            reader.expect("with")
            providers = [reader.name("service provider")]
            while reader.accept(","):
                providers.append(reader.name("service provider"))
            provides.append((service, tuple(sorted(providers))))
            reader.expect(";")
        else:
            raise EvidenceError(
                f"sources module-info.java has an unsupported directive: {directive!r}"
            )
    if reader.cursor != len(reader.tokens):
        raise EvidenceError("sources module-info.java has trailing tokens")
    return _ModuleDescriptor(
        module_name,
        module_flags,
        None,
        tuple(sorted(exports)),
        tuple(sorted(requires)),
        tuple(sorted(opens)),
        tuple(sorted(uses)),
        tuple(sorted(provides)),
    )


def _manifest_attributes(payload: bytes) -> dict[str, str]:
    if not payload or b"\x00" in payload:
        raise EvidenceError("JAR manifest is empty or contains NUL")
    try:
        lines = payload.decode("utf-8").replace("\r\n", "\n").split("\n")
    except UnicodeDecodeError as error:
        raise EvidenceError("JAR manifest is not UTF-8") from error
    unfolded: list[str] = []
    for line in lines:
        if line.startswith(" "):
            if not unfolded:
                raise EvidenceError("JAR manifest continuation has no field")
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    attributes: dict[str, str] = {}
    for line in unfolded:
        if not line:
            break
        if ": " not in line:
            raise EvidenceError("JAR manifest field is malformed")
        name, value = line.split(": ", 1)
        key = name.casefold()
        if key in attributes or not value:
            raise EvidenceError("JAR manifest field is duplicate or empty")
        attributes[key] = value
    return attributes


def _validate_primary_jar(contents: dict[str, bytes], module: str) -> None:
    contract = module_semantic_contract(module)
    class_names = {
        name for name in contents if name.endswith(".class") and name != "module-info.class"
    }
    if contract.primary_anchor not in class_names or any(
        not name.startswith(contract.package_root) for name in class_names
    ):
        raise EvidenceError("primary JAR package identity does not match its module")
    if contract.explicit_module:
        if "module-info.class" not in contents:
            raise EvidenceError("primary JAR module identity is missing")
        descriptor = _module_descriptor(contents["module-info.class"])
        expected_exports = tuple(
            sorted((name, 0, ()) for name in contract.exports)
        )
        expected_requires = tuple(
            sorted(
                (name, flags, dict(contract.requires_versions)[name])
                for name, flags in contract.requires
            )
        )
        if (
            descriptor.name != contract.jpms_name
            or descriptor.flags != contract.module_flags
            or descriptor.version != contract.module_version
            or descriptor.exports != expected_exports
            or descriptor.requires != expected_requires
            or descriptor.opens != tuple(sorted(contract.opens))
            or descriptor.uses != tuple(sorted(contract.uses))
            or descriptor.provides != tuple(sorted(contract.provides))
        ):
            raise EvidenceError(
                "primary JAR module descriptor does not match the exact contract"
            )
    else:
        if "module-info.class" in contents:
            raise EvidenceError("Kotlin JAR must use its canonical automatic module identity")
        manifest = contents.get("META-INF/MANIFEST.MF")
        if manifest is None or _manifest_attributes(manifest).get(
            "automatic-module-name"
        ) != contract.jpms_name:
            raise EvidenceError("Kotlin JAR automatic module identity does not match")
        if "META-INF/procwright-kotlin.kotlin_module" not in contents:
            raise EvidenceError("Kotlin JAR has no Kotlin module declaration")


def _validate_sources_jar(contents: dict[str, bytes], module: str) -> None:
    contract = module_semantic_contract(module)
    extension = ".java" if contract.source_language == "java" else ".kt"
    source_names = {name for name in contents if name.endswith((".java", ".kt"))}
    if contract.source_anchor not in source_names or any(
        name != "module-info.java"
        and (not name.startswith(contract.package_root) or not name.endswith(extension))
        for name in source_names
    ):
        raise EvidenceError("sources JAR package identity does not match its module")
    if contract.explicit_module:
        descriptor = contents.get("module-info.java")
        if descriptor is None:
            raise EvidenceError("sources JAR module identity is missing")
        try:
            source = descriptor.decode("utf-8")
        except UnicodeDecodeError as error:
            raise EvidenceError("sources module-info.java is not UTF-8") from error
        observed = _source_module_descriptor(source)
        expected_exports = tuple(
            sorted((name, 0, ()) for name in contract.exports)
        )
        expected_requires = tuple(
            sorted(
                (name, flags, None)
                for name, flags in contract.requires
                if name != "java.base"
            )
        )
        if (
            observed.name != contract.jpms_name
            or observed.flags != (contract.module_flags & 0x20)
            or observed.exports != expected_exports
            or observed.requires != expected_requires
            or observed.opens != tuple(sorted(contract.opens))
            or observed.uses != tuple(sorted(contract.uses))
            or observed.provides != tuple(sorted(contract.provides))
        ):
            raise EvidenceError("sources JAR module descriptor does not match the exact contract")
    elif "module-info.java" in contents:
        raise EvidenceError("Kotlin sources JAR unexpectedly declares module-info.java")


def _validate_documentation_jar(contents: dict[str, bytes], module: str) -> None:
    contract = module_semantic_contract(module)
    names = set(contents)
    if "index.html" not in names or not any(
        name == contract.documentation_anchor
        or name.endswith("/" + contract.documentation_anchor)
        for name in names
    ):
        raise EvidenceError("documentation identity does not match its module")
    if contract.source_language == "java":
        expected_element_list = (
            ([f"module:{contract.jpms_name}"] if contract.documentation_module_indexed else [])
            + list(contract.exports)
        )
        element_list = contents.get("element-list")
        if element_list != ("\n".join(expected_element_list) + "\n").encode("ascii"):
            raise EvidenceError("documentation declarations do not match the Java module")
        module_summary = contract.jpms_name + "/module-summary.html"
        if contract.documentation_module_indexed != (module_summary in names):
            raise EvidenceError("documentation module index does not match the contract")
    else:
        package_list = contents.get("procwright-kotlin/package-list")
        try:
            package_lines = package_list.decode("utf-8").splitlines()
        except (AttributeError, UnicodeDecodeError) as error:
            raise EvidenceError("documentation identity has no valid Kotlin package index") from error
        locations = [
            line for line in package_lines if line.startswith("$dokka.location:")
        ]
        location_prefix = f"$dokka.location:{contract.jpms_name}"
        target_prefix = (
            "\x1fprocwright-kotlin/" + contract.jpms_name + "/"
        )
        if (
            package_lines[:2] != ["$dokka.format:html-v1", "$dokka.linkExtension:html"]
            or not locations
            or any(
                not line.startswith(location_prefix) or target_prefix not in line
                for line in locations
            )
            or package_lines[-1:] != [contract.jpms_name]
        ):
            raise EvidenceError("documentation identity has no Kotlin package index")


def validate_jar(payload: bytes, module: str, suffix: str) -> None:
    try:
        contents = _safe_nested_zip(payload, suffix)
    except EvidenceError as error:
        raise EvidenceError(f"{suffix} JAR is invalid: {error}") from error
    if suffix == ".jar":
        _validate_primary_jar(contents, module)
    elif suffix == "-sources.jar":
        _validate_sources_jar(contents, module)
    elif suffix == "-javadoc.jar":
        _validate_documentation_jar(contents, module)
    else:
        raise EvidenceError("unknown published JAR role")


def _direct_text(
    root: ElementTree.Element, name: str, *, required: bool = True
) -> str | None:
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    matches = root.findall(namespace + name)
    if len(matches) > 1 or required and len(matches) != 1:
        raise EvidenceError(f"POM field {name} is missing or ambiguous")
    if not matches:
        return None
    element = matches[0]
    if len(element):
        raise EvidenceError(f"POM metadata field {name} must be a scalar")
    value = element.text
    if value is None or not value.strip() or value != value.strip():
        raise EvidenceError(f"POM field {name} is empty or non-canonical")
    return value


def validate_pom(payload: bytes, module: str, version: str) -> None:
    if not payload or len(payload) > MAX_POM_BYTES or b"<!DOCTYPE" in payload or b"<!ENTITY" in payload:
        raise EvidenceError("POM size or XML declarations are unsafe")
    try:
        root = ElementTree.fromstring(payload)
    except ElementTree.ParseError as error:
        raise EvidenceError("POM is not well-formed XML") from error
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    if root.tag != namespace + "project":
        raise EvidenceError("POM root namespace is invalid")
    if any(
        not isinstance(element.tag, str)
        or not element.tag.startswith(namespace)
        or element.tag == namespace
        for element in root.iter()
    ):
        raise EvidenceError("every POM element must use the exact Maven namespace")
    if root.attrib != {
        "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation": POM_SCHEMA_LOCATION
    } or any(element.attrib for element in root.iter() if element is not root):
        raise EvidenceError("POM metadata must not contain XML attributes")
    if (
        _direct_text(root, "modelVersion") != "4.0.0"
        or _direct_text(root, "groupId") != GROUP_ID
        or _direct_text(root, "artifactId") != module
        or _direct_text(root, "version") != version
    ):
        raise EvidenceError("POM coordinates do not match the release identity")
    packaging = _direct_text(root, "packaging", required=False)
    if packaging not in (None, "jar"):
        raise EvidenceError("POM packaging must be absent or jar")
    contract = module_semantic_contract(module)
    if (
        _direct_text(root, "name") != contract.pom_name
        or _direct_text(root, "description") != contract.pom_description
        or _direct_text(root, "url") != PROJECT_URL
    ):
        raise EvidenceError("POM metadata does not match the module contract")

    def exact_container(
        container_name: str, child_name: str, expected: dict[str, str]
    ) -> None:
        containers = root.findall(namespace + container_name)
        if len(containers) != 1:
            raise EvidenceError(f"POM metadata {container_name} is missing or ambiguous")
        children = list(containers[0])
        if len(children) != 1 or children[0].tag != namespace + child_name:
            raise EvidenceError(f"POM metadata {container_name} schema is not exact")
        fields = list(children[0])
        observed_names = [field.tag.rsplit("}", 1)[-1] for field in fields]
        if observed_names != list(expected):
            raise EvidenceError(f"POM metadata {container_name} fields are not exact")
        observed = {}
        for field, field_name in zip(fields, observed_names, strict=True):
            if len(field):
                raise EvidenceError(
                    f"POM metadata {container_name} fields must be scalar"
                )
            value = field.text
            if value is None or value != value.strip() or not value:
                raise EvidenceError(f"POM metadata {container_name} has an invalid value")
            observed[field_name] = value
        if observed != expected:
            raise EvidenceError(f"POM metadata {container_name} does not match the contract")

    exact_container(
        "licenses",
        "license",
        {"name": LICENSE_NAME, "url": LICENSE_URL},
    )
    exact_container(
        "developers",
        "developer",
        {"id": DEVELOPER_ID, "name": DEVELOPER_NAME},
    )
    scm = root.findall(namespace + "scm")
    if len(scm) != 1:
        raise EvidenceError("POM metadata scm is missing or ambiguous")
    scm_fields = list(scm[0])
    expected_scm = {
        "connection": SCM_CONNECTION,
        "developerConnection": SCM_DEVELOPER_CONNECTION,
        "url": SCM_URL,
    }
    if [field.tag.rsplit("}", 1)[-1] for field in scm_fields] != list(expected_scm):
        raise EvidenceError("POM metadata scm fields are not exact")
    if any(len(field) for field in scm_fields):
        raise EvidenceError("POM metadata scm fields must be scalar")
    if {
        field.tag.rsplit("}", 1)[-1]: field.text for field in scm_fields
    } != expected_scm:
        raise EvidenceError("POM metadata scm does not match the contract")

    allowed_top_level = {
        "modelVersion",
        "groupId",
        "artifactId",
        "version",
        "packaging",
        "name",
        "description",
        "url",
        "licenses",
        "developers",
        "scm",
        "dependencies",
    }
    top_level_names = [child.tag.rsplit("}", 1)[-1] for child in root]
    forbidden = {
        "repositories",
        "pluginRepositories",
        "distributionManagement",
        "parent",
        "dependencyManagement",
        "profiles",
    }
    for element in root.iter():
        if element.tag.rsplit("}", 1)[-1] in forbidden:
            raise EvidenceError("POM contains repository or inherited dependency policy")
    if len(top_level_names) != len(set(top_level_names)) or not set(
        top_level_names
    ) <= allowed_top_level:
        raise EvidenceError("POM metadata contains duplicate or unexpected fields")
    dependency_containers = root.findall(f"{namespace}dependencies")
    if len(dependency_containers) > 1:
        raise EvidenceError("POM dependencies section is ambiguous")
    dependency_nodes = root.findall(
        f"{namespace}dependencies/{namespace}dependency"
    )
    if [element for element in root.iter() if element.tag == namespace + "dependency"] != dependency_nodes:
        raise EvidenceError("POM contains dependencies outside the direct dependency list")
    dependencies = []
    for dependency in dependency_nodes:
        children = [(child.tag.rsplit("}", 1)[-1], child) for child in dependency]
        if len(children) != 4 or {name for name, _child in children} != {
            "groupId",
            "artifactId",
            "version",
            "scope",
        }:
            raise EvidenceError("POM dependency schema is not exact")
        indexed = dict(children)
        if any(len(field) for field in indexed.values()):
            raise EvidenceError("POM dependency fields must be scalar")
        values = tuple(indexed[name].text or "" for name in indexed)
        if any(not value or value != value.strip() for value in values):
            raise EvidenceError("POM dependency contains an empty or non-canonical value")
        dependencies.append(
            (
                indexed["groupId"].text or "",
                indexed["artifactId"].text or "",
                indexed["version"].text or "",
                indexed["scope"].text or "",
            )
        )
    if tuple(sorted(dependencies)) != expected_pom_dependencies(module, version):
        raise EvidenceError("POM dependencies do not match the release contract")


def _hashes(payload: bytes) -> dict[str, object]:
    return {
        "md5": hashlib.md5(payload, usedforsecurity=False).hexdigest(),
        "sha1": hashlib.sha1(payload, usedforsecurity=False).hexdigest(),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "sha512": hashlib.sha512(payload).hexdigest(),
        "size": len(payload),
    }


def _expected_variants(module: str) -> tuple[str, ...]:
    common = ("apiElements", "runtimeElements", "sourcesElements")
    return common if module == "procwright-kotlin" else common + ("javadocElements",)


def _expected_variant_attributes(module: str, name: str) -> dict[str, object]:
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


def validate_module_metadata(
    payload: bytes, module: str, version: str, release_payloads: dict[str, bytes]
) -> None:
    if not payload or len(payload) > MAX_MODULE_BYTES:
        raise EvidenceError("Gradle module metadata exceeds its size limit")
    try:
        metadata = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError("Gradle module metadata is not valid UTF-8 JSON") from error
    if not isinstance(metadata, dict) or set(metadata) != {
        "formatVersion",
        "component",
        "createdBy",
        "variants",
    }:
        raise EvidenceError("Gradle module metadata schema is not exact")
    if metadata["formatVersion"] != "1.1":
        raise EvidenceError("Gradle module metadata format is unsupported")
    if metadata["component"] != {
        "group": GROUP_ID,
        "module": module,
        "version": version,
        "attributes": {"org.gradle.status": "release"},
    }:
        raise EvidenceError("Gradle module component identity is incorrect")
    created_by = metadata["createdBy"]
    producer_version = (
        created_by.get("gradle", {}).get("version")
        if isinstance(created_by, dict)
        and isinstance(created_by.get("gradle"), dict)
        else None
    )
    if (
        not isinstance(created_by, dict)
        or set(created_by) != {"gradle"}
        or not isinstance(created_by["gradle"], dict)
        or set(created_by["gradle"]) != {"version"}
        or not isinstance(producer_version, str)
        or re.fullmatch(
            r"[0-9]+(?:\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?",
            producer_version,
        )
        is None
    ):
        raise EvidenceError("Gradle module producer identity is malformed")
    variants = metadata["variants"]
    if not isinstance(variants, list):
        raise EvidenceError("Gradle module variants must be a list")
    names = tuple(variant.get("name") for variant in variants if isinstance(variant, dict))
    if names != _expected_variants(module):
        raise EvidenceError("Gradle module variants do not match published artifacts")
    for variant in variants:
        name = variant["name"]
        expected_dependencies = (
            expected_module_dependencies(module, version, name)
            if name in ("apiElements", "runtimeElements")
            else ()
        )
        has_dependencies = bool(expected_dependencies)
        expected_keys = {"name", "attributes", "files"} | (
            {"dependencies"} if has_dependencies else set()
        )
        if set(variant) != expected_keys or variant["attributes"] != _expected_variant_attributes(module, name):
            raise EvidenceError("Gradle module variant schema is not exact")
        if name == "sourcesElements":
            suffix = "-sources.jar"
        elif name == "javadocElements":
            suffix = "-javadoc.jar"
        else:
            suffix = ".jar"
        filename = f"{module}-{version}{suffix}"
        path = f"{GROUP_ID.replace('.', '/')}/{module}/{version}/{filename}"
        expected_file = {"name": filename, "url": filename, **_hashes(release_payloads[path])}
        if variant["files"] != [expected_file]:
            raise EvidenceError("Gradle module file identity does not match release bytes")
        if has_dependencies:
            observed_dependencies = []
            for dependency in variant["dependencies"]:
                if not isinstance(dependency, dict) or set(dependency) != {
                    "group",
                    "module",
                    "version",
                } or not isinstance(dependency["version"], dict) or set(dependency["version"]) != {"requires"}:
                    raise EvidenceError("Gradle module dependency schema is not exact")
                dependency_values = (
                    dependency["group"],
                    dependency["module"],
                    dependency["version"]["requires"],
                )
                if any(
                    not isinstance(value, str) or not value
                    for value in dependency_values
                ):
                    raise EvidenceError("Gradle module dependency schema is not exact")
                observed_dependencies.append(
                    dependency_values
                )
            if tuple(sorted(observed_dependencies)) != expected_dependencies:
                raise EvidenceError("Gradle module dependencies do not match the release contract")


def validate_base_payloads(payloads: dict[str, bytes], version: str) -> None:
    version = require_canonical_version(version)
    expected = expected_base_paths(version)
    if tuple(sorted(payloads)) != expected:
        raise EvidenceError("base payload map does not equal the closed artifact set")
    identities = {artifact.path: artifact for artifact in expected_release_artifacts(version)}
    for path in expected:
        identity = identities[path]
        payload = payloads[path]
        if not payload:
            raise EvidenceError(f"release payload is empty: {path}")
        if len(payload) > artifact_size_limit(identity):
            raise EvidenceError(f"release payload exceeds its type-specific limit: {path}")
        if identity.base_suffix.endswith(".jar"):
            validate_jar(payload, identity.module, identity.base_suffix)
        elif identity.base_suffix == ".pom":
            validate_pom(payload, identity.module, version)
    for module in MODULES:
        module_path = f"{GROUP_ID.replace('.', '/')}/{module}/{version}/{module}-{version}.module"
        validate_module_metadata(payloads[module_path], module, version, payloads)


def validate_release_payloads(payloads: dict[str, bytes], version: str) -> None:
    version = require_canonical_version(version)
    expected = expected_release_paths(version)
    if tuple(sorted(payloads)) != expected:
        raise EvidenceError("release payload map does not equal the closed artifact set")
    base_payloads = {
        path: payloads[path] for path in expected_base_paths(version)
    }
    validate_base_payloads(base_payloads, version)
    identities = {artifact.path: artifact for artifact in expected_release_artifacts(version)}
    for path in expected:
        identity = identities[path]
        payload = payloads[path]
        if not payload:
            raise EvidenceError(f"release payload is empty: {path}")
        if len(payload) > artifact_size_limit(identity):
            raise EvidenceError(f"release payload exceeds its type-specific limit: {path}")
        if identity.role == "signature":
            validate_armored_signature(payload)
        elif identity.role == "checksum":
            base = payloads[identity.base_path]
            observed = _parse_checksum(
                payload,
                identity.checksum_algorithm or "",
                identity.base_path.rsplit("/", 1)[-1],
            )
            expected_digest = hashlib.new(
                identity.checksum_algorithm or "", base
            ).hexdigest()
            if not hmac.compare_digest(observed, expected_digest):
                raise EvidenceError(f"checksum does not match base artifact: {path}")
