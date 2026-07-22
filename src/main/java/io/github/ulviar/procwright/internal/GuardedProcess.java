/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Deadline-bounded boundary around a process supplied by the public PTY provider SPI. */
final class GuardedProcess extends Process {

    private final Process delegate;
    private final ProcessTreeScanner scanner;

    GuardedProcess(Process delegate, ProcessTreeScanner scanner) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    Process delegate() {
        return delegate;
    }

    Duration providerOperationTimeout() {
        return scanner.providerOperationTimeout();
    }

    @Override
    public OutputStream getOutputStream() {
        return required("procwright-provider-stdin-", delegate::getOutputStream);
    }

    @Override
    public InputStream getInputStream() {
        return required("procwright-provider-stdout-", delegate::getInputStream);
    }

    @Override
    public InputStream getErrorStream() {
        return required("procwright-provider-stderr-", delegate::getErrorStream);
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!waitFor(100, TimeUnit.MILLISECONDS)) {
            // The bounded operation owner makes each provider call interruptible from this lifecycle thread.
        }
        return exitValue();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long timeoutNanos = unit.toNanos(timeout);
        if (timeoutNanos <= 0) {
            return false;
        }
        return waitForWithin(Duration.ofNanos(timeoutNanos));
    }

    @Override
    public int exitValue() {
        return required("procwright-provider-exit-", delegate::exitValue);
    }

    @Override
    public void destroy() {
        required("procwright-provider-destroy-", () -> {
            delegate.destroy();
            return null;
        });
    }

    @Override
    public Process destroyForcibly() {
        required("procwright-provider-force-destroy-", () -> {
            delegate.destroyForcibly();
            return null;
        });
        return this;
    }

    @Override
    public boolean isAlive() {
        return required("procwright-provider-liveness-", delegate::isAlive);
    }

    @Override
    public long pid() {
        return required("procwright-provider-pid-", delegate::pid);
    }

    @Override
    public ProcessHandle toHandle() {
        return required("procwright-provider-handle-", () -> scanner.guardObserved(delegate.toHandle()));
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return scanner.descendants(this).stream();
    }

    @Override
    public boolean supportsNormalTermination() {
        return required("procwright-provider-termination-support-", delegate::supportsNormalTermination);
    }

    private <T> T required(String operation, java.util.concurrent.Callable<T> action) {
        try {
            return scanner.required(operation, scanner.providerOperationTimeout(), action);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new io.github.ulviar.procwright.command.CommandExecutionException(
                    io.github.ulviar.procwright.command.CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Interrupted while invoking provider process",
                    interruption);
        }
    }

    boolean waitForWithin(Duration requested) throws InterruptedException {
        Duration budget = operationBudget(requested);
        return scanner.required(
                "procwright-provider-wait-",
                budget,
                () -> delegate.waitFor(DurationSupport.saturatedNanos(budget), TimeUnit.NANOSECONDS));
    }

    boolean isAliveWithin(Duration requested) throws InterruptedException {
        return scanner.required("procwright-provider-liveness-", operationBudget(requested), delegate::isAlive);
    }

    ProcessHandle toHandleWithin(Duration requested) throws InterruptedException {
        return scanner.required(
                "procwright-provider-handle-",
                operationBudget(requested),
                () -> scanner.guardObserved(delegate.toHandle()));
    }

    void destroyWithin(Duration requested, boolean forceful) throws InterruptedException {
        Duration budget = operationBudget(requested);
        scanner.required(
                forceful ? "procwright-provider-force-destroy-" : "procwright-provider-destroy-", budget, () -> {
                    if (forceful) {
                        delegate.destroyForcibly();
                    } else {
                        delegate.destroy();
                    }
                    return null;
                });
    }

    OutputStream outputStreamWithin(Duration requested) throws InterruptedException {
        return scanner.required("procwright-provider-stdin-", operationBudget(requested), delegate::getOutputStream);
    }

    int exitValueWithin(Duration requested) throws InterruptedException {
        return scanner.required("procwright-provider-exit-", operationBudget(requested), delegate::exitValue);
    }

    private Duration operationBudget(Duration requested) {
        Objects.requireNonNull(requested, "requested");
        if (requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("provider operation budget must be positive");
        }
        return requested.compareTo(scanner.providerOperationTimeout()) < 0
                ? requested
                : scanner.providerOperationTimeout();
    }
}
