import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
RELEASE_SCRIPTS = REPOSITORY_ROOT / "scripts" / "release"


@unittest.skipIf(os.name == "nt", "release bootstrap scripts execute only on Unix release runners")
class ReleaseBootstrapScriptsTest(unittest.TestCase):
    def test_central_byte_download_rejects_malformed_identity_before_github(self) -> None:
        script = RELEASE_SCRIPTS / "download_verified_central_bytes.sh"
        commit = "a" * 40
        expected_name = f"procwright-1.2.3-{commit}-central-verified-bytes"
        cases = (
            ("artifact-id", "0", expected_name, "b" * 64),
            ("artifact-name", "67890", "other", "b" * 64),
            ("artifact-digest", "67890", expected_name, "B" * 64),
        )
        for label, artifact_id, artifact_name, digest in cases:
            with self.subTest(case=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                marker = root / "gh-called"
                fake_bin = root / "bin"
                fake_bin.mkdir()
                fake_gh = fake_bin / "gh"
                fake_gh.write_text(
                    "#!/usr/bin/env bash\n"
                    "printf called > \"${GH_MARKER}\"\n"
                    "exit 99\n",
                    encoding="utf-8",
                )
                fake_gh.chmod(0o700)
                environment = {
                    **os.environ,
                    "GH_MARKER": str(marker),
                    "GH_TOKEN": "public-test-token",
                    "GITHUB_REPOSITORY": "Ulviar/Procwright",
                    "GITHUB_RUN_ID": "12345",
                    "PATH": f"{fake_bin}{os.pathsep}{os.environ['PATH']}",
                    "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST": digest,
                    "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID": artifact_id,
                    "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_NAME": artifact_name,
                    "PROCWRIGHT_RELEASE_COMMIT": commit,
                    "PROCWRIGHT_RELEASE_VERSION": "1.2.3",
                    "RUNNER_TEMP": str(root),
                }

                result = subprocess.run(
                    ["bash", str(script)],
                    cwd=root,
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())

    def test_central_wrappers_pass_credentials_only_through_a_private_temporary_file(
        self,
    ) -> None:
        for script_name in ("stage_central_bundle.sh", "wait_for_central_publication.sh"):
            with self.subTest(script=script_name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                release_directory = root / "control" / "scripts" / "release"
                release_directory.mkdir(parents=True)
                shutil.copy2(RELEASE_SCRIPTS / script_name, release_directory)
                shutil.copy2(RELEASE_SCRIPTS / "trusted_context.sh", release_directory)
                fake_bin = root / "bin"
                fake_bin.mkdir()
                observed = root / "observed"
                fake_python = fake_bin / "python3"
                fake_python.write_text(
                    "#!/usr/bin/env bash\n"
                    "set -euo pipefail\n"
                    "[[ -z \"${CENTRAL_USERNAME+x}\" && -z \"${CENTRAL_PASSWORD+x}\" ]]\n"
                    "for argument in \"$@\"; do\n"
                    "  [[ \"${argument}\" != *argv-user* && \"${argument}\" != *argv-password* ]]\n"
                    "done\n"
                    "printf '%s\\0' \"$@\" > \"${OBSERVED_PATH}\"\n"
                    "credential_file=''\n"
                    "while (($#)); do\n"
                    "  if [[ \"$1\" == '--credentials-file' ]]; then\n"
                    "    credential_file=\"$2\"\n"
                    "    break\n"
                    "  fi\n"
                    "  shift\n"
                    "done\n"
                    "[[ -f \"${credential_file}\" && ! -L \"${credential_file}\" ]]\n"
                    "mode=\"$(stat -f '%Lp' \"${credential_file}\" 2>/dev/null "
                    "|| stat -c '%a' \"${credential_file}\")\"\n"
                    "[[ \"${mode}\" == '600' ]]\n"
                    "exec 3<\"${credential_file}\"\n"
                    "IFS= read -r -d '' username <&3\n"
                    "IFS= read -r -d '' password <&3\n"
                    "[[ \"${username}\" == 'argv-user' && \"${password}\" == 'argv-password' ]]\n"
                    "if IFS= read -r -n 1 extra <&3; then exit 72; fi\n"
                    "printf 'verified\\n' > /dev/null\n",
                    encoding="utf-8",
                )
                fake_python.chmod(fake_python.stat().st_mode | stat.S_IXUSR)
                environment = os.environ.copy()
                environment.update(
                    {
                        "CENTRAL_DEPLOYMENT_ID": "123e4567-e89b-12d3-a456-426614174000",
                        "CENTRAL_PASSWORD": "argv-password",
                        "CENTRAL_USERNAME": "argv-user",
                        "OBSERVED_PATH": str(observed),
                        "PATH": f"{fake_bin}{os.pathsep}{environment['PATH']}",
                        "PROCWRIGHT_RELEASE_COMMIT": "a" * 40,
                        "PROCWRIGHT_RELEASE_VERSION": "1.2.3",
                        "PROCWRIGHT_STAGED_BUNDLE_SHA256": "b" * 64,
                        "PROCWRIGHT_STAGING_ARTIFACT_DIGEST": "c" * 64,
                        "PROCWRIGHT_STAGING_ARTIFACT_ID": "67890",
                        "PROCWRIGHT_STAGING_ARTIFACT_NAME": (
                            f"procwright-1.2.3-{'a' * 40}-central-bundle"
                        ),
                        "PROCWRIGHT_STAGING_RUN_ID": "12345",
                        "RUNNER_TEMP": str(root),
                    }
                )

                credential_path = root / (
                    "procwright-central-stage-credentials"
                    if script_name == "stage_central_bundle.sh"
                    else "procwright-central-wait-credentials"
                )
                credential_path.write_bytes(b"pre-existing")
                credential_path.chmod(0o600)
                rejected = subprocess.run(
                    ["bash", str(release_directory / script_name)],
                    cwd=root,
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                self.assertNotEqual(rejected.returncode, 0)
                self.assertEqual(credential_path.read_bytes(), b"pre-existing")
                self.assertFalse(observed.exists())
                credential_path.unlink()

                result = subprocess.run(
                    ["bash", str(release_directory / script_name)],
                    cwd=root,
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

                self.assertEqual(result.returncode, 0, result.stderr)
                actual_arguments = [
                    value.decode("utf-8")
                    for value in observed.read_bytes().split(b"\0")
                    if value
                ]
                common = [
                    str(
                        root.resolve()
                        / "control/scripts/verify_maven_central_staged_bundle.py"
                    )
                ]
                if script_name == "stage_central_bundle.sh":
                    expected_arguments = common + [
                        "stage-central",
                        "--bundle",
                        "build/maven-central/procwright-1.2.3-maven-central-bundle.zip",
                        "--manifest",
                        "build/maven-central/procwright-1.2.3-staged-bundle-payload-manifest.json",
                        "--checksum",
                        "build/maven-central/procwright-1.2.3-maven-central-bundle.zip.sha256",
                        "--deployment",
                        "build/maven-central/procwright-1.2.3-central-deployment.json",
                        "--credentials-file",
                        str(credential_path),
                        "--version",
                        "1.2.3",
                        "--commit",
                        "a" * 40,
                        "--deadline-seconds",
                        "1800",
                    ]
                else:
                    expected_arguments = common + [
                        "wait-and-verify-central",
                        "--bundle",
                        "build/maven-central/procwright-1.2.3-maven-central-bundle.zip",
                        "--manifest",
                        "build/maven-central/procwright-1.2.3-staged-bundle-payload-manifest.json",
                        "--checksum",
                        "build/maven-central/procwright-1.2.3-maven-central-bundle.zip.sha256",
                        "--deployment",
                        "build/maven-central/procwright-1.2.3-central-deployment.json",
                        "--credentials-file",
                        str(credential_path),
                        "--version",
                        "1.2.3",
                        "--commit",
                        "a" * 40,
                        "--deployment-id",
                        "123e4567-e89b-12d3-a456-426614174000",
                        "--expected-bundle-sha256",
                        "b" * 64,
                        "--destination",
                        str(root / "procwright-central-downloads"),
                        "--attempts",
                        "12",
                        "--retry-delay-seconds",
                        "5",
                        "--timeout-seconds",
                        "30",
                        "--deadline-seconds",
                        "1800",
                    ]
                self.assertEqual(actual_arguments, expected_arguments)
                self.assertEqual(list(root.glob("procwright-central-*-credentials")), [])

    def test_provenance_accepts_only_current_trusted_head_after_successful_main_ci(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            control, _target_commit, workflow_commit, fake_bin = self._control_repository(
                Path(directory)
            )

            result = self._run_provenance(
                control, workflow_commit, workflow_commit, fake_bin
            )

            self.assertEqual(result.returncode, 0, result.stderr)

    def test_provenance_rejects_historical_ancestor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            control, target_commit, workflow_commit, fake_bin = self._control_repository(
                Path(directory)
            )

            result = self._run_provenance(
                control, target_commit, workflow_commit, fake_bin
            )

            self.assertNotEqual(result.returncode, 0)

    def test_provenance_rejects_untrusted_event_ref_sha_and_missing_ci_proof(self) -> None:
        mutations = (
            {"GITHUB_EVENT_NAME": "push"},
            {"GITHUB_REF": "refs/heads/feature"},
            {"GITHUB_SHA": "f" * 40},
            {"FAKE_GH_CONCLUSION": "failure"},
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    control, target_commit, workflow_commit, fake_bin = (
                        self._control_repository(Path(directory))
                    )
                    result = self._run_provenance(
                        control,
                        target_commit,
                        workflow_commit,
                        fake_bin,
                        mutation,
                    )
                    self.assertNotEqual(result.returncode, 0)

    def test_provenance_rejects_non_ancestor_and_dirty_trusted_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control, _target_commit, workflow_commit, fake_bin = (
                self._control_repository(root)
            )
            non_ancestor = self._separate_commit(root / "separate")
            result = self._run_provenance(
                control, non_ancestor, workflow_commit, fake_bin
            )
            self.assertNotEqual(result.returncode, 0)

            (control / "dirty.txt").write_text("dirty", encoding="utf-8")
            result = self._run_provenance(
                control,
                workflow_commit,
                workflow_commit,
                fake_bin,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("not clean", result.stderr)

    def test_provenance_rejects_git_status_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            control, _target_commit, workflow_commit, fake_bin = self._control_repository(
                Path(directory)
            )
            (control / ".git" / "index").write_bytes(b"corrupt index")

            result = self._run_provenance(
                control, workflow_commit, workflow_commit, fake_bin
            )

            self.assertNotEqual(result.returncode, 0)

    def test_target_verifier_accepts_only_exact_clean_real_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = self._copy_control(root / "control")
            target = root / "target"
            commit = self._initialize_repository(target)

            result = self._run_target_verifier(control, target, commit)
            self.assertEqual(result.returncode, 0, result.stderr)

            wrong_commit = "f" * 40
            result = self._run_target_verifier(control, target, wrong_commit)
            self.assertNotEqual(result.returncode, 0)

            (target / "dirty.txt").write_text("dirty", encoding="utf-8")
            result = self._run_target_verifier(control, target, commit)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("not clean", result.stderr)

    def test_target_verifier_rejects_symlink_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = self._copy_control(root / "control")
            target = root / "target"
            commit = self._initialize_repository(target)
            target_link = root / "target-link"
            target_link.symlink_to(target, target_is_directory=True)

            result = self._run_target_verifier(control, target_link, commit)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("real directory", result.stderr)

    def test_target_verifier_rejects_git_status_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = self._copy_control(root / "control")
            target = root / "target"
            commit = self._initialize_repository(target)
            (target / ".git" / "index").write_bytes(b"corrupt index")

            result = self._run_target_verifier(control, target, commit)

            self.assertNotEqual(result.returncode, 0)

    def _control_repository(self, root: Path) -> tuple[Path, str, str, Path]:
        control = self._copy_control(root / "control")
        self._git(control, "init", "-b", "main")
        self._configure_git(control)
        (control / "marker.txt").write_text("target", encoding="utf-8")
        self._git(control, "add", ".")
        self._git(control, "commit", "-m", "target")
        target_commit = self._git(control, "rev-parse", "HEAD").strip()
        (control / "marker.txt").write_text("workflow", encoding="utf-8")
        self._git(control, "add", "marker.txt")
        self._git(control, "commit", "-m", "workflow")
        workflow_commit = self._git(control, "rev-parse", "HEAD").strip()

        fake_bin = root / "bin"
        fake_bin.mkdir()
        fake_gh = fake_bin / "gh"
        fake_gh.write_text(
            "#!/usr/bin/env bash\n"
            "case \"$*\" in\n"
            "  *'/actions/workflows/ci.yml')\n"
            "    printf '%s\\n' '{\"id\":12345,\"path\":\".github/workflows/ci.yml\",\"state\":\"active\"}'\n"
            "    ;;\n"
            "  *'/actions/workflows/12345/runs'*)\n"
            "    printf '{\"total_count\":1,\"workflow_runs\":[{\"id\":777,\"run_attempt\":1,\"workflow_id\":12345,\"path\":\".github/workflows/ci.yml\",\"event\":\"push\",\"status\":\"completed\",\"conclusion\":\"%s\",\"head_branch\":\"main\",\"head_sha\":\"%s\",\"head_commit\":{\"id\":\"%s\"},\"repository\":{\"full_name\":\"%s\"},\"head_repository\":{\"full_name\":\"%s\"}}]}\\n' \"${FAKE_GH_CONCLUSION:-success}\" \"${PROCWRIGHT_RELEASE_COMMIT}\" \"${PROCWRIGHT_RELEASE_COMMIT}\" \"${GITHUB_REPOSITORY}\" \"${GITHUB_REPOSITORY}\"\n"
            "    ;;\n"
            "  *) exit 64 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        fake_gh.chmod(fake_gh.stat().st_mode | stat.S_IXUSR)
        return control, target_commit, workflow_commit, fake_bin

    def _copy_control(self, control: Path) -> Path:
        release_directory = control / "scripts" / "release"
        release_directory.mkdir(parents=True)
        shutil.copy2(REPOSITORY_ROOT / "scripts" / "release_contract.py", control / "scripts")
        shutil.copy2(
            REPOSITORY_ROOT / "scripts" / "verify_release_ci_run.py",
            control / "scripts",
        )
        for name in (
            "trusted_context.sh",
            "verify_release_commit_provenance.sh",
            "verify_target_checkout.sh",
        ):
            shutil.copy2(RELEASE_SCRIPTS / name, release_directory)
        return control

    def _initialize_repository(self, repository: Path) -> str:
        repository.mkdir()
        self._git(repository, "init", "-b", "main")
        self._configure_git(repository)
        (repository / "file.txt").write_text("content", encoding="utf-8")
        self._git(repository, "add", "file.txt")
        self._git(repository, "commit", "-m", "target")
        return self._git(repository, "rev-parse", "HEAD").strip()

    def _separate_commit(self, repository: Path) -> str:
        return self._initialize_repository(repository)

    def _configure_git(self, repository: Path) -> None:
        self._git(repository, "config", "user.name", "Procwright Test")
        self._git(repository, "config", "user.email", "test@example.invalid")

    def _run_provenance(
        self,
        control: Path,
        target_commit: str,
        workflow_commit: str,
        fake_bin: Path,
        mutation: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "FAKE_GH_CONCLUSION": "success",
                "GH_TOKEN": "test-token",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/main",
                "GITHUB_REPOSITORY": "Ulviar/Procwright",
                "GITHUB_SHA": workflow_commit,
                "GITHUB_WORKFLOW_SHA": workflow_commit,
                "PATH": f"{fake_bin}{os.pathsep}{environment['PATH']}",
                "PROCWRIGHT_RELEASE_COMMIT": target_commit,
                "PROCWRIGHT_TRUSTED_ROOT": str(control),
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )
        environment.update(mutation or {})
        return subprocess.run(
            ["bash", str(control / "scripts" / "release" / "verify_release_commit_provenance.sh")],
            cwd=control.parent,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def _run_target_verifier(
        self, control: Path, target: Path, commit: str
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PROCWRIGHT_RELEASE_COMMIT": commit,
                "PROCWRIGHT_TARGET_ROOT": str(target),
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )
        return subprocess.run(
            ["bash", str(control / "scripts" / "release" / "verify_target_checkout.sh")],
            cwd=control.parent,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def _git(self, repository: Path, *arguments: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode != 0:
            self.fail(f"git {' '.join(arguments)} failed: {result.stderr}")
        return result.stdout


if __name__ == "__main__":
    unittest.main()
