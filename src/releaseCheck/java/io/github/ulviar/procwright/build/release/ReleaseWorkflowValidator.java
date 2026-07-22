/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class ReleaseWorkflowValidator {
    private static final Pattern PINNED_ACTION = Pattern.compile("[^\\s@]+@[0-9a-f]{40}");
    private static final Pattern SECRET_EXPRESSION =
            Pattern.compile("\\$\\{\\{[^}]*\\bsecrets\\b", Pattern.CASE_INSENSITIVE);
    private static final String MANUAL_DISPATCH = "github.event_name == 'workflow_dispatch'";
    private static final String RELEASE_JOB_CONDITION =
            MANUAL_DISPATCH + " && github.ref == 'refs/heads/main' && github.sha == github.workflow_sha";
    private static final String DOCS_BUILD_CONDITION =
            "!cancelled() && (github.event_name == 'release' || (github.event_name == 'workflow_dispatch' && "
                    + "github.ref == 'refs/heads/main' && github.sha == github.workflow_sha && "
                    + "needs.recovery.result == 'success'))";
    private static final String NON_DISPATCH_CONDITION = "github.event_name != 'workflow_dispatch'";
    private static final String COMMIT_VALIDATION = "[[ \"$PROCWRIGHT_RELEASE_COMMIT\" =~ ^[0-9a-f]{40}$ ]]";
    private static final String TRUSTED_ROOT = ".procwright-trusted";
    private static final String TARGET_ROOT = ".procwright-target";
    private static final String CHECKOUT = "actions/checkout";
    private static final String SETUP_JAVA = "actions/setup-java";
    private static final String SETUP_PYTHON = "actions/setup-python";
    private static final String SETUP_UV = "astral-sh/setup-uv";
    private static final String SETUP_GRADLE = "gradle/actions/setup-gradle";
    private static final String UPLOAD_ARTIFACT = "actions/upload-artifact";
    private static final String DOWNLOAD_ARTIFACT = "actions/download-artifact";
    private static final Map<String, Object> RELEASE_JOB_PERMISSIONS = Map.of("actions", "read", "contents", "read");
    private static final Map<String, Object> READ_CONTENTS_PERMISSION = Map.of("contents", "read");
    private static final Map<String, Object> BASH_DEFAULTS = Map.of("run", Map.of("shell", "bash"));
    private static final Map<String, Object> TRUSTED_CHECKOUT = Map.of(
            "ref",
            "${{ github.workflow_sha }}",
            "fetch-depth",
            0,
            "persist-credentials",
            false,
            "path",
            TRUSTED_ROOT,
            "sparse-checkout",
            "scripts",
            "sparse-checkout-cone-mode",
            true);
    private static final String HANDOFF_ARTIFACT_PATHS = """
            .procwright-target/build/release-handoff/procwright-${{ inputs.release-version }}-unsigned.zip
            .procwright-target/build/release-handoff/procwright-${{ inputs.release-version }}-unsigned.zip.sha256
            .procwright-target/build/release-handoff/procwright-${{ inputs.release-version }}-unsigned-manifest.json
            """;
    private static final String STAGE_ARTIFACT_PATHS = """
            build/maven-central/procwright-${{ inputs.release-version }}-maven-central-bundle.zip
            build/maven-central/procwright-${{ inputs.release-version }}-maven-central-bundle.zip.sha256
            build/maven-central/procwright-${{ inputs.release-version }}-staged-bundle-payload-manifest.json
            build/maven-central/procwright-${{ inputs.release-version }}-signing-evidence.json
            build/maven-central/procwright-${{ inputs.release-version }}-signing-public-key.asc
            build/maven-central/procwright-${{ inputs.release-version }}-verified-handoff.json
            build/maven-central/procwright-${{ inputs.release-version }}-provenance.txt
            build/maven-central/procwright-${{ inputs.release-version }}-central-deployment.json
            """;
    private static final String FAILED_TEST_REPORT_PATHS = """
            **/build/test-results/**
            **/build/reports/tests/**
            """;

    void validate(Path ciPath, Path stagePath, Path docsPath) {
        WorkflowModel ci = new WorkflowModel(YamlWorkflow.load(ciPath));
        WorkflowModel stage = new WorkflowModel(YamlWorkflow.load(stagePath));
        WorkflowModel docs = new WorkflowModel(YamlWorkflow.load(docsPath));

        validateGlobalSafety(ci);
        validateGlobalSafety(stage);
        validateGlobalSafety(docs);
        validateCi(ci);
        validateStage(stage);
        validateDocs(docs);
    }

    private static void validateGlobalSafety(WorkflowModel workflow) {
        validatePinnedActions(workflow.yaml(), workflow.root(), "$");
        rejectKey(workflow.yaml(), workflow.root(), "$", "continue-on-error");
    }

    private static void validatePinnedActions(YamlWorkflow yaml, Object value, String path) {
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String childPath = path + "." + entry.getKey();
                if (entry.getKey().equals("uses")) {
                    String action = yaml.string(entry.getValue(), childPath);
                    if (!PINNED_ACTION.matcher(action).matches()) {
                        throw yaml.failure(childPath
                                + " action uses must be pinned to a lowercase full 40-character SHA: "
                                + action);
                    }
                }
                validatePinnedActions(yaml, entry.getValue(), childPath);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                validatePinnedActions(yaml, list.get(index), path + "[" + index + "]");
            }
        }
    }

    private static void rejectKey(YamlWorkflow yaml, Object value, String path, String forbiddenKey) {
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (map.containsKey(forbiddenKey)) {
                throw yaml.failure(path + " must not define " + forbiddenKey);
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                rejectKey(yaml, entry.getValue(), path + "." + entry.getKey(), forbiddenKey);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                rejectKey(yaml, list.get(index), path + "[" + index + "]", forbiddenKey);
            }
        }
    }

    private static void validateStage(WorkflowModel workflow) {
        YamlWorkflow yaml = workflow.yaml();
        requireRoot(workflow, "Stage Maven Central", Set.of("name", "on", "permissions", "concurrency", "jobs"));
        requireExactKeys(yaml, workflow.triggers(), Set.of("workflow_dispatch"), "$.on");
        validateDispatch(
                workflow,
                Map.of(
                        "release-version", "SemVer version to stage, without a leading v",
                        "release-commit", "Lowercase full commit SHA from main that passed the CI push workflow"));
        requireExactMap(
                yaml,
                yaml.requiredMap(workflow.root(), "concurrency", "$"),
                Map.of("group", "stage-maven-central", "cancel-in-progress", false),
                "$.concurrency");
        requireOrderedKeys(yaml, workflow.jobs(), List.of("build", "publish"), "$.jobs");
        validateStageBuild(workflow.job("build"));
        validateStagePublish(workflow.job("publish"));
    }

    private static void validateStageBuild(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "if", "runs-on", "timeout-minutes", "permissions", "defaults", "outputs", "steps"),
                job.path());
        requireJobScalar(job, "name", "Build unsigned release handoff");
        requireJobScalar(job, "if", RELEASE_JOB_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 60);
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "outputs", job.path()),
                Map.of(
                        "handoff-artifact-id", "${{ steps.handoff-upload.outputs.artifact-id }}",
                        "handoff-artifact-digest", "${{ steps.handoff-upload.outputs.artifact-digest }}"),
                job.path() + ".outputs");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Validate canonical release version from trusted control",
                        "Verify target provenance from trusted control",
                        "Checkout verified release target",
                        "Verify target checkout from trusted control",
                        "Set up JDK 17",
                        "Set up uv",
                        "Set up Gradle",
                        "Verify release candidate",
                        "Build unsigned Maven repository",
                        "Verify exact unsigned Maven repository",
                        "Prepare immutable unsigned handoff",
                        "Upload immutable unsigned handoff"));

        requireRunStep(
                steps.get(0),
                COMMIT_VALIDATION,
                Map.of("PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                null,
                null,
                null);
        requireTrustedCheckout(steps.get(1));
        requireSetupPython(steps.get(2));
        requireRunStep(
                steps.get(3),
                "python3 .procwright-trusted/scripts/release_contract.py version \"$PROCWRIGHT_RELEASE_VERSION\"",
                Map.of("PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(4),
                "bash .procwright-trusted/scripts/release/verify_release_commit_provenance.sh",
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_TRUSTED_ROOT", TRUSTED_ROOT),
                null,
                null,
                null);
        requireTargetCheckout(steps.get(5), "${{ inputs.release-commit }}");
        requireTargetVerification(steps.get(6), "${{ inputs.release-commit }}");
        requireSetupJava(steps.get(7));
        requireSetupUv(steps.get(8));
        requireActionStep(steps.get(9), SETUP_GRADLE, null, null);
        requireTargetRun(
                steps.get(10),
                "./gradlew releaseCandidateCheck --project-prop=procwright.javaRelease=17 "
                        + "--project-prop=procwright.version=\"$PROCWRIGHT_RELEASE_VERSION\" --no-daemon",
                Map.of("PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"));
        requireTargetRun(
                steps.get(11),
                "./gradlew cleanMavenCentralBundleRepository "
                        + "publishMavenJavaPublicationToMavenCentralBundleRepository "
                        + ":procwright-integrations:publishMavenJavaPublicationToMavenCentralBundleRepository "
                        + ":procwright-kotlin:publishMavenJavaPublicationToMavenCentralBundleRepository "
                        + "--project-prop=procwright.javaRelease=17 "
                        + "--project-prop=procwright.version=\"$PROCWRIGHT_RELEASE_VERSION\" --no-daemon",
                Map.of("PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"));
        requireRunStep(
                steps.get(12),
                "python3 .procwright-trusted/scripts/verify_local_maven_central_bundle.py --unsigned "
                        + "--repository .procwright-target/build/maven-central/repository "
                        + "--version \"$PROCWRIGHT_RELEASE_VERSION\"",
                Map.of("PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(13),
                "python3 .procwright-trusted/scripts/release_handoff.py prepare "
                        + "--repository .procwright-target/build/maven-central/repository "
                        + "--output .procwright-target/build/release-handoff "
                        + "--version \"$PROCWRIGHT_RELEASE_VERSION\" "
                        + "--commit \"$PROCWRIGHT_RELEASE_COMMIT\" "
                        + "--workflow-sha \"$GITHUB_WORKFLOW_SHA\"",
                Map.of(
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"),
                null,
                null,
                "handoff");
        requireActionStep(
                steps.get(14),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-unsigned-release",
                        "path",
                        HANDOFF_ARTIFACT_PATHS,
                        "if-no-files-found",
                        "error",
                        "retention-days",
                        1,
                        "compression-level",
                        0,
                        "overwrite",
                        false,
                        "include-hidden-files",
                        true),
                "handoff-upload");
        rejectSecretReferences(yaml, job.values(), job.path());
    }

    private static void validateStagePublish(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of(
                        "name",
                        "needs",
                        "if",
                        "runs-on",
                        "timeout-minutes",
                        "environment",
                        "permissions",
                        "defaults",
                        "steps"),
                job.path());
        requireJobScalar(job, "name", "Sign and stage verified handoff");
        requireJobScalar(job, "needs", "build");
        requireJobScalar(job, "if", RELEASE_JOB_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 60);
        requireJobScalar(job, "environment", "maven-central");
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Validate canonical release version from trusted control",
                        "Verify target provenance from trusted control",
                        "Verify current-run handoff artifact identity",
                        "Download exact current-run handoff artifact",
                        "Verify and extract unsigned handoff",
                        "Validate protected staging trust root",
                        "Sign and finalize verified Maven Central bundle",
                        "Verify signatures and stage USER_MANAGED deployment",
                        "Record privileged publication provenance",
                        "Preserve validated staged bundle"));
        requireReleasePrelude(steps, "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}");
        requireRunStep(
                steps.get(5),
                "python3 .procwright-trusted/scripts/verify_release_handoff_artifact.py",
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST", "${{ needs.build.outputs.handoff-artifact-digest }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_ID", "${{ needs.build.outputs.handoff-artifact-id }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_NAME",
                                "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-unsigned-release",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                null,
                null,
                null);
        requireActionStep(
                steps.get(6),
                DOWNLOAD_ARTIFACT,
                Map.of(
                        "artifact-ids", "${{ needs.build.outputs.handoff-artifact-id }}",
                        "path", ".procwright-handoff",
                        "merge-multiple", false,
                        "skip-decompress", true,
                        "digest-mismatch", "error"),
                null);
        requireRunStep(
                steps.get(7),
                "python3 .procwright-trusted/scripts/release_handoff.py verify "
                        + "--handoff .procwright-handoff "
                        + "--output-repository build/maven-central/repository "
                        + "--version \"$PROCWRIGHT_RELEASE_VERSION\" "
                        + "--commit \"$PROCWRIGHT_RELEASE_COMMIT\" "
                        + "--workflow-sha \"$GITHUB_WORKFLOW_SHA\" "
                        + "--github-artifact-digest \"$PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST\" "
                        + "--github-artifact-id \"$PROCWRIGHT_HANDOFF_ARTIFACT_ID\" "
                        + "--github-artifact-name \"$PROCWRIGHT_HANDOFF_ARTIFACT_NAME\"",
                Map.of(
                        "PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST", "${{ needs.build.outputs.handoff-artifact-digest }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_ID", "${{ needs.build.outputs.handoff-artifact-id }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_NAME",
                                "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-unsigned-release",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}"),
                null,
                null,
                "handoff");
        requireRunStep(
                steps.get(8),
                "[[ \"$PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT\" =~ ^[0-9A-F]{40}$ ]]",
                Map.of(
                        "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT",
                        "${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(9),
                trustedScript("sign_release_handoff.sh"),
                Map.of(
                        "PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST", "${{ needs.build.outputs.handoff-artifact-digest }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_ID", "${{ needs.build.outputs.handoff-artifact-id }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_NAME",
                                "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-unsigned-release",
                        "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT",
                                "${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}",
                        "SIGNING_KEY", "${{ secrets.SIGNING_KEY }}",
                        "SIGNING_PASSWORD", "${{ secrets.SIGNING_PASSWORD }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(10),
                """
                python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence --bundle "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-maven-central-bundle.zip" --evidence "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-signing-evidence.json" --public-key "build/maven-central/procwright-${PROCWRIGHT_RELEASE_VERSION}-signing-public-key.asc" --version "$PROCWRIGHT_RELEASE_VERSION" --fingerprint "$PROCWRIGHT_SIGNING_FINGERPRINT" --github-artifact .procwright-handoff --github-artifact-digest "$PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST" --github-artifact-id "$PROCWRIGHT_HANDOFF_ARTIFACT_ID" --github-artifact-name "$PROCWRIGHT_HANDOFF_ARTIFACT_NAME"
                bash .procwright-trusted/scripts/release/stage_central_bundle.sh
                verified_digest="$(sed -n 's/^verified_signed_bundle_sha256=//p' "$GITHUB_OUTPUT")"
                staged_digest="$(sed -n 's/^bundle_sha256=//p' "$GITHUB_OUTPUT")"
                [[ "$verified_digest" =~ ^[0-9a-f]{64}$ && "$verified_digest" == "$staged_digest" ]]
                """,
                Map.of(
                        "PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST", "${{ needs.build.outputs.handoff-artifact-digest }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_ID", "${{ needs.build.outputs.handoff-artifact-id }}",
                        "PROCWRIGHT_HANDOFF_ARTIFACT_NAME",
                                "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-unsigned-release",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}",
                        "PROCWRIGHT_SIGNING_FINGERPRINT", "${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                        "CENTRAL_USERNAME", "${{ secrets.CENTRAL_USERNAME }}",
                        "CENTRAL_PASSWORD", "${{ secrets.CENTRAL_PASSWORD }}"),
                null,
                null,
                "central");
        requireRunStep(
                steps.get(11),
                trustedScript("record_build_provenance.sh"),
                Map.of(
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.release-version }}",
                        "PROCWRIGHT_STAGED_BUNDLE_SHA256", "${{ steps.central.outputs.bundle_sha256 }}",
                        "PROCWRIGHT_UNSIGNED_HANDOFF_SHA256", "${{ steps.handoff.outputs.unsigned_bundle_sha256 }}"),
                null,
                null,
                null);
        requireActionStep(
                steps.get(12),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "procwright-${{ inputs.release-version }}-${{ inputs.release-commit }}-central-bundle",
                        "path",
                        STAGE_ARTIFACT_PATHS,
                        "if-no-files-found",
                        "error",
                        "retention-days",
                        90),
                null);
    }

    private static void validateCi(WorkflowModel workflow) {
        YamlWorkflow yaml = workflow.yaml();
        requireRoot(workflow, "CI", Set.of("name", "on", "permissions", "concurrency", "jobs"));
        requireExactKeys(yaml, workflow.triggers(), Set.of("push", "pull_request", "workflow_dispatch"), "$.on");
        requireExactMap(
                yaml,
                yaml.requiredMap(workflow.triggers(), "push", "$.on"),
                Map.of("branches", List.of("main")),
                "$.on.push");
        requireExactMap(
                yaml, yaml.requiredMap(workflow.triggers(), "pull_request", "$.on"), Map.of(), "$.on.pull_request");
        validateDispatch(
                workflow,
                Map.of(
                        "consumer-version", "Maven Central version to smoke-test as an external consumer",
                        "release-commit", "Lowercase full release commit SHA used to build the staged bundle"));
        requireExactMap(
                yaml,
                yaml.requiredMap(workflow.root(), "concurrency", "$"),
                Map.of(
                        "group", "ci-${{ github.ref }}",
                        "cancel-in-progress", "${{ github.event_name == 'pull_request' }}"),
                "$.concurrency");
        requireOrderedKeys(
                yaml,
                workflow.jobs(),
                List.of(
                        "verify",
                        "source-variants",
                        "docs",
                        "comparison-compile",
                        "central-publication-ready",
                        "consumer-smoke-maven-central"),
                "$.jobs");
        validateVerifyJob(workflow.job("verify"));
        validateSourceVariantsJob(workflow.job("source-variants"));
        validateCiDocsJob(workflow.job("docs"));
        validateComparisonJob(workflow.job("comparison-compile"));
        validateCentralPublicationJob(workflow.job("central-publication-ready"));
        validateConsumerJob(workflow.job("consumer-smoke-maven-central"));
    }

    private static void validateVerifyJob(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "if", "runs-on", "timeout-minutes", "permissions", "strategy", "steps"),
                job.path());
        requireJobScalar(job, "name", "Verify Java 17 artifact on Java ${{ matrix.java-runtime }} / ${{ matrix.os }}");
        requireJobScalar(job, "if", NON_DISPATCH_CONDITION);
        requireJobScalar(job, "runs-on", "${{ matrix.os }}");
        requireJobScalar(job, "timeout-minutes", 60);
        requireExactMap(yaml, job.permissions(), READ_CONTENTS_PERMISSION, job.path() + ".permissions");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "strategy", job.path()),
                Map.of(
                        "fail-fast",
                        false,
                        "matrix",
                        Map.of(
                                "java-runtime", List.of(17, 21, 25),
                                "os", List.of("ubuntu-latest", "macos-latest", "windows-2025"))),
                job.path() + ".strategy");
        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Checkout",
                        "Set up JDK ${{ matrix.java-runtime }}",
                        "Set up Gradle",
                        "Verify system PTY prerequisites",
                        "Verify Unix",
                        "Verify Windows",
                        "Publication smoke Unix",
                        "Publication smoke Windows",
                        "Upload failed test reports"));
        requireActionStep(steps.get(0), CHECKOUT, null, null);
        requireActionStep(
                steps.get(1),
                SETUP_JAVA,
                Map.of("distribution", "temurin", "java-version", "${{ matrix.java-runtime }}"),
                null);
        requireActionStep(steps.get(2), SETUP_GRADLE, null, null);
        requireRunStep(
                steps.get(3),
                "command -v script && command -v stty && command -v env && command -v dd",
                null,
                "runner.os != 'Windows' && matrix.java-runtime == 17",
                null,
                null);
        requireRunStep(
                steps.get(4),
                "./gradlew check publicJavaJavadocCheck --project-prop=procwright.javaRelease=17 --no-daemon",
                Map.of(
                        "ORG_GRADLE_PROJECT_procwright.requireSystemPty",
                        "${{ runner.os != 'Windows' && matrix.java-runtime == 17 }}"),
                "runner.os != 'Windows'",
                null,
                null);
        requireRunStep(
                steps.get(5),
                ".\\gradlew.bat check publicJavaJavadocCheck --project-prop=procwright.javaRelease=17 --no-daemon",
                null,
                "runner.os == 'Windows'",
                null,
                null);
        requireRunStep(steps.get(6), """
                set -euo pipefail
                repository="${RUNNER_TEMP}/procwright-m2"
                ./gradlew publishToMavenLocal \\
                  -Dmaven.repo.local="${repository}" \\
                  --project-prop=procwright.javaRelease=17 \\
                  --project-prop=procwright.version=0.1.0 \\
                  --no-daemon
                ./gradlew \\
                  :procwright-consumer-examples:test \\
                  :procwright-integrations-consumer-example:check \\
                  :procwright-kotlin-consumer-example:check \\
                  --project-prop=procwright.javaRelease=17 \\
                  --project-prop=procwright.consumerVersion=0.1.0 \\
                  --project-prop=procwright.consumerRepository="${repository}" \\
                  --dependency-verification=off \\
                  --refresh-dependencies \\
                  --rerun-tasks \\
                  --no-daemon
                ./gradlew \\
                  :procwright-consumer-examples:test \\
                  :procwright-integrations-consumer-example:check \\
                  :procwright-kotlin-consumer-example:check \\
                  --project-prop=procwright.javaRelease=17 \\
                  --project-prop=procwright.consumerVersion=0.1.0 \\
                  --project-prop=procwright.consumerRepository="${repository}" \\
                  --project-prop=procwright.consumerPomOnly=true \\
                  --dependency-verification=off \\
                  --refresh-dependencies \\
                  --rerun-tasks \\
                  --no-daemon
                """, null, "runner.os != 'Windows' && matrix.java-runtime == 17", null, null);
        requireRunStep(
                steps.get(7), """
                $repository = Join-Path $env:RUNNER_TEMP "procwright-m2"
                .\\gradlew.bat publishToMavenLocal `
                  "-Dmaven.repo.local=$repository" `
                  --project-prop=procwright.javaRelease=17 `
                  --project-prop=procwright.version=0.1.0 `
                  --no-daemon
                if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                .\\gradlew.bat `
                  :procwright-consumer-examples:test `
                  :procwright-integrations-consumer-example:check `
                  :procwright-kotlin-consumer-example:check `
                  --project-prop=procwright.javaRelease=17 `
                  --project-prop=procwright.consumerVersion=0.1.0 `
                  "--project-prop=procwright.consumerRepository=$repository" `
                  --dependency-verification=off `
                  --refresh-dependencies `
                  --rerun-tasks `
                  --no-daemon
                if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                .\\gradlew.bat `
                  :procwright-consumer-examples:test `
                  :procwright-integrations-consumer-example:check `
                  :procwright-kotlin-consumer-example:check `
                  --project-prop=procwright.javaRelease=17 `
                  --project-prop=procwright.consumerVersion=0.1.0 `
                  "--project-prop=procwright.consumerRepository=$repository" `
                  --project-prop=procwright.consumerPomOnly=true `
                  --dependency-verification=off `
                  --refresh-dependencies `
                  --rerun-tasks `
                  --no-daemon
                if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                """, null, "runner.os == 'Windows' && matrix.java-runtime == 17", null, null, "pwsh");
        requireActionStep(
                steps.get(8),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "failed-tests-${{ matrix.os }}-java-${{ matrix.java-runtime }}",
                        "path",
                        FAILED_TEST_REPORT_PATHS,
                        "if-no-files-found",
                        "ignore",
                        "retention-days",
                        7),
                null,
                "failure()");
        rejectSecretReferences(yaml, job.values(), job.path());
    }

    private static void validateSourceVariantsJob(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "if", "runs-on", "timeout-minutes", "permissions", "strategy", "steps"),
                job.path());
        requireJobScalar(job, "name", "Verify source target Java ${{ matrix.java-release }}");
        requireJobScalar(job, "if", NON_DISPATCH_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 60);
        requireExactMap(yaml, job.permissions(), READ_CONTENTS_PERMISSION, job.path() + ".permissions");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "strategy", job.path()),
                Map.of("fail-fast", false, "matrix", Map.of("java-release", List.of(21, 25))),
                job.path() + ".strategy");
        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Checkout",
                        "Set up JDK ${{ matrix.java-release }}",
                        "Set up Gradle",
                        "Verify source variant",
                        "Upload failed test reports"));
        requireActionStep(steps.get(0), CHECKOUT, null, null);
        requireActionStep(
                steps.get(1),
                SETUP_JAVA,
                Map.of("distribution", "temurin", "java-version", "${{ matrix.java-release }}"),
                null);
        requireActionStep(steps.get(2), SETUP_GRADLE, null, null);
        requireRunStep(
                steps.get(3),
                "./gradlew check publicJavaJavadocCheck "
                        + "--project-prop=procwright.javaRelease=${{ matrix.java-release }} --no-daemon",
                null,
                null,
                null,
                null);
        requireActionStep(
                steps.get(4),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "failed-tests-source-java-${{ matrix.java-release }}",
                        "path",
                        FAILED_TEST_REPORT_PATHS,
                        "if-no-files-found",
                        "ignore",
                        "retention-days",
                        7),
                null,
                "failure()");
        rejectSecretReferences(yaml, job.values(), job.path());
    }

    private static void validateCiDocsJob(WorkflowModel.Job job) {
        requireSimpleCiJob(job, "Docs", "ubuntu-24.04", 30);
        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Checkout",
                        "Set up JDK 17",
                        "Set up Python 3.13",
                        "Set up uv",
                        "Set up Gradle",
                        "Build docs site in strict mode"));
        requireActionStep(steps.get(0), CHECKOUT, null, null);
        requireSetupJava(steps.get(1));
        requireSetupPython(steps.get(2));
        requireSetupUv(steps.get(3));
        requireActionStep(steps.get(4), SETUP_GRADLE, null, null);
        requireRunStep(
                steps.get(5),
                "./gradlew publicDocsCheck --project-prop=procwright.javaRelease=17 --no-daemon",
                null,
                null,
                null,
                null);
    }

    private static void validateComparisonJob(WorkflowModel.Job job) {
        requireSimpleCiJob(job, "Comparison Check", "ubuntu-latest", 30);
        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of("Checkout", "Set up JDK 25", "Set up Gradle", "Check comparison module and JMH sources"));
        requireActionStep(steps.get(0), CHECKOUT, null, null);
        requireActionStep(steps.get(1), SETUP_JAVA, Map.of("distribution", "temurin", "java-version", 25), null);
        requireActionStep(steps.get(2), SETUP_GRADLE, null, null);
        requireRunStep(
                steps.get(3),
                "./gradlew :procwright-comparison:compileJava "
                        + ":procwright-comparison:compileTestJava "
                        + ":procwright-comparison:jmhCompileCheck "
                        + ":procwright-comparison:comparisonReportDefaultsCheck "
                        + "--project-prop=procwright.javaRelease=25 --no-daemon",
                null,
                null,
                null,
                null);
    }

    private static void requireSimpleCiJob(
            WorkflowModel.Job job, String expectedName, String expectedRunner, int expectedTimeout) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "if", "runs-on", "timeout-minutes", "permissions", "steps"),
                job.path());
        requireJobScalar(job, "name", expectedName);
        requireJobScalar(job, "if", NON_DISPATCH_CONDITION);
        requireJobScalar(job, "runs-on", expectedRunner);
        requireJobScalar(job, "timeout-minutes", expectedTimeout);
        requireExactMap(yaml, job.permissions(), READ_CONTENTS_PERMISSION, job.path() + ".permissions");
        rejectSecretReferences(yaml, job.values(), job.path());
    }

    private static void validateCentralPublicationJob(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of(
                        "name",
                        "if",
                        "runs-on",
                        "timeout-minutes",
                        "environment",
                        "permissions",
                        "defaults",
                        "outputs",
                        "steps"),
                job.path());
        requireJobScalar(job, "name", "Verify Maven Central publication");
        requireJobScalar(job, "if", RELEASE_JOB_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 60);
        requireJobScalar(job, "environment", "maven-central");
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "outputs", job.path()),
                Map.of(
                        "central-bytes-artifact-digest", "${{ steps.central-bytes-upload.outputs.artifact-digest }}",
                        "central-bytes-artifact-id", "${{ steps.central-bytes-upload.outputs.artifact-id }}",
                        "central-bytes-artifact-name", "${{ steps.central-bytes.outputs.artifact_name }}",
                        "deployment-id", "${{ steps.staging.outputs.deployment_id }}",
                        "stage-run-id", "${{ steps.staging.outputs.stage_run_id }}"),
                job.path() + ".outputs");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Validate canonical consumer version from trusted control",
                        "Verify target provenance from trusted control",
                        "Download validated staging evidence from trusted control",
                        "Wait for publication and verify every staged payload byte",
                        "Seal exact verified Central bytes for job transfer",
                        "Upload exact verified Central bytes for consumer job"));
        requireReleasePrelude(steps, "PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}");
        requireRunStep(
                steps.get(5),
                trustedScript("download_staging_evidence.sh"),
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                null,
                null,
                "staging");
        requireRunStep(
                steps.get(6),
                trustedScript("wait_for_central_publication.sh"),
                Map.of(
                        "CENTRAL_DEPLOYMENT_ID", "${{ steps.staging.outputs.deployment_id }}",
                        "CENTRAL_USERNAME", "${{ secrets.CENTRAL_USERNAME }}",
                        "CENTRAL_PASSWORD", "${{ secrets.CENTRAL_PASSWORD }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.consumer-version }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(7),
                trustedScript("seal_verified_central_bytes.sh"),
                Map.of(
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.consumer-version }}"),
                null,
                null,
                "central-bytes");
        requireActionStep(
                steps.get(8),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "${{ steps.central-bytes.outputs.artifact_name }}",
                        "path",
                        "${{ runner.temp }}/procwright-central-downloads",
                        "if-no-files-found",
                        "error",
                        "retention-days",
                        1,
                        "compression-level",
                        0,
                        "overwrite",
                        false,
                        "include-hidden-files",
                        false),
                "central-bytes-upload");
    }

    private static void validateConsumerJob(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "needs", "if", "runs-on", "timeout-minutes", "permissions", "defaults", "steps"),
                job.path());
        requireJobScalar(job, "name", "Consumer Smoke Maven Central");
        requireJobScalar(job, "needs", "central-publication-ready");
        requireJobScalar(job, "if", RELEASE_JOB_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 60);
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Validate canonical consumer version from trusted control",
                        "Verify target provenance from trusted control",
                        "Checkout verified release target",
                        "Verify target checkout from trusted control",
                        "Download validated staging evidence from trusted control",
                        "Download exact verified Central bytes from producer job",
                        "Set up JDK 17",
                        "Set up Gradle",
                        "Bootstrap isolated normal and POM-only verification candidates",
                        "Fail-closed merge byte-verified release checksums",
                        "Compile and run strict external consumers",
                        "Record successful Central consumer proof",
                        "Preserve successful Central consumer proof"));
        requireReleasePrelude(steps, "PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}");
        requireTargetCheckout(steps.get(5), "${{ inputs.release-commit }}");
        requireTargetVerification(steps.get(6), "${{ inputs.release-commit }}");
        requireTargetRun(
                steps.get(7),
                trustedTargetScript("download_staging_evidence.sh"),
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                "staging");
        requireTargetRun(
                steps.get(8),
                trustedTargetScript("download_verified_central_bytes.sh"),
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID",
                                "${{ needs.central-publication-ready.outputs.central-bytes-artifact-id }}",
                        "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_NAME",
                                "${{ needs.central-publication-ready.outputs.central-bytes-artifact-name }}",
                        "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_DIGEST",
                                "${{ needs.central-publication-ready.outputs.central-bytes-artifact-digest }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ inputs.consumer-version }}"));
        requireSetupJava(steps.get(9));
        requireActionStep(steps.get(10), SETUP_GRADLE, null, null);
        requireTargetRun(
                steps.get(11),
                trustedTargetScript("bootstrap_consumer_verification.sh"),
                Map.of("PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}"));
        requireTargetRun(
                steps.get(12),
                trustedTargetScript("merge_consumer_verification.sh"),
                Map.of("PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}"));
        requireTargetRun(
                steps.get(13),
                trustedTargetScript("run_strict_consumers.sh"),
                Map.of("PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}"));
        requireTargetRun(
                steps.get(14),
                trustedTargetScript("record_consumer_proof.sh"),
                Map.of(
                        "PROCWRIGHT_CONSUMER_VERSION", "${{ inputs.consumer-version }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "CENTRAL_DEPLOYMENT_ID", "${{ needs.central-publication-ready.outputs.deployment-id }}",
                        "STAGE_RUN_ID", "${{ needs.central-publication-ready.outputs.stage-run-id }}"));
        requireActionStep(
                steps.get(15),
                UPLOAD_ARTIFACT,
                Map.of(
                        "name",
                        "procwright-${{ inputs.consumer-version }}-${{ inputs.release-commit }}-central-consumer-smoke",
                        "path",
                        ".procwright-target/build/central-consumer-smoke/provenance.txt",
                        "if-no-files-found",
                        "error",
                        "retention-days",
                        90),
                null);
        rejectSecretReferences(yaml, job.values(), job.path());
    }

    private static void validateDocs(WorkflowModel workflow) {
        YamlWorkflow yaml = workflow.yaml();
        requireRoot(workflow, "Docs Deploy", Set.of("name", "on", "permissions", "jobs"));
        requireExactKeys(yaml, workflow.triggers(), Set.of("release", "workflow_dispatch"), "$.on");
        requireExactMap(
                yaml,
                yaml.requiredMap(workflow.triggers(), "release", "$.on"),
                Map.of("types", List.of("published")),
                "$.on.release");
        validateDispatch(
                workflow,
                Map.of(
                        "release-version", "Released SemVer version, without a leading v",
                        "release-commit", "Lowercase full commit SHA referenced by the release tag"));
        requireOrderedKeys(yaml, workflow.jobs(), List.of("recovery", "build", "deploy"), "$.jobs");
        validateDocsRecovery(workflow.job("recovery"));
        validateDocsBuild(workflow.job("build"));
        validateDocsDeploy(workflow.job("deploy"));
    }

    private static void validateDocsRecovery(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of("name", "if", "runs-on", "timeout-minutes", "environment", "permissions", "defaults", "steps"),
                job.path());
        requireJobScalar(job, "name", "Verify Release Recovery Evidence");
        requireJobScalar(job, "if", RELEASE_JOB_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 30);
        requireJobScalar(job, "environment", "release-recovery");
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Verify release identity from trusted control",
                        "Validate protected recovery trust roots",
                        "Verify persistent Maven Central recovery evidence"));
        requireRunStep(
                steps.get(0),
                COMMIT_VALIDATION,
                Map.of("PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                null,
                null,
                null);
        requireTrustedCheckout(steps.get(1));
        requireSetupPython(steps.get(2));
        requireRunStep(
                steps.get(3),
                "python3 .procwright-trusted/scripts/verify_docs_release_identity.py",
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_EVENT_NAME", "${{ github.event_name }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_RELEASE_REF", "${{ inputs.release-version }}",
                        "PROCWRIGHT_TRUSTED_ROOT", TRUSTED_ROOT,
                        "PROCWRIGHT_WORKFLOW_SHA", "${{ github.workflow_sha }}"),
                null,
                null,
                "identity");
        requireRunStep(
                steps.get(4),
                """
                [[ "$PROCWRIGHT_SIGNING_FINGERPRINT" =~ ^[0-9A-F]{40}$ ]]
                [[ "${#PROCWRIGHT_SIGNING_PUBLIC_KEY}" -ge 100 && "${#PROCWRIGHT_SIGNING_PUBLIC_KEY}" -le 49152 ]]
                [[ "$PROCWRIGHT_SIGNING_PUBLIC_KEY" == "-----BEGIN PGP PUBLIC KEY BLOCK-----"$'\\n'* ]]
                [[ "$PROCWRIGHT_SIGNING_PUBLIC_KEY" == *$'\\n'"-----END PGP PUBLIC KEY BLOCK-----" ]]
                [[ "$PROCWRIGHT_SIGNING_PUBLIC_KEY" != *"PRIVATE KEY BLOCK"* ]]
                """,
                Map.of(
                        "PROCWRIGHT_SIGNING_FINGERPRINT", "${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT }}",
                        "PROCWRIGHT_SIGNING_PUBLIC_KEY", "${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY }}"),
                null,
                null,
                null);
        requireRunStep(
                steps.get(5),
                "python3 .procwright-trusted/scripts/verify_maven_central_release.py \"$PROCWRIGHT_RELEASE_VERSION\"",
                Map.of(
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ steps.identity.outputs.release_commit }}",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ steps.identity.outputs.release_version }}",
                        "PROCWRIGHT_SIGNING_FINGERPRINT", "${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT }}",
                        "PROCWRIGHT_SIGNING_PUBLIC_KEY", "${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY }}"),
                null,
                null,
                null);
    }

    private static void validateDocsBuild(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of(
                        "name",
                        "needs",
                        "if",
                        "runs-on",
                        "timeout-minutes",
                        "permissions",
                        "defaults",
                        "outputs",
                        "steps"),
                job.path());
        requireJobScalar(job, "name", "Build Docs Site");
        requireJobScalar(job, "needs", "recovery");
        requireJobScalar(job, "if", DOCS_BUILD_CONDITION);
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 30);
        requireExactMap(yaml, job.permissions(), RELEASE_JOB_PERMISSIONS, job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "outputs", job.path()),
                Map.of(
                        "pages-artifact-id", "${{ steps.pages-upload.outputs.artifact_id }}",
                        "pages-content-sha256", "${{ steps.pages-content.outputs.pages_content_sha256 }}"),
                job.path() + ".outputs");

        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Validate release commit before checkout",
                        "Checkout trusted release control",
                        "Set up Python 3.13",
                        "Verify release identity from trusted control",
                        "Checkout verified release target",
                        "Verify target checkout from trusted control",
                        "Set up JDK 17",
                        "Set up uv",
                        "Set up Gradle",
                        "Build docs site in strict mode",
                        "Seal exact Pages content",
                        "Upload Pages artifact"));
        requireRunStep(
                steps.get(0),
                COMMIT_VALIDATION,
                Map.of("PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                MANUAL_DISPATCH,
                null,
                null);
        requireTrustedCheckout(steps.get(1));
        requireSetupPython(steps.get(2));
        requireRunStep(
                steps.get(3),
                "python3 .procwright-trusted/scripts/verify_docs_release_identity.py",
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_EVENT_NAME", "${{ github.event_name }}",
                        "PROCWRIGHT_RELEASE_COMMIT",
                                "${{ github.event_name == 'workflow_dispatch' && inputs.release-commit || '' }}",
                        "PROCWRIGHT_RELEASE_REF",
                                "${{ github.event_name == 'release' && github.event.release.tag_name || inputs.release-version }}",
                        "PROCWRIGHT_TRUSTED_ROOT", TRUSTED_ROOT,
                        "PROCWRIGHT_WORKFLOW_SHA", "${{ github.workflow_sha }}"),
                null,
                null,
                "identity");
        requireTargetCheckout(steps.get(4), "${{ steps.identity.outputs.release_commit }}");
        requireTargetVerification(steps.get(5), "${{ steps.identity.outputs.release_commit }}");
        requireSetupJava(steps.get(6));
        requireSetupUv(steps.get(7));
        requireActionStep(steps.get(8), SETUP_GRADLE, null, null);
        requireRunStep(
                steps.get(9),
                "bash .procwright-trusted/scripts/release/build_release_docs.sh",
                Map.of(
                        "PROCWRIGHT_PAGES_OUTPUT", "build/public-docs",
                        "PROCWRIGHT_RELEASE_VERSION", "${{ steps.identity.outputs.release_version }}",
                        "PROCWRIGHT_TARGET_ROOT", TARGET_ROOT),
                null,
                null,
                null);
        requireRunStep(
                steps.get(10),
                "python3 .procwright-trusted/scripts/verify_pages_artifact.py seal --directory build/public-docs",
                null,
                null,
                null,
                "pages-content");
        requireActionStep(
                steps.get(11),
                "actions/upload-pages-artifact",
                Map.of("name", "github-pages", "path", "build/public-docs"),
                "pages-upload");
    }

    private static void validateDocsDeploy(WorkflowModel.Job job) {
        YamlWorkflow yaml = job.yaml();
        requireExactKeys(
                yaml,
                job.values(),
                Set.of(
                        "name",
                        "needs",
                        "runs-on",
                        "timeout-minutes",
                        "permissions",
                        "defaults",
                        "environment",
                        "steps"),
                job.path());
        requireJobScalar(job, "name", "Deploy Docs Site");
        requireJobScalar(job, "needs", "build");
        requireJobScalar(job, "runs-on", "ubuntu-24.04");
        requireJobScalar(job, "timeout-minutes", 15);
        requireExactMap(
                yaml,
                job.permissions(),
                Map.of("actions", "read", "contents", "read", "id-token", "write", "pages", "write"),
                job.path() + ".permissions");
        requireExactMap(
                yaml, yaml.requiredMap(job.values(), "defaults", job.path()), BASH_DEFAULTS, job.path() + ".defaults");
        requireExactMap(
                yaml,
                yaml.requiredMap(job.values(), "environment", job.path()),
                Map.of("name", "github-pages", "url", "${{ steps.deployment.outputs.page_url }}"),
                job.path() + ".environment");
        List<WorkflowModel.Step> steps = job.steps();
        requireExactStepNames(
                job,
                steps,
                List.of(
                        "Checkout trusted Pages verifier",
                        "Set up Python 3.13",
                        "Verify exact current-run Pages artifact",
                        "Configure GitHub Pages",
                        "Deploy to GitHub Pages"));
        requireTrustedCheckout(steps.get(0));
        requireSetupPython(steps.get(1));
        requireRunStep(
                steps.get(2),
                "python3 .procwright-trusted/scripts/verify_pages_artifact.py verify",
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_PAGES_ARTIFACT_ID", "${{ needs.build.outputs.pages-artifact-id }}",
                        "PROCWRIGHT_PAGES_CONTENT_SHA256", "${{ needs.build.outputs.pages-content-sha256 }}",
                        "PROCWRIGHT_PAGES_RUN_COMMIT", "${{ github.sha }}",
                        "PROCWRIGHT_PAGES_RUN_ID", "${{ github.run_id }}",
                        "PROCWRIGHT_TRUSTED_ROOT", TRUSTED_ROOT,
                        "PROCWRIGHT_WORKFLOW_SHA", "${{ github.workflow_sha }}"),
                null,
                null,
                null);
        requireActionStep(steps.get(3), "actions/configure-pages", null, null);
        requireActionStep(steps.get(4), "actions/deploy-pages", Map.of("artifact_name", "github-pages"), "deployment");
    }

    private static void requireRoot(WorkflowModel workflow, String name, Set<String> expectedKeys) {
        YamlWorkflow yaml = workflow.yaml();
        requireExactKeys(yaml, workflow.root(), expectedKeys, "$");
        requireStringValue(yaml, workflow.root(), "name", name, "$");
        requireExactMap(yaml, yaml.requiredMap(workflow.root(), "permissions", "$"), Map.of(), "$.permissions");
    }

    private static void validateDispatch(WorkflowModel workflow, Map<String, String> inputs) {
        YamlWorkflow yaml = workflow.yaml();
        Map<String, Object> dispatch = yaml.requiredMap(workflow.triggers(), "workflow_dispatch", "$.on");
        requireExactKeys(yaml, dispatch, Set.of("inputs"), "$.on.workflow_dispatch");
        Map<String, Object> actualInputs = yaml.requiredMap(dispatch, "inputs", "$.on.workflow_dispatch");
        requireExactKeys(yaml, actualInputs, inputs.keySet(), "$.on.workflow_dispatch.inputs");
        for (Map.Entry<String, String> expected : inputs.entrySet()) {
            String path = "$.on.workflow_dispatch.inputs." + expected.getKey();
            requireExactMap(
                    yaml,
                    yaml.requiredMap(actualInputs, expected.getKey(), "$.on.workflow_dispatch.inputs"),
                    Map.of("description", expected.getValue(), "required", true, "type", "string"),
                    path);
        }
    }

    private static void requireTrustedCheckout(WorkflowModel.Step step) {
        requireActionStep(step, CHECKOUT, TRUSTED_CHECKOUT, null);
    }

    private static void requireReleasePrelude(
            List<WorkflowModel.Step> steps, String versionVariable, String versionExpression) {
        requireRunStep(
                steps.get(0),
                COMMIT_VALIDATION,
                Map.of("PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}"),
                null,
                null,
                null);
        requireTrustedCheckout(steps.get(1));
        requireSetupPython(steps.get(2));
        requireRunStep(
                steps.get(3),
                "python3 .procwright-trusted/scripts/release_contract.py version \"$" + versionVariable + "\"",
                Map.of(versionVariable, versionExpression),
                null,
                null,
                null);
        requireRunStep(
                steps.get(4),
                trustedScript("verify_release_commit_provenance.sh"),
                Map.of(
                        "GH_TOKEN", "${{ github.token }}",
                        "PROCWRIGHT_RELEASE_COMMIT", "${{ inputs.release-commit }}",
                        "PROCWRIGHT_TRUSTED_ROOT", TRUSTED_ROOT),
                null,
                null,
                null);
    }

    private static void requireTargetCheckout(WorkflowModel.Step step, String reference) {
        requireActionStep(
                step,
                CHECKOUT,
                Map.of("ref", reference, "fetch-depth", 0, "persist-credentials", false, "path", TARGET_ROOT),
                null);
    }

    private static void requireTargetVerification(WorkflowModel.Step step, String expectedCommit) {
        requireRunStep(
                step,
                "bash .procwright-trusted/scripts/release/verify_target_checkout.sh",
                Map.of(
                        "PROCWRIGHT_RELEASE_COMMIT", expectedCommit,
                        "PROCWRIGHT_TARGET_ROOT", TARGET_ROOT),
                null,
                null,
                null);
    }

    private static void requireSetupPython(WorkflowModel.Step step) {
        requireActionStep(step, SETUP_PYTHON, Map.of("python-version", "3.13.14"), null);
    }

    private static void requireSetupJava(WorkflowModel.Step step) {
        requireActionStep(step, SETUP_JAVA, Map.of("distribution", "temurin", "java-version", "17.0.19+10"), null);
    }

    private static void requireSetupUv(WorkflowModel.Step step) {
        requireActionStep(step, SETUP_UV, Map.of("version", "0.10.12"), null);
    }

    private static String trustedTargetScript(String name) {
        return "bash ../.procwright-trusted/scripts/release/" + name;
    }

    private static String trustedScript(String name) {
        return "bash .procwright-trusted/scripts/release/" + name;
    }

    private static void requireTrustedReleaseRun(WorkflowModel.Step step, String scriptName, String versionVariable) {
        requireRunStep(
                step,
                trustedScript(scriptName),
                Map.of(
                        versionVariable,
                        "${{ inputs."
                                + (versionVariable.equals("PROCWRIGHT_RELEASE_VERSION")
                                        ? "release-version"
                                        : "consumer-version")
                                + " }}"),
                null,
                null,
                null);
    }

    private static void requireTargetRun(WorkflowModel.Step step, String run, Map<String, Object> environment) {
        requireTargetRun(step, run, environment, null);
    }

    private static void requireTargetRun(
            WorkflowModel.Step step, String run, Map<String, Object> environment, String id) {
        requireRunStep(step, run, environment, null, TARGET_ROOT, id);
    }

    private static void requireRunStep(
            WorkflowModel.Step step,
            String run,
            Map<String, Object> environment,
            String condition,
            String workingDirectory,
            String id) {
        requireRunStep(step, run, environment, condition, workingDirectory, id, null);
    }

    private static void requireRunStep(
            WorkflowModel.Step step,
            String run,
            Map<String, Object> environment,
            String condition,
            String workingDirectory,
            String id,
            String shell) {
        Set<String> expectedKeys = new LinkedHashSet<>(List.of("name", "run"));
        if (environment != null) {
            expectedKeys.add("env");
        }
        if (condition != null) {
            expectedKeys.add("if");
        }
        if (workingDirectory != null) {
            expectedKeys.add("working-directory");
        }
        if (id != null) {
            expectedKeys.add("id");
        }
        if (shell != null) {
            expectedKeys.add("shell");
        }
        requireExactKeys(step.yaml(), step.values(), expectedKeys, step.path());
        requireStringValue(step.yaml(), step.values(), "run", run, step.path());
        requireOptionalString(step, "if", condition);
        requireOptionalString(step, "working-directory", workingDirectory);
        requireOptionalString(step, "id", id);
        requireOptionalString(step, "shell", shell);
        if (environment != null) {
            requireExactMap(step.yaml(), step.requiredMap("env"), environment, step.path() + ".env");
        }
    }

    private static void requireActionStep(WorkflowModel.Step step, String action, Map<String, Object> with, String id) {
        requireActionStep(step, action, with, id, null);
    }

    private static void requireActionStep(
            WorkflowModel.Step step, String action, Map<String, Object> with, String id, String condition) {
        Set<String> expectedKeys = new LinkedHashSet<>(List.of("name", "uses"));
        if (with != null) {
            expectedKeys.add("with");
        }
        if (id != null) {
            expectedKeys.add("id");
        }
        if (condition != null) {
            expectedKeys.add("if");
        }
        requireExactKeys(step.yaml(), step.values(), expectedKeys, step.path());
        String actual = step.uses().orElseThrow(() -> step.yaml().failure(step.path() + " must define uses"));
        String actualAction = actual.substring(0, actual.lastIndexOf('@'));
        if (!actualAction.equals(action)) {
            throw step.yaml().failure(step.path() + " must use " + action + ", got " + actualAction);
        }
        requireOptionalString(step, "id", id);
        requireOptionalString(step, "if", condition);
        if (with != null) {
            requireExactMap(step.yaml(), step.requiredMap("with"), with, step.path() + ".with");
        }
    }

    private static void requireOptionalString(WorkflowModel.Step step, String key, String expected) {
        if (expected != null) {
            requireStringValue(step.yaml(), step.values(), key, expected, step.path());
        }
    }

    private static void requireExactStepNames(
            WorkflowModel.Job job, List<WorkflowModel.Step> steps, List<String> expectedNames) {
        List<String> actualNames = steps.stream().map(WorkflowModel.Step::name).toList();
        if (!actualNames.equals(expectedNames)) {
            throw job.yaml()
                    .failure(job.path() + ".steps must be exactly ordered; expected=" + expectedNames + ", actual="
                            + actualNames);
        }
    }

    private static void requireJobScalar(WorkflowModel.Job job, String key, Object expected) {
        requireValue(job.yaml(), job.values(), key, expected, job.path());
    }

    private static void requireStringValue(
            YamlWorkflow yaml, Map<String, Object> map, String key, String expected, String path) {
        Object actual = yaml.required(map, key, path);
        if (!(actual instanceof String) || !actual.equals(expected)) {
            throw yaml.failure(path + "." + key + " must be " + expected + ", got " + actual);
        }
    }

    private static void requireValue(
            YamlWorkflow yaml, Map<String, Object> map, String key, Object expected, String path) {
        Object actual = yaml.required(map, key, path);
        if (!Objects.equals(actual, expected)) {
            throw yaml.failure(path + "." + key + " must be " + expected + ", got " + actual);
        }
    }

    private static void requireExactMap(
            YamlWorkflow yaml, Map<String, Object> actual, Map<String, ?> expected, String path) {
        requireExactKeys(yaml, actual, expected.keySet(), path);
        for (Map.Entry<String, ?> entry : expected.entrySet()) {
            requireValue(yaml, actual, entry.getKey(), entry.getValue(), path);
        }
    }

    private static void requireExactKeys(
            YamlWorkflow yaml, Map<String, Object> actual, Set<String> expected, String path) {
        if (!actual.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual.keySet());
            Set<String> extra = new LinkedHashSet<>(actual.keySet());
            extra.removeAll(expected);
            throw yaml.failure(path + " has unexpected keys; missing=" + missing + ", extra=" + extra);
        }
    }

    private static void requireOrderedKeys(
            YamlWorkflow yaml, Map<String, Object> actual, List<String> expected, String path) {
        List<String> actualKeys = List.copyOf(actual.keySet());
        if (!actualKeys.equals(expected)) {
            throw yaml.failure(path + " must have exactly ordered keys " + expected + ", got " + actualKeys);
        }
    }

    private static void rejectSecretReferences(YamlWorkflow yaml, Object value, String path) {
        if (value instanceof String text) {
            if (SECRET_EXPRESSION.matcher(text).find()) {
                throw yaml.failure(path + " must not access the secrets context");
            }
            return;
        }
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                rejectSecretReferences(yaml, entry.getValue(), path + "." + entry.getKey());
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                rejectSecretReferences(yaml, list.get(index), path + "[" + index + "]");
            }
        }
    }
}
