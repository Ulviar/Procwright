package com.github.ulviar.icli.testcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CliOptionsTest {

    @Test
    void parsesScenarioFlagAndTypedOptions() {
        CliOptions options = CliOptions.parse(new String[] {
            "--scenario=stream",
            "--count",
            "3",
            "--flush",
            "--no-newline",
            "--stdout-bytes=2k",
            "--tag",
            "first",
            "--tag=second",
            "--",
            "raw value"
        });

        assertEquals("stream", options.scenario());
        assertEquals(3, options.integer("count", 1));
        assertTrue(options.bool("flush", false));
        assertFalse(options.bool("newline", true));
        assertEquals(2048, options.byteSize("stdout-bytes", 0));
        assertEquals("second", options.string("tag", "missing"));
        assertEquals(2, options.values("tag").size());
        assertEquals("raw value", options.positionals().getFirst());
    }

    @Test
    void acceptsScenarioAsFirstPositionalForErgonomicManualRuns() {
        CliOptions options = CliOptions.parse(new String[] {"line-repl", "--prompt=> "});

        assertEquals("line-repl", options.scenario());
        assertEquals("> ", options.string("prompt", ""));
        assertTrue(options.positionals().isEmpty());
    }

    @Test
    void rejectsMalformedOptionsBeforeScenarioExecution() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"--=bad"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"--count"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"--count=-1"})
                .byteSize("count", 0));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"--flag=maybe"})
                .bool("flag", false));
    }

    @Test
    void missingFlagsUseDefaults() {
        CliOptions options = CliOptions.parse(new String[] {"exit"});

        assertFalse(options.bool("verbose", false));
        assertEquals(11L, options.longValue("delay-millis", 11L));
        assertEquals("fallback", options.string("missing", "fallback"));
    }
}
