package io.github.ulviar.procwright.internal;

import java.util.List;
import java.util.Locale;

public final class SystemShell {

    private SystemShell() {}

    public static List<String> command(String commandLine) {
        if (isWindows()) {
            return List.of("cmd.exe", "/d", "/s", "/c", commandLine);
        }
        return List.of("/bin/sh", "-c", commandLine);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
