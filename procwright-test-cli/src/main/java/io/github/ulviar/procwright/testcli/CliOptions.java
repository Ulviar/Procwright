package io.github.ulviar.procwright.testcli;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record CliOptions(String scenario, Map<String, List<String>> options, List<String> positionals) {

    private static final Set<String> BOOLEAN_FLAGS = Set.of(
            "flush", "newline", "stdout-first", "wait", "fail-fast", "started", "finished", "malformed-response");

    CliOptions {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(positionals, "positionals");
        if (scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        options.forEach((key, values) -> copied.put(validateName(key), List.copyOf(values)));
        options = Map.copyOf(copied);
        positionals = List.copyOf(positionals);
    }

    static CliOptions parse(String[] args) {
        Objects.requireNonNull(args, "args");
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        String scenario = null;
        boolean raw = false;

        for (int index = 0; index < args.length; index++) {
            String arg = Objects.requireNonNull(args[index], "args[" + index + "]");
            if (raw) {
                positionals.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                raw = true;
                continue;
            }
            if (arg.startsWith("--")) {
                ParsedOption option = parseOption(args, index);
                index = option.nextIndex();
                if ("scenario".equals(option.name())) {
                    scenario = option.value();
                } else {
                    values.computeIfAbsent(option.name(), ignored -> new ArrayList<>())
                            .add(option.value());
                }
                continue;
            }
            if (scenario == null) {
                scenario = arg;
            } else {
                positionals.add(arg);
            }
        }

        return new CliOptions(scenario == null ? "catalog" : scenario, values, positionals);
    }

    List<String> values(String name) {
        return options.getOrDefault(validateName(name), List.of());
    }

    String string(String name, String defaultValue) {
        List<String> values = values(name);
        return values.isEmpty() ? defaultValue : values.get(values.size() - 1);
    }

    int integer(String name, int defaultValue) {
        long value = longValue(name, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside integer range: " + value);
        }
        return (int) value;
    }

    long longValue(String name, long defaultValue) {
        String value = string(name, Long.toString(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number: " + value, exception);
        }
    }

    boolean bool(String name, boolean defaultValue) {
        List<String> values = values(name);
        if (values.isEmpty()) {
            return defaultValue;
        }
        String raw = values.get(values.size() - 1).toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(name + " must be boolean: " + values.get(values.size() - 1));
        };
    }

    int byteSize(String name, int defaultValue) {
        String value = string(name, Integer.toString(defaultValue)).trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (value.endsWith("k")) {
            multiplier = 1024;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 1024 * 1024;
            value = value.substring(0, value.length() - 1);
        }
        try {
            long parsed = Math.multiplyExact(Long.parseLong(value), multiplier);
            if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(name + " is outside supported byte-size range: " + parsed);
            }
            return (int) parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be a non-negative byte size: " + string(name, ""), exception);
        }
    }

    Charset charset() {
        String value = string("charset", StandardCharsets.UTF_8.name());
        try {
            return Charset.forName(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("charset is not supported: " + value, exception);
        }
    }

    private static ParsedOption parseOption(String[] args, int index) {
        String raw = args[index];
        String body = raw.substring(2);
        if (body.isEmpty()) {
            throw new IllegalArgumentException("option name must not be blank");
        }

        int equals = body.indexOf('=');
        if (equals >= 0) {
            String name = body.substring(0, equals);
            String value = body.substring(equals + 1);
            return new ParsedOption(validateName(name), value, index);
        }

        if (body.startsWith("no-")) {
            String name = validateName(body.substring(3));
            if (!BOOLEAN_FLAGS.contains(name)) {
                throw new IllegalArgumentException("--no-" + name + " is only valid for known boolean options");
            }
            return new ParsedOption(name, "false", index);
        }

        String name = validateName(body);
        if (BOOLEAN_FLAGS.contains(name)) {
            return new ParsedOption(name, "true", index);
        }
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("--" + name + " requires a value");
        }
        return new ParsedOption(name, args[index + 1], index + 1);
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("invalid option name: " + name);
        }
        return name;
    }

    private record ParsedOption(String name, String value, int nextIndex) {}
}
