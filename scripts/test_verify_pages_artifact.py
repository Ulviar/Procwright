import copy
import hashlib
import io
import json
import os
import stat
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPTS_DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIRECTORY))

import verify_pages_artifact as pages


class PagesArtifactTest(unittest.TestCase):
    REPOSITORY = "Ulviar/Procwright"
    ARTIFACT_ID = 401
    RUN_ID = 301
    RUN_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    WORKFLOW_SHA = "89abcdef0123456789abcdef0123456789abcdef"

    def setUp(self) -> None:
        self.files = {
            "assets/app.js": b"console.log('exact');\n",
            "index.html": b"<!doctype html><title>Exact docs</title>\n",
        }
        self.content_digest = pages._canonical_digest(self.files)
        self.raw_artifact = self.pages_artifact(self.files)
        self.metadata = self.artifact_metadata(self.raw_artifact)
        self.commands = []
        self.downloads = []

    @staticmethod
    def tar_payload(
        files: dict[str, bytes], *, extra: tuple[str, bytes, int] | None = None
    ) -> bytes:
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as archive:
            root = tarfile.TarInfo(".")
            root.type = tarfile.DIRTYPE
            root.mode = 0o755
            archive.addfile(root)
            directories = sorted(
                {str(Path(path).parent).replace(os.sep, "/") for path in files}
                - {"."}
            )
            for directory in directories:
                info = tarfile.TarInfo("./" + directory)
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                archive.addfile(info)
            for path, payload in sorted(files.items()):
                info = tarfile.TarInfo("./" + path)
                info.size = len(payload)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(payload))
            if extra is not None:
                name, payload, kind = extra
                info = tarfile.TarInfo(name)
                info.type = kind
                if kind == tarfile.REGTYPE:
                    info.size = len(payload)
                    archive.addfile(info, io.BytesIO(payload))
                else:
                    info.linkname = "./index.html"
                    archive.addfile(info)
        return output.getvalue()

    @classmethod
    def pages_artifact(
        cls,
        files: dict[str, bytes],
        *,
        extra_tar: tuple[str, bytes, int] | None = None,
        extra_zip: bool = False,
    ) -> bytes:
        artifact_tar = cls.tar_payload(files, extra=extra_tar)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            info = zipfile.ZipInfo("artifact.tar", date_time=(2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, artifact_tar)
            if extra_zip:
                archive.writestr("substitute.txt", b"hostile")
        return output.getvalue()

    def artifact_metadata(self, payload: bytes) -> dict:
        return {
            "id": self.ARTIFACT_ID,
            "node_id": "artifact-node",
            "name": "github-pages",
            "size_in_bytes": len(payload),
            "url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/artifacts/{self.ARTIFACT_ID}",
            "archive_download_url": f"https://api.github.com/repos/{self.REPOSITORY}/actions/artifacts/{self.ARTIFACT_ID}/zip",
            "expired": False,
            "created_at": "2026-01-01T00:00:00Z",
            "expires_at": "2026-01-02T00:00:00Z",
            "updated_at": "2026-01-01T00:00:00Z",
            "digest": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "workflow_run": {
                "id": self.RUN_ID,
                "repository_id": 9001,
                "head_repository_id": 9001,
                "head_branch": "main",
                "head_sha": self.RUN_COMMIT,
            },
        }

    def runner(self, command: list[str]) -> bytes:
        self.commands.append(command)
        if command[0] == "git":
            return f"{self.WORKFLOW_SHA}\n".encode()
        endpoint = command[4]
        if endpoint.endswith(f"/actions/runs/{self.RUN_ID}/artifacts"):
            return json.dumps(
                {"total_count": 1, "artifacts": [self.metadata]}
            ).encode()
        if endpoint.endswith(f"/actions/artifacts/{self.ARTIFACT_ID}"):
            return json.dumps(self.metadata).encode()
        raise AssertionError(f"unexpected command: {command}")

    def downloader(self, repository: str, artifact_id: int, maximum_size: int) -> bytes:
        self.downloads.append((repository, artifact_id, maximum_size))
        return self.raw_artifact

    def environment(self) -> dict[str, str]:
        return {
            "GITHUB_REPOSITORY": self.REPOSITORY,
            "PROCWRIGHT_PAGES_ARTIFACT_ID": str(self.ARTIFACT_ID),
            "PROCWRIGHT_PAGES_CONTENT_SHA256": self.content_digest,
            "PROCWRIGHT_PAGES_RUN_COMMIT": self.RUN_COMMIT,
            "PROCWRIGHT_PAGES_RUN_ID": str(self.RUN_ID),
            "PROCWRIGHT_TRUSTED_ROOT": ".procwright-trusted",
            "PROCWRIGHT_WORKFLOW_SHA": self.WORKFLOW_SHA,
        }

    def verify(self) -> str:
        with mock.patch.dict(pages.os.environ, self.environment(), clear=True):
            return pages.verify_from_environment(self.runner, self.downloader)

    def test_seals_exact_visible_tree_and_rejects_unsafe_source_entries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "assets").mkdir()
            (root / "assets/app.js").write_bytes(self.files["assets/app.js"])
            (root / "index.html").write_bytes(self.files["index.html"])
            (root / ".ignored").write_bytes(b"not uploaded by upload-pages-artifact")
            self.assertEqual(pages.seal_directory(root), self.content_digest)
            (root / "link.html").symlink_to(root / "index.html")
            with self.assertRaisesRegex(pages.PagesArtifactError, "symlink"):
                pages.seal_directory(root)

    def test_verifies_current_run_id_api_digest_raw_bytes_and_exact_content(self) -> None:
        raw_digest = self.verify()
        self.assertEqual(raw_digest, hashlib.sha256(self.raw_artifact).hexdigest())
        self.assertEqual(
            [(repository, artifact_id) for repository, artifact_id, _ in self.downloads],
            [(self.REPOSITORY, self.ARTIFACT_ID)],
        )
        self.assertEqual(len([command for command in self.commands if command[0] == "gh"]), 2)
        self.assertTrue(
            any(
                f"name={pages.PAGES_ARTIFACT_NAME}" in command
                for command in self.commands
            )
        )

    def test_rejects_ambiguous_name_only_wrong_run_or_weakened_metadata(self) -> None:
        hostile = []
        for field, value in (
            ("id", 999),
            ("name", "github-pages-historical"),
            ("expired", True),
            ("digest", None),
        ):
            metadata = copy.deepcopy(self.metadata)
            metadata[field] = value
            hostile.append(metadata)
        for field, value in (("id", 999), ("head_sha", "f" * 40)):
            metadata = copy.deepcopy(self.metadata)
            metadata["workflow_run"][field] = value
            hostile.append(metadata)
        name_only = {"id": self.ARTIFACT_ID, "name": "github-pages"}
        hostile.append(name_only)
        for metadata in hostile:
            with self.subTest(metadata=metadata):
                self.metadata = metadata
                with self.assertRaises(pages.PagesArtifactError):
                    self.verify()

        self.metadata = self.artifact_metadata(self.raw_artifact)

        def ambiguous_runner(command: list[str]) -> bytes:
            if command[0] == "git":
                return f"{self.WORKFLOW_SHA}\n".encode()
            if command[4].endswith(f"/actions/runs/{self.RUN_ID}/artifacts"):
                return json.dumps(
                    {
                        "total_count": 2,
                        "artifacts": [self.metadata, copy.deepcopy(self.metadata)],
                    }
                ).encode()
            return json.dumps(self.metadata).encode()

        with mock.patch.dict(pages.os.environ, self.environment(), clear=True):
            with self.assertRaisesRegex(pages.PagesArtifactError, "exactly one"):
                pages.verify_from_environment(ambiguous_runner, self.downloader)

    def test_rejects_raw_digest_substitution_and_detail_list_disagreement(self) -> None:
        original = self.raw_artifact
        self.raw_artifact += b"substitution"
        with self.assertRaisesRegex(pages.PagesArtifactError, "API digest"):
            self.verify()
        self.raw_artifact = original

        detail = copy.deepcopy(self.metadata)
        detail["size_in_bytes"] += 1

        def disagreeing_runner(command: list[str]) -> bytes:
            if command[0] == "git":
                return f"{self.WORKFLOW_SHA}\n".encode()
            if command[4].endswith(f"/actions/runs/{self.RUN_ID}/artifacts"):
                return json.dumps(
                    {"total_count": 1, "artifacts": [self.metadata]}
                ).encode()
            return json.dumps(detail).encode()

        with mock.patch.dict(pages.os.environ, self.environment(), clear=True):
            with self.assertRaisesRegex(pages.PagesArtifactError, "identity differ"):
                pages.verify_from_environment(disagreeing_runner, self.downloader)

    def test_rejects_content_substitution_unsafe_tar_and_ambiguous_zip(self) -> None:
        hostile = (
            self.pages_artifact(
                {**self.files, "index.html": b"<title>substituted</title>"}
            ),
            self.pages_artifact(
                self.files,
                extra_tar=("./linked.html", b"", tarfile.SYMTYPE),
            ),
            self.pages_artifact(self.files, extra_zip=True),
        )
        for raw in hostile:
            with self.subTest(raw=raw[:20]):
                self.raw_artifact = raw
                self.metadata = self.artifact_metadata(raw)
                with self.assertRaises(pages.PagesArtifactError):
                    self.verify()

    def test_rejects_wrong_trusted_checkout_and_malformed_inputs(self) -> None:
        def wrong_checkout(command: list[str]) -> bytes:
            if command[0] == "git":
                return f"{'f' * 40}\n".encode()
            return self.runner(command)

        with mock.patch.dict(pages.os.environ, self.environment(), clear=True):
            with self.assertRaisesRegex(pages.PagesArtifactError, "workflow revision"):
                pages.verify_from_environment(wrong_checkout, self.downloader)
        environment = self.environment()
        environment["PROCWRIGHT_PAGES_ARTIFACT_ID"] = "01"
        with mock.patch.dict(pages.os.environ, environment, clear=True):
            with self.assertRaises(pages.PagesArtifactError):
                pages.verify_from_environment(self.runner, self.downloader)


if __name__ == "__main__":
    unittest.main()
