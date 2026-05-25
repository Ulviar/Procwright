package io.github.ulviar.icli.testcli;

import java.io.IOException;
import java.util.Arrays;

final class LoadScenarios {

    private static final int MAX_MEMORY_BYTES = 64 * 1024 * 1024;

    private LoadScenarios() {}

    static int mixedLoad(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        byte[] retained = allocate(options.byteSize("memory-bytes", 0));
        int ticks = options.integer("ticks", 3);
        long cpuMillis = options.longValue("cpu-millis", 10);
        long intervalMillis = options.longValue("interval-millis", 10);
        int stderrEvery = options.integer("stderr-every", 0);
        for (int index = 0; index < ticks; index++) {
            long operations = burnCpu(cpuMillis);
            context.stdoutLine("load:" + index + ":ops:" + operations + ":memory:" + retained.length);
            if (stderrEvery > 0 && index % stderrEvery == 0) {
                context.stderrLine("load-err:" + index);
            }
            context.sleepMillis(intervalMillis);
        }
        return options.integer("exit-code", 0);
    }

    private static byte[] allocate(int memoryBytes) throws IOException {
        if (memoryBytes < 0 || memoryBytes > MAX_MEMORY_BYTES) {
            throw new IOException("memory-bytes must be between 0 and " + MAX_MEMORY_BYTES);
        }
        byte[] retained = new byte[memoryBytes];
        Arrays.fill(retained, (byte) 0x5a);
        return retained;
    }

    private static long burnCpu(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("cpu-millis must not be negative");
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        long operations = 0;
        while (System.nanoTime() < deadline) {
            operations = operations * 31 + 17;
            operations ^= operations >>> 7;
        }
        return operations;
    }
}
