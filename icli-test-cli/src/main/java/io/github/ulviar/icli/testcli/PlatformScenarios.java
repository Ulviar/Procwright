package io.github.ulviar.icli.testcli;

import java.io.IOException;
import java.util.HexFormat;

final class PlatformScenarios {

    private PlatformScenarios() {}

    static int platformNewlines(ScenarioContext context) throws IOException {
        CliOptions options = context.options();
        String newline = newline(options.string("style", "system"));
        int stdoutLines = options.integer("stdout-lines", 2);
        int stderrLines = options.integer("stderr-lines", 0);
        for (int index = 0; index < stdoutLines; index++) {
            context.stdoutText("out:" + index + newline);
        }
        for (int index = 0; index < stderrLines; index++) {
            context.stderrText("err:" + index + newline);
        }
        return options.integer("exit-code", 0);
    }

    static int platformProbe(ScenarioContext context) throws IOException {
        String newline = System.lineSeparator();
        context.stdoutLine("os.name:" + System.getProperty("os.name"));
        context.stdoutLine("file.separator:" + System.getProperty("file.separator"));
        context.stdoutLine("path.separator:" + System.getProperty("path.separator"));
        context.stdoutLine("line.separator.hex:" + HexFormat.of().formatHex(newline.getBytes(context.charset())));
        return context.options().integer("exit-code", 0);
    }

    private static String newline(String style) {
        return switch (style) {
            case "lf" -> "\n";
            case "crlf" -> "\r\n";
            case "cr" -> "\r";
            case "system" -> System.lineSeparator();
            default -> throw new IllegalArgumentException("unknown newline style: " + style);
        };
    }
}
