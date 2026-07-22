/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

final class WorkflowSecurityPolicy {
    private static final Pattern ACTION = Pattern.compile("[^@]+@[0-9a-f]{40}");
    private static final Pattern CONTAINER = Pattern.compile("docker://[^@]+@sha256:[0-9a-f]{64}");
    private static final Pattern SECRET = Pattern.compile("\\$\\{\\{\\s*secrets(?:\\.|\\s*\\[)");
    private static final Pattern ARTIFACT_ID =
            Pattern.compile("\\$\\{\\{\\s*needs\\.([A-Za-z0-9_-]+)\\.outputs\\.[A-Za-z0-9_-]*artifact[_-]id\\s*}}");
    private static final Pattern ARTIFACT_DIGEST = Pattern.compile(
            "needs\\.([A-Za-z0-9_-]+)\\.outputs\\.(?:[A-Za-z0-9_-]*artifact[_-]digest|[A-Za-z0-9_-]*sha256)");
    private static final Pattern STEP_OUTPUT =
            Pattern.compile("\\$\\{\\{\\s*steps\\.([A-Za-z0-9_-]+)\\.outputs\\.([A-Za-z0-9_-]+)\\s*}}");
    private static final Set<String> PROTECTED_ENVIRONMENTS =
            Set.of("github-pages", "maven-central", "release-recovery");
    private static final String HANDOFF_VERIFY = "python3 .procwright-trusted/scripts/release_handoff.py verify";
    private static final String SIGNING_VERIFY =
            "python3 .procwright-trusted/scripts/release_handoff.py verify-signing-evidence";
    private static final String CENTRAL_STAGE = "bash .procwright-trusted/scripts/release/stage_central_bundle.sh";
    private static final String PAGES_VERIFY = "python3 .procwright-trusted/scripts/verify_pages_artifact.py verify";
    private static final Set<String> HANDOFF_VERIFY_ARGUMENTS = Set.of(
            "--handoff",
            "--output-repository",
            "--version",
            "--commit",
            "--workflow-sha",
            "--github-artifact-digest",
            "--github-artifact-id",
            "--github-artifact-name");
    private static final Set<String> SIGNING_VERIFY_ARGUMENTS = Set.of(
            "--bundle",
            "--evidence",
            "--public-key",
            "--version",
            "--fingerprint",
            "--github-artifact",
            "--github-artifact-digest",
            "--github-artifact-id",
            "--github-artifact-name");

    private WorkflowSecurityPolicy() {}

    static void validate(String source, String yaml) {
        Map<String, Object> root = load(source, yaml);
        requireMap(root.get("permissions"), source + ".permissions").isEmptyOrThrow("root permissions must be empty");
        MapValue triggers = requireMap(root.get("on"), source + ".on");
        reject(triggers.values().containsKey("pull_request_target"), source, "pull_request_target is forbidden");

        MapValue jobs = requireMap(root.get("jobs"), source + ".jobs");
        reject(jobs.values().isEmpty(), source, "at least one job is required");
        for (Map.Entry<String, Object> entry : jobs.values().entrySet()) {
            validateJob(source + ".jobs." + entry.getKey(), requireMap(entry.getValue(), entry.getKey()));
        }
        validateProducerOutputs(source, jobs);
    }

