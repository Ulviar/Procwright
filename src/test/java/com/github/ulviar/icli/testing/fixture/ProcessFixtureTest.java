package com.github.ulviar.icli.testing.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ProcessFixtureTest {

    @Test
    void modelsSuccessfulSingleRunProcess() {
        ProcessFixture fixture = ProcessFixture.singleRun()
                .stdout("ready\n")
                .stderr("")
                .exitCode(0)
                .build();

        FixtureRunResult result = fixture.run(Duration.ofSeconds(1));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode().orElseThrow());
        assertEquals("ready\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void modelsStderrAndNonZeroExitCode() {
        ProcessFixture fixture = ProcessFixture.singleRun()
                .stdout("")
                .stderr("warning\n")
                .exitCode(7)
                .build();

        FixtureRunResult result = fixture.run(Duration.ofSeconds(1));

        assertFalse(result.timedOut());
        assertEquals(7, result.exitCode().orElseThrow());
        assertEquals("warning\n", result.stderr());
    }

    @Test
    void createsDeterministicLargeOutput() {
        ProcessFixture fixture =
                ProcessFixture.singleRun().stdoutRepeated('x', 4096).exitCode(0).build();

        FixtureRunResult result = fixture.run(Duration.ofSeconds(1));

        assertEquals(4096, result.stdout().length());
        assertTrue(result.stdout().chars().allMatch(character -> character == 'x'));
    }

    @Test
    void modelsTimeoutWithoutExitCode() {
        ProcessFixture fixture =
                ProcessFixture.singleRun().stdout("partial").hangs().build();

        FixtureRunResult result = fixture.run(Duration.ofMillis(10));

        assertTrue(result.timedOut());
        assertTrue(result.exitCode().isEmpty());
        assertEquals("partial", result.stdout());
    }
}
