package com.github.ulviar.icli;

import java.util.List;
import java.util.Locale;

final class SystemShell {

    private SystemShell() {}

    static List<String> command(String commandLine) {
        if (isWindows()) {
            return List.of("cmd.exe", "/d", "/s", "/c", commandLine);
        }
        return List.of("/bin/sh", "-c", commandLine);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