    private static void validateJob(String path, MapValue job) {
        reject(job.values().containsKey("continue-on-error"), path, "continue-on-error is forbidden");
        List<MapValue> steps = requireList(job.values().get("steps"), path + ".steps");
        Map<String, String> expectedPermissions = new LinkedHashMap<>();
        List<MapValue> checkouts = new ArrayList<>();
        int pagesDeploy = -1;

        for (int index = 0; index < steps.size(); index++) {
            MapValue step = steps.get(index);
            reject(step.values().containsKey("continue-on-error"), path, "continue-on-error is forbidden");
            String uses = optionalString(step.values().get("uses"), step.path() + ".uses");
            if (uses == null) {
                continue;
            }
            validateAction(step.path(), uses);
            if (uses.startsWith("actions/checkout@")) {
                expectedPermissions.put("contents", "read");
                checkouts.add(step);
            }
            if (uses.startsWith("actions/download-artifact@")) {
                validateArtifactDownload(path, steps, index);
            }
            if (uses.startsWith("actions/deploy-pages@")) {
                expectedPermissions.put("id-token", "write");
                expectedPermissions.put("pages", "write");
                pagesDeploy = index;
            }
        }

        if (containsKey(job.values(), "GH_TOKEN")) {
            expectedPermissions.put("actions", "read");
        }
        Map<String, String> actualPermissions =
                stringMap(requireMap(job.values().get("permissions"), path + ".permissions"));
        reject(
                !actualPermissions.equals(expectedPermissions),
                path,
                "permissions " + actualPermissions + " do not match required " + expectedPermissions);

        String environment = environment(job, path);
        boolean hasSecrets = strings(job.values()).stream()
                .anyMatch(value -> SECRET.matcher(value).find());
        boolean hasWrite = actualPermissions.containsValue("write");
        boolean privileged = hasSecrets || hasWrite || environment != null;
        reject(hasSecrets && environment == null, path, "secret-bearing job needs a protected environment");
        reject(hasWrite && environment == null, path, "write-capable job needs a protected environment");

        if (privileged) {
            MapValue defaults = requireMap(job.values().get("defaults"), path + ".defaults");
            MapValue runDefaults = requireMap(defaults.values().get("run"), defaults.path() + ".run");
            reject(
                    !"bash".equals(runDefaults.values().get("shell")),
                    path,
                    "privileged jobs must use the trusted bash shell");
            reject(
                    job.values().containsKey("container") || job.values().containsKey("services"),
                    path,
                    "privileged jobs must not use containers or services");
            reject(
                    !"ubuntu-24.04".equals(job.values().get("runs-on")),
                    path,
                    "privileged jobs must use the approved GitHub-hosted runner");
            reject(
                    checkouts.size() != 1 || !isTrustedCheckout(checkouts.get(0)),
                    path,
                    "privileged job must have exactly one trusted-control checkout");
            reject(
                    strings(job.values()).stream().anyMatch(value -> value.contains(".procwright-target")),
                    path,
                    "privileged job must not read or execute target checkout");
        }

        for (MapValue step : steps) {
            String run = optionalString(step.values().get("run"), step.path() + ".run");
            boolean trustedScript = run != null && run.contains(".procwright-trusted/scripts/");
            if (privileged || trustedScript) {
                reject(
                        step.values().containsKey("shell") || step.values().containsKey("if"),
                        step.path(),
                        "security-sensitive steps must not override shell or execution condition");
            }
        }

        validateTargetCheckouts(path, steps);
        validateArtifactBindings(path, job, privileged);
        validatePagesOrder(path, steps, pagesDeploy);
        validateCentralOrder(path, steps);
    }

    private static void validateAction(String path, String uses) {
        if (uses.startsWith("./")) {
            return;
        }
        if (uses.startsWith("docker://")) {
            reject(!CONTAINER.matcher(uses).matches(), path, "container must be pinned to a sha256 digest");
            return;
        }
        reject(!ACTION.matcher(uses).matches(), path, "action must be pinned to a full commit SHA");
    }

    private static void validateArtifactDownload(String path, List<MapValue> steps, int index) {
        MapValue step = steps.get(index);
        MapValue with = requireMap(step.values().get("with"), step.path() + ".with");
        String artifactId = requireString(with.values().get("artifact-ids"), with.path() + ".artifact-ids");
        Matcher id = ARTIFACT_ID.matcher(artifactId);
        reject(!id.matches(), path, "artifact download must bind an immutable producer artifact ID");
        reject(
                !"error".equals(with.values().get("digest-mismatch")),
                path,
                "artifact download must fail on digest mismatch");
        reject(index + 1 >= steps.size(), path, "artifact download must be followed by a verifier");
        MapValue verifierStep = steps.get(index + 1);
        String verifierRun = optionalString(verifierStep.values().get("run"), verifierStep.path() + ".run");
        reject(
                verifierRun == null
                        || trustedInvocationIndex(verifierRun, HANDOFF_VERIFY, HANDOFF_VERIFY_ARGUMENTS) != 0
                        || commandLines(verifierRun).size() != 1,
                path,
                "artifact download must be followed by the trusted handoff verifier");
        Bindings verifier = bindings(verifierStep.values());
        reject(
                !verifier.ids().contains(id.group(1)) || !verifier.digests().contains(id.group(1)),
                path,
                "artifact verifier must bind the downloaded producer ID and digest");
    }

