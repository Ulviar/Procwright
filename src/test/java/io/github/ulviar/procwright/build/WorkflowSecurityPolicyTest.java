/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

@Tag("workflow-security")
class WorkflowSecurityPolicyTest {
    private static final Path WORKFLOWS = Path.of(".github", "workflows");

    @Test
    void repositoryWorkflowsSatisfyPolicy() throws IOException {
        try (var files = Files.list(WORKFLOWS)) {
            files.filter(path ->
                            path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> assertDoesNotThrow(() -> validate(path)));
        }
    }

    @Test
    void parserRejectsDuplicateKeys() throws IOException {
        String workflow = read("ci.yml").replaceFirst("permissions: \\{\\}", "permissions: {}\npermissions: {}");
        assertThrows(YamlEngineException.class, () -> validate("duplicate.yml", workflow));
    }

    @Test
    void parserRejectsMultipleDocuments() throws IOException {
        String workflow = read("ci.yml") + "\n---\n{}\n";
        assertThrows(IllegalArgumentException.class, () -> validate("multiple.yml", workflow));
    }

    @Test
    void quotedKeysCannotHideMutableArtifactDownload() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace("uses: actions/download-artifact@", "\"uses\": actions/download-artifact@")
                .replace("artifact-ids: ${{ needs.build.outputs.handoff-artifact-id }}", "name: mutable");
        assertThrows(IllegalArgumentException.class, () -> validate("quoted.yml", workflow));
    }

    @Test
    void commentsCannotFakeTrustedCheckout() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace("uses: actions/checkout@", "uses: actions/setup-java@")
                .replace(
                        "      - name: Set up Python 3.13",
                        "      # uses: actions/checkout@trusted\n"
                                + "      # ref: ${{ github.workflow_sha }}\n"
                                + "      # path: .procwright-trusted\n"
                                + "      - name: Set up Python 3.13");
        assertThrows(IllegalArgumentException.class, () -> validate("comments.yml", workflow));
    }

    @Test
    void centralStagingCannotPrecedeSignatureVerification() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace(
                        "          python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence",
                        "          bash .procwright-trusted/scripts/release/stage_central_bundle.sh\n"
                                + "          python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence");
        assertThrows(IllegalArgumentException.class, () -> validate("order.yml", workflow));
    }

    @Test
    void secretBearingJobRequiresApprovedEnvironment() throws IOException {
        String workflow = read("publish-maven-central.yml").replace("environment: maven-central", "environment: test");
        assertThrows(IllegalArgumentException.class, () -> validate("environment.yml", workflow));
    }

    @Test
    void redundantPermissionIsRejected() throws IOException {
        String workflow =
                read("ci.yml").replaceFirst("      contents: read", "      contents: read\n      actions: read");
        assertThrows(IllegalArgumentException.class, () -> validate("permissions.yml", workflow));
    }

    @Test
    void literalArtifactIdIsRejected() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace("artifact-ids: ${{ needs.build.outputs.handoff-artifact-id }}", "artifact-ids: 1");
        assertThrows(IllegalArgumentException.class, () -> validate("artifact.yml", workflow));
    }

    @Test
    void unpinnedActionIsRejected() throws IOException {
        String workflow = read("ci.yml").replaceFirst("actions/checkout@[0-9a-f]{40}", "actions/checkout@v6");
        assertThrows(IllegalArgumentException.class, () -> validate("pinning.yml", workflow));
    }

    @Test
    void pagesDeploymentRequiresPriorBoundVerification() throws IOException {
        String workflow =
                read("docs-deploy.yml").replace("verify_pages_artifact.py verify", "verify_pages_artifact.py");
        assertThrows(IllegalArgumentException.class, () -> validate("pages.yml", workflow));
    }

    @Test
    void echoedPagesVerifierIsNotExecution() throws IOException {
        String workflow = read("docs-deploy.yml")
                .replace(
                        "run: python3 .procwright-trusted/scripts/verify_pages_artifact.py verify",
                        "run: echo python3 .procwright-trusted/scripts/verify_pages_artifact.py verify");
        assertThrows(IllegalArgumentException.class, () -> validate("pages-echo.yml", workflow));
    }

    @Test
    void echoedHandoffVerifierIsNotExecution() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace(
                        "run: python3 .procwright-trusted/scripts/release_handoff.py verify --handoff",
                        "run: echo python3 .procwright-trusted/scripts/release_handoff.py verify --handoff");
        assertThrows(IllegalArgumentException.class, () -> validate("handoff-echo.yml", workflow));
    }

    @Test
    void echoedSigningVerifierIsNotExecution() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace(
                        "          python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence",
                        "          echo python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence");
        assertThrows(IllegalArgumentException.class, () -> validate("signing-echo.yml", workflow));
    }

    @Test
    void helpModeIsNotVerification() throws IOException {
        String pages = read("docs-deploy.yml")
                .replace("verify_pages_artifact.py verify", "verify_pages_artifact.py verify --help");
        assertThrows(IllegalArgumentException.class, () -> validate("pages-help.yml", pages));

        String central = read("publish-maven-central.yml")
                .replace("verify-signing-evidence --bundle", "verify-signing-evidence --help --bundle");
        assertThrows(IllegalArgumentException.class, () -> validate("central-help.yml", central));

        String abbreviated = read("publish-maven-central.yml")
                .replace("verify-signing-evidence --bundle", "verify-signing-evidence --he --bundle");
        assertThrows(IllegalArgumentException.class, () -> validate("central-help-prefix.yml", abbreviated));
    }

    @Test
    void backgroundVerifierIsNotVerification() throws IOException {
        String workflow = read("docs-deploy.yml")
                .replace(
                        "run: python3 .procwright-trusted/scripts/verify_pages_artifact.py verify",
                        "run: python3 .procwright-trusted/scripts/verify_pages_artifact.py verify & true");
        assertThrows(IllegalArgumentException.class, () -> validate("pages-background.yml", workflow));
    }

    @Test
    void privilegedJobCannotUseMutableContainer() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace(
                        "    runs-on: ubuntu-24.04\n    timeout-minutes: 60\n    environment: maven-central",
                        "    runs-on: ubuntu-24.04\n    container: attacker/image:latest\n"
                                + "    timeout-minutes: 60\n    environment: maven-central");
        assertThrows(IllegalArgumentException.class, () -> validate("container.yml", workflow));
    }

    @Test
    void targetCheckoutCannotFallBackToDefaultRef() throws IOException {
        String workflow =
                read("publish-maven-central.yml").replace("          ref: ${{ inputs.release-commit }}\n", "");
        assertThrows(IllegalArgumentException.class, () -> validate("target-ref.yml", workflow));
    }

    @Test
    void artifactOutputMustComeFromUploadStep() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace("${{ steps.handoff-upload.outputs.artifact-id }}", "${{ steps.handoff.outputs.artifact-id }}");
        assertThrows(IllegalArgumentException.class, () -> validate("producer.yml", workflow));
    }

    @Test
    void securityStepCannotOverrideShell() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace(
                        "      - name: Verify signatures and stage USER_MANAGED deployment",
                        "      - name: Verify signatures and stage USER_MANAGED deployment\n"
                                + "        shell: custom {0}");
        assertThrows(IllegalArgumentException.class, () -> validate("shell.yml", workflow));
    }

    @Test
    void securityStepCannotBeConditional() throws IOException {
        String workflow = read("docs-deploy.yml")
                .replace(
                        "      - name: Verify exact current-run Pages artifact",
                        "      - name: Verify exact current-run Pages artifact\n        if: false");
        assertThrows(IllegalArgumentException.class, () -> validate("condition.yml", workflow));
    }

    @Test
    void arbitraryCheckoutRefIsRejected() throws IOException {
        String workflow = read("publish-maven-central.yml")
                .replace("ref: ${{ inputs.release-commit }}", "ref: refs/heads/untrusted");
        assertThrows(IllegalArgumentException.class, () -> validate("ref.yml", workflow));
    }

    @Test
    void jobLevelContinueOnErrorIsRejected() throws IOException {
        String workflow = read("ci.yml")
                .replace("    runs-on: ${{ matrix.os }}", "    runs-on: ${{ matrix.os }}\n    continue-on-error: true");
        assertThrows(IllegalArgumentException.class, () -> validate("continue.yml", workflow));
    }

    private static String read(String name) throws IOException {
        return Files.readString(WORKFLOWS.resolve(name));
    }

    private static void validate(Path path) throws IOException {
        validate(path.toString(), Files.readString(path));
    }

    private static void validate(String source, String workflow) {
        WorkflowSecurityPolicy.validate(source, workflow);
    }
}
