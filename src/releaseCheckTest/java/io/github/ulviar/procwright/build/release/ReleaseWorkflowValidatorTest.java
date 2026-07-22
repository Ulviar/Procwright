/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseWorkflowValidatorTest {
    private static final String CHECKOUT_SHA = "df4cb1c069e1874edd31b4311f1884172cec0e10";
    private static final String DOCS_BUILD_CONDITION =
            "!cancelled() && (github.event_name == 'release' || (github.event_name == 'workflow_dispatch' && "
                    + "github.ref == 'refs/heads/main' && github.sha == github.workflow_sha && "
                    + "needs.recovery.result == 'success'))";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsCurrentReleaseWorkflows() throws IOException {
        assertDoesNotThrow(fixture()::validate);
    }

    @Test
    void rejectsDuplicateKeysAndCollectionAliases() throws IOException {
        WorkflowFixture duplicate = fixture();
        duplicate.append(duplicate.stage(), "\npermissions: {}\n");
        assertRejected(duplicate, "duplicate");

        WorkflowFixture alias = fixture();
        alias.replaceRequired(alias.stage(), "permissions: {}\n", "permissions: &shared {}\n");
        alias.replaceRequired(
                alias.stage(),
                "    permissions:\n      actions: read\n      contents: read\n",
                "    permissions: *shared\n");
        assertRejected(alias, "alias");
    }

    @Test
    void rejectsUnpinnedOrNonScalarActionReferences() throws IOException {
        String pinned = "actions/checkout@" + CHECKOUT_SHA;
        for (String hostile : new String[] {
            "actions/checkout",
            "actions/checkout@v6",
            "actions/checkout@" + CHECKOUT_SHA.substring(0, 12),
            "actions/checkout@" + CHECKOUT_SHA.toUpperCase(Locale.ROOT),
            "true"
        }) {
            WorkflowFixture fixture = fixture();
            fixture.replaceRequired(fixture.stage(), "uses: " + pinned, "uses: " + hostile);
            assertRejected(fixture, "uses");
        }
    }

    @Test
    void rejectsAnyRootExecutionOrPermissionOverride() throws IOException {
        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.stage(), "permissions: {}\n", "permissions: {}\nenv:\n  GH_TOKEN: hostile\n");
        assertRejected(environment, "unexpected keys");

        WorkflowFixture defaults = fixture();
        defaults.replaceRequired(
                defaults.docs(), "permissions: {}\n", "permissions: {}\ndefaults:\n  run:\n    shell: sh\n");
        assertRejected(defaults, "unexpected keys");

        WorkflowFixture permission = fixture();
        permission.replaceRequired(permission.ci(), "permissions: {}\n", "permissions:\n  contents: write\n");
        assertRejected(permission, "permissions");
    }

    @Test
    void rejectsExtraOrChangedTriggers() throws IOException {
        WorkflowFixture pushFilter = fixture();
        pushFilter.replaceRequired(
                pushFilter.ci(),
                "    branches:\n      - main\n",
                "    branches:\n      - main\n    tags:\n      - '*'\n");
        assertRejected(pushFilter, "unexpected keys");

        WorkflowFixture pullRequestFilter = fixture();
        pullRequestFilter.replaceRequired(
                pullRequestFilter.ci(), "  pull_request: {}\n", "  pull_request:\n    branches: [main]\n");
        assertRejected(pullRequestFilter, "pull_request");

        WorkflowFixture stageTrigger = fixture();
        stageTrigger.replaceRequired(
                stageTrigger.stage(), "on:\n  workflow_dispatch:\n", "on:\n  push: {}\n  workflow_dispatch:\n");
        assertRejected(stageTrigger, "unexpected keys");

        WorkflowFixture releaseFilter = fixture();
        releaseFilter.replaceRequired(
                releaseFilter.docs(), "      - published\n", "      - published\n    branches: [main]\n");
        assertRejected(releaseFilter, "release");
    }

    @Test
    void rejectsDispatchInputDefaultsTypesAndDescriptions() throws IOException {
        WorkflowFixture defaultValue = fixture();
        defaultValue.replaceRequired(
                defaultValue.stage(),
                "        required: true\n        type: string\n",
                "        required: true\n        type: string\n        default: 1.2.3\n");
        assertRejected(defaultValue, "unexpected keys");

        WorkflowFixture optional = fixture();
        optional.replaceRequired(optional.ci(), "        required: true\n", "        required: false\n");
        assertRejected(optional, "required");

        WorkflowFixture wrongType = fixture();
        wrongType.replaceRequired(wrongType.docs(), "        type: string\n", "        type: boolean\n");
        assertRejected(wrongType, "type");

        WorkflowFixture description = fixture();
        description.replaceRequired(
                description.stage(),
                "description: SemVer version to stage, without a leading v",
                "description: Version");
        assertRejected(description, "description");
    }

    @Test
    void rejectsJobEnvironmentDefaultsRunnerConditionAndFailureOverrides() throws IOException {
        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.stage(), "    environment: maven-central\n", "    environment: production\n");
        assertRejected(environment, "environment");

        WorkflowFixture shell = fixture();
        shell.replaceRequired(shell.stage(), "        shell: bash\n", "        shell: sh\n");
        assertRejected(shell, "defaults");

        WorkflowFixture runner = fixture();
        runner.replaceRequired(runner.docs(), "    runs-on: ubuntu-24.04\n", "    runs-on: ubuntu-latest\n");
        assertRejected(runner, "runs-on");

        WorkflowFixture condition = fixture();
        condition.replaceRequired(
                condition.ci(),
                "    if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.sha == github.workflow_sha\n",
                "    if: github.event_name == 'workflow_dispatch'\n");
        assertRejected(condition, "if");

        WorkflowFixture continueOnError = fixture();
        continueOnError.replaceRequired(
                continueOnError.stage(),
                "    timeout-minutes: 60\n",
                "    timeout-minutes: 60\n    continue-on-error: true\n");
        assertRejected(continueOnError, "continue-on-error");

        WorkflowFixture jobEnvironment = fixture();
        jobEnvironment.replaceRequired(
                jobEnvironment.docs(), "    defaults:\n", "    env:\n      GH_TOKEN: hostile\n    defaults:\n");
        assertRejected(jobEnvironment, "unexpected keys");
    }

    @Test
    void rejectsCancellationResistantOrUnguardedDocsBuildCondition() throws IOException {
        WorkflowFixture always = fixture();
        always.replaceRequired(
                always.docs(),
                "    if: \"" + DOCS_BUILD_CONDITION + "\"\n",
                "    if: \"" + DOCS_BUILD_CONDITION.replace("!cancelled()", "always()") + "\"\n");
        assertRejected(always, "if");

        WorkflowFixture unguarded = fixture();
        unguarded.replaceRequired(
                unguarded.docs(),
                "    if: \"" + DOCS_BUILD_CONDITION + "\"\n",
                "    if: \"" + DOCS_BUILD_CONDITION.replace("!cancelled() && ", "") + "\"\n");
        assertRejected(unguarded, "if");
    }

    @Test
    void rejectsJobPermissionChangesAndUnknownJobLevelCapabilities() throws IOException {
        WorkflowFixture permission = fixture();
        permission.replaceRequired(permission.stage(), "      contents: read\n", "      contents: write\n");
        assertRejected(permission, "permissions");

        WorkflowFixture jobUses = fixture();
        jobUses.replaceRequired(
                jobUses.ci(),
                "    steps:\n      - name: Checkout\n",
                "    uses: owner/repository/.github/workflows/job.yml@" + CHECKOUT_SHA
                        + "\n    steps:\n      - name: Checkout\n");
        assertRejected(jobUses, "unexpected keys");

        WorkflowFixture secrets = fixture();
        secrets.replaceRequired(
                secrets.ci(),
                "    steps:\n      - name: Checkout\n",
                "    secrets: inherit\n    steps:\n      - name: Checkout\n");
        assertRejected(secrets, "unexpected keys");
    }

    @Test
    void rejectsUnknownOrReorderedJobs() throws IOException {
        WorkflowFixture stageJob = fixture();
        stageJob.append(
                stageJob.stage(),
                "\n  secret-reader:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: echo hostile\n");
        assertRejected(stageJob, "jobs");

        WorkflowFixture ciJob = fixture();
        ciJob.append(
                ciJob.ci(),
                "\n  secret-reader:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: echo ${{ secrets.CENTRAL_PASSWORD }}\n");
        assertRejected(ciJob, "jobs");

        WorkflowFixture order = fixture();
        order.swapRequired(order.ci(), "  verify:\n", "  source-variants:\n");
        assertRejected(order, "ordered keys");
    }

    @Test
    void rejectsUnknownReorderedOrRenamedCriticalSteps() throws IOException {
        WorkflowFixture extra = fixture();
        extra.replaceRequired(
                extra.stage(),
                "      - name: Preserve validated staged bundle\n",
                "      - name: Read release secret\n"
                        + "        env:\n"
                        + "          TOKEN: ${{ secrets.CENTRAL_PASSWORD }}\n"
                        + "        run: echo hostile\n\n"
                        + "      - name: Preserve validated staged bundle\n");
        assertRejected(extra, "steps");

        WorkflowFixture reorder = fixture();
        reorder.swapRequired(
                reorder.stage(),
                "      - name: Verify target provenance from trusted control\n",
                "      - name: Checkout verified release target\n");
        assertRejected(reorder, "steps");

        WorkflowFixture renamed = fixture();
        renamed.replaceRequired(
                renamed.docs(),
                "      - name: Verify release identity from trusted control\n",
                "      - name: Verify something\n");
        assertRejected(renamed, "steps");
    }

    @Test
    void rejectsWeakPreCheckoutValidationAndEnvironmentSubstitution() throws IOException {
        WorkflowFixture validation = fixture();
        validation.replaceRequired(
                validation.stage(),
                "run: '[[ \"$PROCWRIGHT_RELEASE_COMMIT\" =~ ^[0-9a-f]{40}$ ]]'",
                "run: '[[ \"$PROCWRIGHT_RELEASE_COMMIT\" =~ ^[0-9a-f]+$ ]]'");
        assertRejected(validation, "run");

        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.stage(),
                "PROCWRIGHT_RELEASE_COMMIT: ${{ inputs.release-commit }}",
                "PROCWRIGHT_RELEASE_COMMIT: deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        assertRejected(environment, "env");

        WorkflowFixture extraEnvironment = fixture();
        extraEnvironment.replaceRequired(
                extraEnvironment.docs(),
                "          PROCWRIGHT_WORKFLOW_SHA: ${{ github.workflow_sha }}\n",
                "          PROCWRIGHT_WORKFLOW_SHA: ${{ github.workflow_sha }}\n          PYTHONPATH: .procwright-target\n");
        assertRejected(extraEnvironment, "env");
    }

    @Test
    void rejectsTrustedCheckoutReferenceDepthCredentialsAndPathMutations() throws IOException {
        WorkflowFixture reference = fixture();
        reference.replaceRequired(
                reference.stage(),
                "          ref: ${{ github.workflow_sha }}\n",
                "          ref: ${{ inputs.release-commit }}\n");
        assertRejected(reference, "with");

        WorkflowFixture depth = fixture();
        depth.replaceRequired(depth.ci(), "          fetch-depth: 0\n", "          fetch-depth: 1\n");
        assertRejected(depth, "with");

        WorkflowFixture credentials = fixture();
        credentials.replaceRequired(
                credentials.docs(), "          persist-credentials: false\n", "          persist-credentials: true\n");
        assertRejected(credentials, "with");

        WorkflowFixture path = fixture();
        path.replaceRequired(
                path.stage(), "          path: .procwright-trusted\n", "          path: .procwright-target\n");
        assertRejected(path, "with");
    }

    @Test
    void rejectsTargetCheckoutAndVerificationMutations() throws IOException {
        WorkflowFixture targetReference = fixture();
        targetReference.replaceRequired(
                targetReference.docs(),
                "          ref: ${{ steps.identity.outputs.release_commit }}\n",
                "          ref: ${{ github.sha }}\n");
        assertRejected(targetReference, "with");

        WorkflowFixture targetPath = fixture();
        targetPath.replaceRequired(targetPath.ci(), "          path: .procwright-target\n", "          path: .\n");
        assertRejected(targetPath, "with");

        WorkflowFixture verifier = fixture();
        verifier.replaceRequired(
                verifier.stage(),
                "run: bash .procwright-trusted/scripts/release/verify_target_checkout.sh",
                "run: bash .procwright-target/scripts/release/verify_target_checkout.sh");
        assertRejected(verifier, "run");

        WorkflowFixture targetEnvironment = fixture();
        targetEnvironment.replaceRequired(
                targetEnvironment.stage(),
                "PROCWRIGHT_TARGET_ROOT: .procwright-target",
                "PROCWRIGHT_TARGET_ROOT: .procwright-trusted");
        assertRejected(targetEnvironment, "env");
    }

    @Test
    void rejectsAnyTargetCodeBeforeTrustedProvenancePasses() throws IOException {
        WorkflowFixture canonicalVersion = fixture();
        canonicalVersion.replaceRequired(
                canonicalVersion.stage(),
                "python3 .procwright-trusted/scripts/release_contract.py",
                "python3 .procwright-target/scripts/release_contract.py");
        assertRejected(canonicalVersion, "run");

        WorkflowFixture provenance = fixture();
        provenance.replaceRequired(
                provenance.ci(),
                "bash .procwright-trusted/scripts/release/verify_release_commit_provenance.sh",
                "bash .procwright-target/scripts/release/verify_release_commit_provenance.sh");
        assertRejected(provenance, "run");

        WorkflowFixture order = fixture();
        order.swapRequired(
                order.ci(),
                "      - name: Verify target provenance from trusted control\n",
                "      - name: Checkout verified release target\n");
        assertRejected(order, "steps");
    }

    @Test
    void rejectsGradleOrReleaseScriptsOutsideVerifiedTarget() throws IOException {
        WorkflowFixture gradle = fixture();
        gradle.replaceRequired(
                gradle.stage(),
                "        working-directory: .procwright-target\n",
                "        working-directory: .procwright-trusted\n");
        assertRejected(gradle, "working-directory");

        WorkflowFixture script = fixture();
        script.replaceRequired(
                script.ci(),
                "bash ../.procwright-trusted/scripts/release/download_staging_evidence.sh",
                "bash scripts/release/download_staging_evidence.sh");
        assertRejected(script, "run");

        WorkflowFixture docs = fixture();
        docs.replaceRequired(
                docs.docs(),
                "PROCWRIGHT_TARGET_ROOT: .procwright-target",
                "PROCWRIGHT_TARGET_ROOT: .procwright-trusted");
        assertRejected(docs, "env");
    }

    @Test
    void rejectsShellControlOperatorsInterpretersCommentsAndStepShellOverrides() throws IOException {
        WorkflowFixture ignoredFailure = fixture();
        ignoredFailure.replaceRequired(
                ignoredFailure.stage(),
                "--project-prop=procwright.version=\"$PROCWRIGHT_RELEASE_VERSION\" --no-daemon",
                "--project-prop=procwright.version=\"$PROCWRIGHT_RELEASE_VERSION\" --no-daemon || true");
        assertRejected(ignoredFailure, "run");

        WorkflowFixture interpreter = fixture();
        interpreter.replaceRequired(
                interpreter.docs(),
                "run: python3 .procwright-trusted/scripts/verify_docs_release_identity.py",
                "run: python3 -c 'print(\"verified\")'");
        assertRejected(interpreter, "run");

        WorkflowFixture comment = fixture();
        comment.replaceRequired(
                comment.stage(),
                "          bash .procwright-trusted/scripts/release/stage_central_bundle.sh",
                "          # bash .procwright-trusted/scripts/release/stage_central_bundle.sh\n"
                        + "          echo skipped");
        assertRejected(comment, "run");

        WorkflowFixture shell = fixture();
        shell.replaceRequired(
                shell.stage(),
                "        working-directory: .procwright-target\n        run: ./gradlew releaseCandidateCheck",
                "        working-directory: .procwright-target\n        shell: sh\n        run: ./gradlew releaseCandidateCheck");
        assertRejected(shell, "unexpected keys");
    }

    @Test
    void rejectsUnknownSecretConsumerInOrdinaryCiJob() throws IOException {
        WorkflowFixture fixture = fixture();
        fixture.replaceRequired(
                fixture.ci(),
                "    steps:\n      - name: Checkout\n",
                "    steps:\n"
                        + "      - name: Unknown secret consumer\n"
                        + "        env:\n"
                        + "          TOKEN: ${{ secrets.CENTRAL_PASSWORD }}\n"
                        + "        run: echo hostile\n\n"
                        + "      - name: Checkout\n");

        assertRejected(fixture, "steps");
    }

    @Test
    void rejectsUnknownGithubTokenConsumerInOrdinaryCiJob() throws IOException {
        WorkflowFixture fixture = fixture();
        fixture.replaceRequired(
                fixture.ci(),
                "    steps:\n      - name: Checkout\n",
                "    steps:\n"
                        + "      - name: Unknown token consumer\n"
                        + "        env:\n"
                        + "          TOKEN: ${{ github.token }}\n"
                        + "        run: echo hostile\n\n"
                        + "      - name: Checkout\n");

        assertRejected(fixture, "steps");
    }

    @Test
    void rejectsConsumerSecretAndArtifactMutations() throws IOException {
        WorkflowFixture secret = fixture();
        secret.replaceRequired(
                secret.ci(),
                "CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}",
                "CENTRAL_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}");
        assertRejected(secret, "env");

        WorkflowFixture artifactPath = fixture();
        artifactPath.replaceRequired(
                artifactPath.ci(),
                "path: .procwright-target/build/central-consumer-smoke/provenance.txt",
                "path: .procwright-trusted/build/central-consumer-smoke/provenance.txt");
        assertRejected(artifactPath, "with");

        WorkflowFixture artifactName = fixture();
        artifactName.replaceRequired(
                artifactName.ci(), "-central-consumer-smoke\n          path:", "-anything\n          path:");
        assertRejected(artifactName, "with");
    }

    @Test
    void rejectsStageArtifactListAndActionSchemaMutations() throws IOException {
        WorkflowFixture missingEvidence = fixture();
        missingEvidence.replaceRequired(
                missingEvidence.stage(),
                "            build/maven-central/procwright-${{ inputs.release-version }}-provenance.txt\n",
                "");
        assertRejected(missingEvidence, "with");

        WorkflowFixture extraEvidence = fixture();
        extraEvidence.replaceRequired(
                extraEvidence.stage(),
                "            build/maven-central/procwright-${{ inputs.release-version }}-central-deployment.json\n",
                "            build/maven-central/procwright-${{ inputs.release-version }}-central-deployment.json\n"
                        + "            .procwright-trusted/secrets.txt\n");
        assertRejected(extraEvidence, "with");

        WorkflowFixture actionEnvironment = fixture();
        actionEnvironment.replaceRequired(
                actionEnvironment.stage(),
                "      - name: Preserve validated staged bundle\n        uses:",
                "      - name: Preserve validated staged bundle\n        env:\n          TOKEN: hostile\n        uses:");
        assertRejected(actionEnvironment, "unexpected keys");
    }

    @Test
    void rejectsCentralStepIdentityDigestBindingAndCriticalOrderMutations() throws IOException {
        WorkflowFixture missingId = fixture();
        missingId.replaceRequired(missingId.stage(), "        id: central\n        run: |", "        run: |");
        assertRejected(missingId, "id");

        WorkflowFixture wrongDigest = fixture();
        wrongDigest.replaceRequired(
                wrongDigest.stage(),
                "PROCWRIGHT_STAGED_BUNDLE_SHA256: ${{ steps.central.outputs.bundle_sha256 }}",
                "PROCWRIGHT_STAGED_BUNDLE_SHA256: ${{ steps.handoff.outputs.unsigned_bundle_sha256 }}");
        assertRejected(wrongDigest, "env");

        WorkflowFixture reordered = fixture();
        reordered.swapRequired(
                reordered.stage(),
                "      - name: Verify signatures and stage USER_MANAGED deployment\n",
                "      - name: Record privileged publication provenance\n");
        assertRejected(reordered, "steps");

        WorkflowFixture missingIdentity = fixture();
        missingIdentity.replaceRequired(
                missingIdentity.ci(),
                "          PROCWRIGHT_RELEASE_COMMIT: ${{ inputs.release-commit }}\n",
                "          PROCWRIGHT_RELEASE_COMMIT: ${{ github.sha }}\n");
        assertRejected(missingIdentity, "env");
    }

    @Test
    void rejectsAnyTargetCheckoutOrExecutionInPrivilegedPublishJob() throws IOException {
        WorkflowFixture targetCheckout = fixture();
        targetCheckout.replaceRequired(
                targetCheckout.stage(),
                "      - name: Verify current-run handoff artifact identity\n",
                "      - name: Checkout hostile target\n"
                        + "        uses: actions/checkout@"
                        + CHECKOUT_SHA
                        + "\n        with:\n          ref: ${{ inputs.release-commit }}\n\n"
                        + "      - name: Verify current-run handoff artifact identity\n");
        assertRejected(targetCheckout, "steps");

        WorkflowFixture targetRun = fixture();
        targetRun.replaceRequired(
                targetRun.stage(),
                "run: bash .procwright-trusted/scripts/release/sign_release_handoff.sh",
                "run: bash .procwright-target/scripts/release/sign_release_handoff.sh");
        assertRejected(targetRun, "run");

        WorkflowFixture targetDirectory = fixture();
        targetDirectory.replaceRequired(
                targetDirectory.stage(),
                "      - name: Sign and finalize verified Maven Central bundle\n        run:",
                "      - name: Sign and finalize verified Maven Central bundle\n"
                        + "        working-directory: .procwright-target\n        run:");
        assertRejected(targetDirectory, "unexpected keys");
    }

    @Test
    void rejectsHandoffIdentityDownloadAndOutputMutations() throws IOException {
        WorkflowFixture output = fixture();
        output.replaceRequired(
                output.stage(),
                "handoff-artifact-id: ${{ steps.handoff-upload.outputs.artifact-id }}",
                "handoff-artifact-id: ${{ steps.handoff-upload.outputs.artifact-url }}");
        assertRejected(output, "outputs");

        WorkflowFixture wrongId = fixture();
        wrongId.replaceRequired(
                wrongId.stage(), "artifact-ids: ${{ needs.build.outputs.handoff-artifact-id }}", "artifact-ids: '123'");
        assertRejected(wrongId, "with");

        WorkflowFixture trustedOverwrite = fixture();
        trustedOverwrite.replaceRequired(
                trustedOverwrite.stage(),
                "          path: .procwright-handoff\n",
                "          path: .procwright-trusted\n");
        assertRejected(trustedOverwrite, "with");

        WorkflowFixture crossRun = fixture();
        crossRun.replaceRequired(
                crossRun.stage(),
                "          digest-mismatch: error\n",
                "          digest-mismatch: error\n          run-id: 123\n          github-token: ${{ github.token }}\n");
        assertRejected(crossRun, "with");

        WorkflowFixture ignoredDigest = fixture();
        ignoredDigest.replaceRequired(
                ignoredDigest.stage(), "          digest-mismatch: error\n", "          digest-mismatch: warn\n");
        assertRejected(ignoredDigest, "with");
    }

    @Test
    void rejectsRemovalOfRawArtifactMetadataAndSignatureEvidenceProofs() throws IOException {
        WorkflowFixture decompressed = fixture();
        decompressed.replaceRequired(
                decompressed.stage(), "          skip-decompress: true\n", "          skip-decompress: false\n");
        assertRejected(decompressed, "with");

        WorkflowFixture metadata = fixture();
        metadata.replaceRequired(
                metadata.stage(),
                "      - name: Verify exact unsigned Maven repository\n",
                "      - name: Ignore generated Maven metadata\n");
        assertRejected(metadata, "steps");

        WorkflowFixture unsignedDigest = fixture();
        unsignedDigest.replaceRequired(
                unsignedDigest.stage(),
                "--github-artifact-digest \"$PROCWRIGHT_HANDOFF_ARTIFACT_DIGEST\"",
                "--github-artifact-digest sha256:0000000000000000000000000000000000000000000000000000000000000000");
        assertRejected(unsignedDigest, "run");

        WorkflowFixture signatureProof = fixture();
        signatureProof.replaceRequired(
                signatureProof.stage(), "release_handoff.py verify-signing-evidence", "release_handoff.py prepare");
        assertRejected(signatureProof, "run");

        WorkflowFixture fingerprint = fixture();
        fingerprint.replaceRequired(
                fingerprint.stage(),
                "PROCWRIGHT_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                "PROCWRIGHT_SIGNING_FINGERPRINT: 0000000000000000000000000000000000000000");
        assertRejected(fingerprint, "env");

        WorkflowFixture stagedDigest = fixture();
        stagedDigest.replaceRequired(
                stagedDigest.stage(),
                "[[ \"$verified_digest\" =~ ^[0-9a-f]{64}$ && \"$verified_digest\" == \"$staged_digest\" ]]",
                "[[ \"$staged_digest\" =~ ^[0-9a-f]{64}$ ]]");
        assertRejected(stagedDigest, "run");
    }

    @Test
    void rejectsSelfDerivedSigningIdentity() throws IOException {
        WorkflowFixture signer = fixture();
        signer.replaceRequired(
                signer.stage(),
                "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT: ${{ steps.signing.outputs.signing_fingerprint }}");
        assertRejected(signer, "env");

        WorkflowFixture evidence = fixture();
        evidence.replaceRequired(
                evidence.stage(),
                "PROCWRIGHT_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                "PROCWRIGHT_SIGNING_FINGERPRINT: ${{ steps.signing.outputs.signing_fingerprint }}");
        assertRejected(evidence, "env");
    }

    @Test
    void rejectsMissingOrWeakStagingTrustRootValidation() throws IOException {
        WorkflowFixture scope = fixture();
        scope.replaceRequired(
                scope.stage(),
                "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}",
                "PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_SIGNING_FINGERPRINT }}");
        assertRejected(scope, "env");

        WorkflowFixture format = fixture();
        format.replaceRequired(
                format.stage(),
                "run: '[[ \"$PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT\" =~ ^[0-9A-F]{40}$ ]]'",
                "run: '[[ -n \"$PROCWRIGHT_APPROVED_SIGNING_FINGERPRINT\" ]]'");
        assertRejected(format, "run");
    }

    @Test
    void rejectsSecretsOrReleaseEnvironmentInUnprivilegedBuildJob() throws IOException {
        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.stage(),
                "  build:\n    name: Build unsigned release handoff\n",
                "  build:\n    name: Build unsigned release handoff\n    environment: maven-central\n");
        assertRejected(environment, "unexpected keys");

        WorkflowFixture secret = fixture();
        secret.replaceRequired(
                secret.stage(),
                "      - name: Build unsigned Maven repository\n"
                        + "        working-directory: .procwright-target\n"
                        + "        run: ./gradlew cleanMavenCentralBundleRepository",
                "      - name: Build unsigned Maven repository\n"
                        + "        working-directory: .procwright-target\n"
                        + "        run: echo ${{ secrets.SIGNING_KEY }} && ./gradlew cleanMavenCentralBundleRepository");
        assertRejected(secret, "run");
    }

    @Test
    void rejectsCentralCredentialsInTargetConsumerJobAndTargetCodeInCentralJob() throws IOException {
        WorkflowFixture targetCode = fixture();
        targetCode.replaceRequired(
                targetCode.ci(),
                "      - name: Download validated staging evidence from trusted control\n",
                "      - name: Execute target before Central\n"
                        + "        working-directory: .procwright-target\n"
                        + "        run: ./gradlew help\n\n"
                        + "      - name: Download validated staging evidence from trusted control\n");
        assertRejected(targetCode, "steps");

        WorkflowFixture consumerEnvironment = fixture();
        consumerEnvironment.replaceRequired(
                consumerEnvironment.ci(),
                "  consumer-smoke-maven-central:\n    name: Consumer Smoke Maven Central\n",
                "  consumer-smoke-maven-central:\n"
                        + "    name: Consumer Smoke Maven Central\n"
                        + "    environment: maven-central\n");
        assertRejected(consumerEnvironment, "unexpected keys");

        WorkflowFixture secret = fixture();
        secret.replaceRequired(
                secret.ci(),
                "          STAGE_RUN_ID: ${{ needs.central-publication-ready.outputs.stage-run-id }}\n",
                "          STAGE_RUN_ID: ${{ needs.central-publication-ready.outputs.stage-run-id }}\n"
                        + "          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}\n");
        assertRejected(secret, "env");
    }

    @Test
    void rejectsCentralByteArtifactIdentityAndJobWiringMutations() throws IOException {
        WorkflowFixture digestOutput = fixture();
        digestOutput.replaceRequired(
                digestOutput.ci(),
                "central-bytes-artifact-digest: ${{ steps.central-bytes-upload.outputs.artifact-digest }}",
                "central-bytes-artifact-digest: ${{ steps.central-bytes-upload.outputs.artifact-id }}");
        assertRejected(digestOutput, "outputs");

        WorkflowFixture uploadPath = fixture();
        uploadPath.replaceRequired(
                uploadPath.ci(),
                "          path: ${{ runner.temp }}/procwright-central-downloads\n",
                "          path: build/maven-central\n");
        assertRejected(uploadPath, "with");

        WorkflowFixture consumerId = fixture();
        consumerId.replaceRequired(
                consumerId.ci(),
                "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID: ${{ needs.central-publication-ready.outputs.central-bytes-artifact-id }}",
                "PROCWRIGHT_CENTRAL_BYTES_ARTIFACT_ID: ${{ needs.central-publication-ready.outputs.central-bytes-artifact-name }}");
        assertRejected(consumerId, "env");

        WorkflowFixture missingVerification = fixture();
        missingVerification.replaceRequired(
                missingVerification.ci(),
                "      - name: Download exact verified Central bytes from producer job\n",
                "      - name: Skip exact verified Central bytes from producer job\n");
        assertRejected(missingVerification, "steps");
    }

    @Test
    void rejectsTrustedSparseCheckoutAndJobBoundaryMutations() throws IOException {
        WorkflowFixture sparse = fixture();
        sparse.replaceRequired(
                sparse.stage(), "          sparse-checkout: scripts\n", "          sparse-checkout: .\n");
        assertRejected(sparse, "with");

        WorkflowFixture cone = fixture();
        cone.replaceRequired(
                cone.ci(),
                "          sparse-checkout-cone-mode: true\n",
                "          sparse-checkout-cone-mode: false\n");
        assertRejected(cone, "with");

        WorkflowFixture dependency = fixture();
        dependency.replaceRequired(dependency.stage(), "    needs: build\n", "    needs: other-job\n");
        assertRejected(dependency, "needs");

        WorkflowFixture reordered = fixture();
        reordered.swapRequired(reordered.stage(), "  build:\n", "  publish:\n");
        assertRejected(reordered, "ordered keys");
    }

    @Test
    void rejectsPagesUploadMutationsAndExtraExecutionKeys() throws IOException {
        WorkflowFixture path = fixture();
        path.replaceRequired(path.docs(), "          path: build/public-docs\n", "          path: /tmp/docs\n");
        assertRejected(path, "with");

        WorkflowFixture name = fixture();
        name.replaceRequired(name.docs(), "          name: github-pages\n", "          name: arbitrary\n");
        assertRejected(name, "with");

        WorkflowFixture condition = fixture();
        condition.replaceRequired(
                condition.docs(),
                "      - name: Upload Pages artifact\n        id: pages-upload\n        uses:",
                "      - name: Upload Pages artifact\n        id: pages-upload\n        if: always()\n        uses:");
        assertRejected(condition, "unexpected keys");

        WorkflowFixture continueOnError = fixture();
        continueOnError.replaceRequired(
                continueOnError.docs(),
                "      - name: Upload Pages artifact\n        id: pages-upload\n        uses:",
                "      - name: Upload Pages artifact\n        id: pages-upload\n        continue-on-error: true\n        uses:");
        assertRejected(continueOnError, "continue-on-error");

        WorkflowFixture uploadId = fixture();
        uploadId.replaceRequired(
                uploadId.docs(),
                "      - name: Upload Pages artifact\n        id: pages-upload\n",
                "      - name: Upload Pages artifact\n");
        assertRejected(uploadId, "id");

        WorkflowFixture outputId = fixture();
        outputId.replaceRequired(
                outputId.docs(),
                "pages-artifact-id: ${{ steps.pages-upload.outputs.artifact_id }}",
                "pages-artifact-id: github-pages");
        assertRejected(outputId, "outputs");

        WorkflowFixture contentSeal = fixture();
        contentSeal.replaceRequired(
                contentSeal.docs(),
                "python3 .procwright-trusted/scripts/verify_pages_artifact.py seal --directory build/public-docs",
                "printf '%s\\n' trusted");
        assertRejected(contentSeal, "run");
    }

    @Test
    void rejectsPagesDeployPermissionsEnvironmentActionsAndArtifactMutations() throws IOException {
        WorkflowFixture permissions = fixture();
        permissions.replaceRequired(permissions.docs(), "      pages: write\n", "      pages: read\n");
        assertRejected(permissions, "permissions");

        WorkflowFixture actionsPermission = fixture();
        actionsPermission.replaceRequired(actionsPermission.docs(), "      actions: read\n", "      actions: write\n");
        assertRejected(actionsPermission, "permissions");

        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.docs(),
                "    environment:\n      name: github-pages\n      url:",
                "    environment:\n      name: production\n      url:");
        assertRejected(environment, "environment");

        WorkflowFixture configureWith = fixture();
        configureWith.replaceRequired(
                configureWith.docs(),
                "      - name: Configure GitHub Pages\n        uses:",
                "      - name: Configure GitHub Pages\n        with:\n          token: hostile\n        uses:");
        assertRejected(configureWith, "unexpected keys");

        WorkflowFixture artifact = fixture();
        artifact.replaceRequired(
                artifact.docs(), "          artifact_name: github-pages\n", "          artifact_name: arbitrary\n");
        assertRejected(artifact, "with");

        WorkflowFixture run = fixture();
        run.replaceRequired(
                run.docs(),
                "      - name: Verify exact current-run Pages artifact\n",
                "      - name: Mutate artifact\n        run: echo hostile\n\n"
                        + "      - name: Verify exact current-run Pages artifact\n");
        assertRejected(run, "steps");

        WorkflowFixture artifactId = fixture();
        artifactId.replaceRequired(
                artifactId.docs(),
                "PROCWRIGHT_PAGES_ARTIFACT_ID: ${{ needs.build.outputs.pages-artifact-id }}",
                "PROCWRIGHT_PAGES_ARTIFACT_ID: github-pages");
        assertRejected(artifactId, "env");

        WorkflowFixture artifactDigest = fixture();
        artifactDigest.replaceRequired(
                artifactDigest.docs(),
                "PROCWRIGHT_PAGES_CONTENT_SHA256: ${{ needs.build.outputs.pages-content-sha256 }}",
                "PROCWRIGHT_PAGES_CONTENT_SHA256: ${{ github.sha }}");
        assertRejected(artifactDigest, "env");

        WorkflowFixture currentRun = fixture();
        currentRun.replaceRequired(
                currentRun.docs(), "PROCWRIGHT_PAGES_RUN_ID: ${{ github.run_id }}", "PROCWRIGHT_PAGES_RUN_ID: 1");
        assertRejected(currentRun, "env");

        WorkflowFixture verifierOrder = fixture();
        verifierOrder.swapRequired(
                verifierOrder.docs(),
                "      - name: Verify exact current-run Pages artifact\n",
                "      - name: Deploy to GitHub Pages\n");
        assertRejected(verifierOrder, "steps");
    }

    @Test
    void rejectsUnapprovedMavenCentralRecoveryTrustMaterial() throws IOException {
        WorkflowFixture environment = fixture();
        environment.replaceRequired(
                environment.docs(), "    environment: release-recovery\n", "    environment: maven-central\n");
        assertRejected(environment, "environment");

        WorkflowFixture branchPolicyBypass = fixture();
        branchPolicyBypass.replaceRequired(
                branchPolicyBypass.docs(),
                "if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.sha == github.workflow_sha",
                "if: github.event_name == 'workflow_dispatch'");
        assertRejected(branchPolicyBypass, "if");

        WorkflowFixture buildBypass = fixture();
        buildBypass.replaceRequired(buildBypass.docs(), "    needs: recovery\n", "    needs: deploy\n");
        assertRejected(buildBypass, "needs");

        WorkflowFixture fingerprint = fixture();
        fingerprint.replaceRequired(
                fingerprint.docs(),
                "PROCWRIGHT_SIGNING_FINGERPRINT: ${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT }}",
                "PROCWRIGHT_SIGNING_FINGERPRINT: ${{ inputs.release-version }}");
        assertRejected(fingerprint, "env");

        WorkflowFixture sharedScope = fixture();
        sharedScope.replaceRequired(
                sharedScope.docs(),
                "${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT }}",
                "${{ vars.MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT }}");
        assertRejected(sharedScope, "env");

        WorkflowFixture publicKey = fixture();
        publicKey.replaceRequired(
                publicKey.docs(),
                "PROCWRIGHT_SIGNING_PUBLIC_KEY: ${{ vars.MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY }}",
                "PROCWRIGHT_SIGNING_PUBLIC_KEY: downloaded-key");
        assertRejected(publicKey, "env");

        WorkflowFixture format = fixture();
        format.replaceRequired(
                format.docs(),
                "[[ \"$PROCWRIGHT_SIGNING_FINGERPRINT\" =~ ^[0-9A-F]{40}$ ]]",
                "[[ -n \"$PROCWRIGHT_SIGNING_FINGERPRINT\" ]]");
        assertRejected(format, "run");

        WorkflowFixture publicKeyFormat = fixture();
        publicKeyFormat.replaceRequired(
                publicKeyFormat.docs(),
                "[[ \"$PROCWRIGHT_SIGNING_PUBLIC_KEY\" == \"-----BEGIN PGP PUBLIC KEY BLOCK-----\"$'\\n'* ]]",
                "[[ -n \"$PROCWRIGHT_SIGNING_PUBLIC_KEY\" ]]");
        assertRejected(publicKeyFormat, "run");
    }

    private WorkflowFixture fixture() throws IOException {
        Path repositoryRoot = Path.of(System.getProperty("procwright.repositoryRoot"));
        Path fixtureRoot = Files.createTempDirectory(temporaryDirectory, "workflows-");
        return new WorkflowFixture(
                copy(repositoryRoot.resolve(".github/workflows/ci.yml"), fixtureRoot.resolve("ci.yml")),
                copy(
                        repositoryRoot.resolve(".github/workflows/publish-maven-central.yml"),
                        fixtureRoot.resolve("publish-maven-central.yml")),
                copy(
                        repositoryRoot.resolve(".github/workflows/docs-deploy.yml"),
                        fixtureRoot.resolve("docs-deploy.yml")));
    }

    private static Path copy(Path source, Path destination) throws IOException {
        Files.copy(source, destination);
        return destination;
    }

    private static void assertRejected(WorkflowFixture fixture, String expectedMessage) {
        WorkflowValidationException error = assertThrows(WorkflowValidationException.class, fixture::validate);
        assertTrue(
                error.getMessage().toLowerCase(Locale.ROOT).contains(expectedMessage),
                () -> "Expected diagnostic containing '" + expectedMessage + "', got: " + error.getMessage());
    }

    private record WorkflowFixture(Path ci, Path stage, Path docs) {
        void validate() {
            new ReleaseWorkflowValidator().validate(ci, stage, docs);
        }

        void replaceRequired(Path file, String expected, String replacement) throws IOException {
            String source = Files.readString(file);
            if (!source.contains(expected)) {
                throw new AssertionError("Fixture text is missing: " + expected);
            }
            Files.writeString(
                    file,
                    source.replaceFirst(
                            java.util.regex.Pattern.quote(expected),
                            java.util.regex.Matcher.quoteReplacement(replacement)));
        }

        void append(Path file, String text) throws IOException {
            Files.writeString(file, Files.readString(file) + text);
        }

        void swapRequired(Path file, String left, String right) throws IOException {
            String source = Files.readString(file);
            if (!source.contains(left) || !source.contains(right)) {
                throw new AssertionError("Fixture text is missing a swap operand");
            }
            String marker = "__PROCWRIGHT_WORKFLOW_SWAP__";
            Files.writeString(
                    file, source.replace(left, marker).replace(right, left).replace(marker, right));
        }
    }
}