    private static void validateTargetCheckouts(String path, List<MapValue> steps) {
        boolean hasTrustedControl = steps.stream().anyMatch(step -> {
            String uses = optionalString(step.values().get("uses"), step.path() + ".uses");
            if (uses == null
                    || !uses.startsWith("actions/checkout@")
                    || !step.values().containsKey("with")) {
                return false;
            }
            MapValue with = requireMap(step.values().get("with"), step.path() + ".with");
            return "${{ github.workflow_sha }}".equals(with.values().get("ref"));
        });
        for (int index = 0; index < steps.size(); index++) {
            MapValue step = steps.get(index);
            String uses = optionalString(step.values().get("uses"), step.path() + ".uses");
            if (uses == null || !uses.startsWith("actions/checkout@")) {
                continue;
            }
            if (!step.values().containsKey("with")) {
                reject(hasTrustedControl, path, "checkout beside trusted control must be a verified release target");
                continue;
            }
            MapValue with = requireMap(step.values().get("with"), step.path() + ".with");
            String ref = optionalString(with.values().get("ref"), with.path() + ".ref");
            if ("${{ github.workflow_sha }}".equals(ref)) {
                continue;
            }
            if (ref == null) {
                reject(hasTrustedControl, path, "checkout beside trusted control must declare the release target ref");
                continue;
            }
            reject(
                    !"${{ inputs.release-commit }}".equals(ref)
                            && !"${{ steps.identity.outputs.release_commit }}".equals(ref),
                    path,
                    "checkout ref must be trusted workflow control or a verified release target");
            reject(
                    !Boolean.FALSE.equals(with.values().get("persist-credentials")),
                    path,
                    "release target checkout must not persist credentials");
            reject(
                    !".procwright-target".equals(with.values().get("path")),
                    path,
                    "release target checkout must use the isolated target directory");
            boolean provenance = steps.subList(0, index).stream()
                    .map(candidate -> optionalString(candidate.values().get("run"), candidate.path() + ".run"))
                    .anyMatch(run -> run != null
                            && (isSingleExactCommand(
                                            run,
                                            "bash .procwright-trusted/scripts/release/verify_release_commit_provenance.sh")
                                    || isSingleExactCommand(
                                            run,
                                            "python3 .procwright-trusted/scripts/verify_docs_release_identity.py")));
            boolean verified = steps.subList(index + 1, steps.size()).stream()
                    .map(candidate -> optionalString(candidate.values().get("run"), candidate.path() + ".run"))
                    .anyMatch(run -> run != null
                            && isSingleExactCommand(
                                    run, "bash .procwright-trusted/scripts/release/verify_target_checkout.sh"));
            reject(!provenance || !verified, path, "target checkout must be provenance-checked before use");
        }
    }

    private static void validateArtifactBindings(String path, MapValue job, boolean privileged) {
        if (!privileged
                || !job.values().containsKey("needs")
                || strings(job.values()).stream().noneMatch(value -> value.toLowerCase(java.util.Locale.ROOT)
                        .contains("artifact"))) {
            return;
        }
        Bindings bindings = bindings(job.values());
        Set<String> common = new LinkedHashSet<>(bindings.ids());
        common.retainAll(bindings.digests());
        reject(common.isEmpty(), path, "artifact transfer must bind producer ID and digest");
    }

