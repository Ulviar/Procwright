/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkflowModel {
    private final YamlWorkflow yaml;

    WorkflowModel(YamlWorkflow yaml) {
        this.yaml = yaml;
    }

    YamlWorkflow yaml() {
        return yaml;
    }

    Map<String, Object> root() {
        return yaml.root();
    }

    Map<String, Object> triggers() {
        return yaml.requiredMap(root(), "on", "$");
    }

    Map<String, Object> jobs() {
        return yaml.requiredMap(root(), "jobs", "$");
    }

    Job job(String name) {
        return new Job(yaml, name, yaml.requiredMap(jobs(), name, "$.jobs"));
    }

    record Job(YamlWorkflow yaml, String name, Map<String, Object> values) {
        String path() {
            return "$.jobs." + name;
        }

        List<Step> steps() {
            List<Object> rawSteps = yaml.requiredList(values, "steps", path());
            List<Step> steps = new ArrayList<>(rawSteps.size());
            for (int index = 0; index < rawSteps.size(); index++) {
                String stepPath = path() + ".steps[" + index + "]";
                Map<String, Object> step = yaml.map(rawSteps.get(index), stepPath);
                steps.add(new Step(yaml, stepPath, step));
            }
            return List.copyOf(steps);
        }

        Map<String, Object> permissions() {
            return yaml.requiredMap(values, "permissions", path());
        }

        String requiredString(String key) {
            return yaml.requiredString(values, key, path());
        }
    }

    record Step(YamlWorkflow yaml, String path, Map<String, Object> values) {
        String name() {
            return yaml.requiredString(values, "name", path);
        }

        Optional<String> run() {
            if (!values.containsKey("run")) {
                return Optional.empty();
            }
            return Optional.of(yaml.string(values.get("run"), path + ".run"));
        }

        Optional<String> uses() {
            if (!values.containsKey("uses")) {
                return Optional.empty();
            }
            return Optional.of(yaml.string(values.get("uses"), path + ".uses"));
        }

        Optional<String> condition() {
            if (!values.containsKey("if")) {
                return Optional.empty();
            }
            return Optional.of(yaml.string(values.get("if"), path + ".if"));
        }

        Map<String, Object> requiredMap(String key) {
            return yaml.requiredMap(values, key, path);
        }
    }
}
