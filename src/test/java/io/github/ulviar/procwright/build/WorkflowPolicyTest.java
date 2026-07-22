/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

final class WorkflowPolicyTest {

    private static final Pattern PINNED_ACTION = Pattern.compile("[^@]+@[0-9a-f]{40}");
    private static final Map<String, String> READ_ONLY = Map.of("contents", "read");
    private static final Map<String, String> PAGES_DEPLOY = Map.of("pages", "write", "id-token", "write");

    @Test
    void workflowsUsePinnedActionsAndLeastPrivilege() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(".github", "workflows"))) {
            for (Path workflow :
                    files.filter(WorkflowPolicyTest::isWorkflow).sorted().toList()) {
                Map<String, Object> root = stringMap(load(workflow), workflow + " root");
                assertEquals(Map.of(), stringMap(root.get("permissions"), workflow + " root permissions"));
                assertNoPullRequestTarget(root.get("on"), workflow);

                Map<String, Object> jobs = stringMap(root.get("jobs"), workflow + " jobs");
                assertFalse(jobs.isEmpty(), workflow + " has no jobs");
                for (Map.Entry<String, Object> entry : jobs.entrySet()) {
                    Map<String, Object> job = stringMap(entry.getValue(), workflow + " job " + entry.getKey());
                    Map<String, Object> permissions =
                            stringMap(job.get("permissions"), workflow + " permissions for " + entry.getKey());
                    Map<String, String> expected = "deploy-docs".equals(entry.getKey()) ? PAGES_DEPLOY : READ_ONLY;
                    assertEquals(expected, permissions, workflow + " permissions for " + entry.getKey());
                    if ("deploy-docs".equals(entry.getKey())) {
                        assertEquals(List.of("verify", "source-variants", "docs"), job.get("needs"));
                        assertEquals(
                                "github.event_name != 'pull_request' && github.ref == 'refs/heads/main'",
                                job.get("if"));
                    }
                }
                assertSafeNodes(root, workflow.toString());
            }
        }
    }

    private static Object load(Path workflow) throws IOException {
        LoadSettings settings =
                LoadSettings.builder().setSchema(new CoreSchema()).build();
        return new Load(settings).loadFromString(Files.readString(workflow));
    }

    private static void assertNoPullRequestTarget(Object triggers, Path workflow) {
        if (triggers instanceof Map<?, ?> triggerMap) {
            assertFalse(triggerMap.containsKey("pull_request_target"), workflow.toString());
        } else if (triggers instanceof Iterable<?> iterable) {
            for (Object trigger : iterable) {
                assertFalse("pull_request_target".equals(trigger), workflow.toString());
            }
        } else {
            assertFalse("pull_request_target".equals(triggers), workflow.toString());
        }
    }

    private static void assertSafeNodes(Object node, String location) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                String childLocation = location + '.' + key;
                if ("uses".equals(key)) {
                    String action = assertInstanceOf(String.class, value, childLocation);
                    assertTrue(
                            action.startsWith("./")
                                    || PINNED_ACTION.matcher(action).matches(),
                            childLocation + " is not pinned to a commit SHA: " + action);
                }
                assertFalse("continue-on-error".equals(key), childLocation + " is forbidden");
                assertSafeNodes(value, childLocation);
            }
        } else if (node instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object value : iterable) {
                assertSafeNodes(value, location + '[' + index++ + ']');
            }
        }
    }

    private static Map<String, Object> stringMap(Object value, String location) {
        Map<?, ?> source = assertInstanceOf(Map.class, value, location);
        return source.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
    }

    private static boolean isWorkflow(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