    private static void validateProducerOutputs(String source, MapValue jobs) {
        for (Map.Entry<String, Object> jobEntry : jobs.values().entrySet()) {
            MapValue job = requireMap(jobEntry.getValue(), source + ".jobs." + jobEntry.getKey());
            if (!job.values().containsKey("outputs")) {
                continue;
            }
            MapValue outputs = requireMap(job.values().get("outputs"), job.path() + ".outputs");
            List<MapValue> steps = requireList(job.values().get("steps"), job.path() + ".steps");
            Map<String, MapValue> stepsById = new LinkedHashMap<>();
            for (MapValue step : steps) {
                String id = optionalString(step.values().get("id"), step.path() + ".id");
                if (id != null) {
                    stepsById.put(id, step);
                }
            }
            for (Map.Entry<String, Object> output : outputs.values().entrySet()) {
                String normalized = output.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!normalized.contains("artifact") && !normalized.endsWith("sha256")) {
                    continue;
                }
                String expression = requireString(output.getValue(), outputs.path() + "." + output.getKey());
                Matcher reference = STEP_OUTPUT.matcher(expression);
                reject(!reference.matches(), job.path(), "security output must bind one producing step");
                MapValue producer = stepsById.get(reference.group(1));
                reject(producer == null, job.path(), "security output references an unknown producing step");
                if (producer == null) {
                    continue;
                }
                String uses = optionalString(producer.values().get("uses"), producer.path() + ".uses");
                String producedOutput = reference.group(2);
                if (normalized.contains("artifact") && normalized.endsWith("id")) {
                    reject(
                            uses == null
                                    || (!uses.startsWith("actions/upload-artifact@")
                                            && !uses.startsWith("actions/upload-pages-artifact@"))
                                    || (!"artifact-id".equals(producedOutput) && !"artifact_id".equals(producedOutput)),
                            job.path(),
                            "artifact ID output must come from a pinned upload step");
                } else if (normalized.contains("artifact") && normalized.contains("digest")) {
                    reject(
                            uses == null
                                    || !uses.startsWith("actions/upload-artifact@")
                                    || !"artifact-digest".equals(producedOutput),
                            job.path(),
                            "artifact digest output must come from its upload step");
                } else if (normalized.endsWith("sha256")) {
                    String run = optionalString(producer.values().get("run"), producer.path() + ".run");
                    reject(
                            run == null
                                    || trustedInvocationIndex(
                                                    run,
                                                    "python3 .procwright-trusted/scripts/verify_pages_artifact.py seal",
                                                    Set.of("--directory"))
                                            != 0
                                    || commandLines(run).size() != 1
                                    || !producedOutput.endsWith("sha256"),
                            job.path(),
                            "content digest output must come from the trusted seal step");
                }
            }
        }
    }

    private static void validatePagesOrder(String path, List<MapValue> steps, int deploy) {
        if (deploy < 0) {
            return;
        }
        for (int index = 0; index < deploy; index++) {
            MapValue step = steps.get(index);
            String run = optionalString(step.values().get("run"), step.path() + ".run");
            if (run != null && isSingleExactCommand(run, PAGES_VERIFY)) {
                Bindings bindings = bindings(step.values());
                Set<String> common = new LinkedHashSet<>(bindings.ids());
                common.retainAll(bindings.digests());
                reject(common.isEmpty(), path, "Pages verifier must bind producer ID and digest");
                return;
            }
        }
        throw failure(path, "Pages artifact must be verified before deployment");
    }

    private static void validateCentralOrder(String path, List<MapValue> steps) {
        for (MapValue step : steps) {
            String run = optionalString(step.values().get("run"), step.path() + ".run");
            if (run == null) {
                continue;
            }
            int verification = trustedInvocationIndex(run, SIGNING_VERIFY, SIGNING_VERIFY_ARGUMENTS);
            int staging = exactCommandIndex(run, CENTRAL_STAGE);
            if (staging >= 0) {
                reject(verification != 0 || staging != 1, path, "signed bundle must be verified before staging");
            }
        }
    }

    private static int trustedInvocationIndex(String run, String command, Set<String> requiredArguments) {
        List<String> lines = commandLines(run);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith(command + " ")
                    && hasExactOptions(line, requiredArguments)
                    && !hasShellControlOperator(line)) {
                return index;
            }
        }
        return -1;
    }

    private static int exactCommandIndex(String run, String command) {
        return commandLines(run).indexOf(command);
    }

    private static boolean isSingleExactCommand(String run, String command) {
        List<String> lines = commandLines(run);
        return lines.size() == 1 && lines.get(0).equals(command);
    }

    private static List<String> commandLines(String run) {
        return run.lines()
                .map(String::strip)
                .filter(Predicate.not(String::isEmpty))
                .toList();
    }

    private static boolean hasShellControlOperator(String command) {
        return command.contains(";")
                || command.contains("&")
                || command.contains("|")
                || command.contains("`")
                || command.contains("$(")
                || command.contains(">")
                || command.contains("<");
    }

    private static boolean hasExactOptions(String command, Set<String> requiredOptions) {
        List<String> options = Pattern.compile("\\s+")
                .splitAsStream(command)
                .filter(token -> token.startsWith("-"))
                .toList();
        return options.size() == requiredOptions.size() && Set.copyOf(options).equals(requiredOptions);
    }

    private static boolean isTrustedCheckout(MapValue step) {
        MapValue with = requireMap(step.values().get("with"), step.path() + ".with");
        return "${{ github.workflow_sha }}".equals(with.values().get("ref"))
                && Boolean.FALSE.equals(with.values().get("persist-credentials"))
                && ".procwright-trusted".equals(with.values().get("path"))
                && "scripts".equals(with.values().get("sparse-checkout"));
    }

    private static String environment(MapValue job, String path) {
        Object raw = job.values().get("environment");
        if (raw == null) {
            return null;
        }
        String value = raw instanceof String text
                ? text
                : requireString(
                        requireMap(raw, path + ".environment").values().get("name"), path + ".environment.name");
        reject(!PROTECTED_ENVIRONMENTS.contains(value), path, "unapproved protected environment " + value);
        return value;
    }

    private static Map<String, Object> load(String source, String yaml) {
        LoadSettings settings = LoadSettings.builder()
                .setLabel(source)
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(1_000_000)
                .build();
        Iterator<Object> documents = new Load(settings).loadAllFromString(yaml).iterator();
        reject(!documents.hasNext(), source, "workflow must contain one YAML document");
        Object loaded = documents.next();
        reject(documents.hasNext(), source, "workflow must not contain multiple YAML documents");
        return requireMap(loaded, source).values();
    }

    private static MapValue requireMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw failure(path, "must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw failure(path, "contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return new MapValue(path, result);
    }

    private static List<MapValue> requireList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw failure(path, "must be a sequence");
        }
        List<MapValue> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            result.add(requireMap(list.get(index), path + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(MapValue map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.values().entrySet()) {
            result.put(entry.getKey(), requireString(entry.getValue(), map.path() + "." + entry.getKey()));
        }
        return result;
    }

    private static boolean containsKey(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.containsKey(key) || map.values().stream().anyMatch(child -> containsKey(child, key));
        }
        return value instanceof List<?> list && list.stream().anyMatch(child -> containsKey(child, key));
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        collectStrings(value, result);
        return result;
    }

    private static void collectStrings(Object value, List<String> result) {
        if (value instanceof String text) {
            result.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> collectStrings(child, result));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectStrings(child, result));
        }
    }

    private static Bindings bindings(Object value) {
        Set<String> ids = producers(value, ARTIFACT_ID);
        Set<String> digests = producers(value, ARTIFACT_DIGEST);
        return new Bindings(ids, digests);
    }

    private static Set<String> producers(Object value, Pattern pattern) {
        Set<String> result = new LinkedHashSet<>();
        for (String text : strings(value)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return result;
    }

    private static String requireString(Object value, String path) {
        String result = optionalString(value, path);
        if (result == null) {
            throw failure(path, "must be a string");
        }
        return result;
    }

    private static String optionalString(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw failure(path, "must be a string");
        }
        return text;
    }

    private static void reject(boolean rejected, String path, String message) {
        if (rejected) {
            throw failure(path, message);
        }
    }

    private static IllegalArgumentException failure(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }

    private record MapValue(String path, Map<String, Object> values) {
        void isEmptyOrThrow(String message) {
            reject(!values.isEmpty(), path, message);
        }
    }

    private record Bindings(Set<String> ids, Set<String> digests) {}
}
