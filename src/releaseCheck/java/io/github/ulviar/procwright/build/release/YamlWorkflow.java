/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.CoreSchema;

final class YamlWorkflow {
    private static final int MAX_CODE_POINTS = 1_000_000;
    private static final int MAX_ALIASES = 0;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 100_000;

    private final Path source;
    private final Map<String, Object> root;

    private YamlWorkflow(Path source, Map<String, Object> root) {
        this.source = source;
        this.root = root;
    }

    static YamlWorkflow load(Path source) {
        String yaml;
        try {
            yaml = Files.readString(source);
        } catch (IOException error) {
            throw new WorkflowValidationException("Cannot read workflow " + source, error);
        }

        LoadSettings settings = LoadSettings.builder()
                .setLabel(source.toString())
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(MAX_ALIASES)
                .setCodePointLimit(MAX_CODE_POINTS)
                .setParseComments(false)
                .build();
        try {
            var documents = new Load(settings).loadAllFromString(yaml).iterator();
            if (!documents.hasNext()) {
                throw new WorkflowValidationException(source + " must contain one YAML document");
            }
            NodeCounter counter = new NodeCounter();
            Object normalized = normalize(documents.next(), "$", 0, counter);
            if (documents.hasNext()) {
                throw new WorkflowValidationException(source + " must not contain multiple YAML documents");
            }
            if (!(normalized instanceof Map<?, ?> rawRoot)) {
                throw new WorkflowValidationException(source + " YAML document root must be a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) rawRoot;
            return new YamlWorkflow(source, root);
        } catch (WorkflowValidationException error) {
            throw error;
        } catch (YamlEngineException error) {
            throw new WorkflowValidationException("Invalid YAML in " + source + ": " + error.getMessage(), error);
        }
    }

    Path source() {
        return source;
    }

    Map<String, Object> root() {
        return root;
    }

    Map<String, Object> requiredMap(Map<String, Object> parent, String key, String path) {
        return map(required(parent, key, path), path + "." + key);
    }

    List<Object> requiredList(Map<String, Object> parent, String key, String path) {
        return list(required(parent, key, path), path + "." + key);
    }

    String requiredString(Map<String, Object> parent, String key, String path) {
        return string(required(parent, key, path), path + "." + key);
    }

    Object required(Map<String, Object> parent, String key, String path) {
        if (!parent.containsKey(key)) {
            throw failure(path + " is missing required key " + key);
        }
        return parent.get(key);
    }

    Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw failure(path + " must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    List<Object> list(Object value, String path) {
        if (!(value instanceof List<?> raw)) {
            throw failure(path + " must be a sequence");
        }
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) raw;
        return result;
    }

    String string(Object value, String path) {
        if (!(value instanceof String text)) {
            throw failure(path + " must be a string scalar");
        }
        return text;
    }

    WorkflowValidationException failure(String message) {
        return new WorkflowValidationException(source + ": " + message);
    }

    private static Object normalize(Object value, String path, int depth, NodeCounter counter) {
        if (depth > MAX_DEPTH) {
            throw new WorkflowValidationException(path + " exceeds the YAML nesting limit");
        }
        counter.increment(path);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new WorkflowValidationException(path + " contains a non-string mapping key");
                }
                normalized.put(key, normalize(entry.getValue(), path + "." + key, depth + 1, counter));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                normalized.add(normalize(list.get(index), path + "[" + index + "]", depth + 1, counter));
            }
            return normalized;
        }
        return value;
    }

    private static final class NodeCounter {
        private int count;

        void increment(String path) {
            count++;
            if (count > MAX_NODES) {
                throw new WorkflowValidationException(path + " exceeds the YAML node limit");
            }
        }
    }
}
