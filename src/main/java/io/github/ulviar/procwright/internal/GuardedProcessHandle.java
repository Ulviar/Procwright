/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Bounded view of a process handle returned by a provider-controlled process. */
final class GuardedProcessHandle implements ProcessHandle {

    private final ProcessHandle delegate;
    private final ProcessTreeScanner scanner;
    private final ProcessTreeScanner.HandleIdentity identity;

    GuardedProcessHandle(
            ProcessHandle delegate, ProcessTreeScanner scanner, ProcessTreeScanner.HandleIdentity identity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    ProcessHandle delegate() {
        return delegate;
    }

    Duration providerOperationTimeout() {
        return scanner.providerOperationTimeout();
    }

    @Override
    public long pid() {
        return required("procwright-provider-handle-pid-", delegate::pid);
    }

    @Override
    public Optional<ProcessHandle> parent() {
        return required(
                "procwright-provider-handle-parent-", () -> delegate.parent().map(scanner::guardObserved));
    }

    @Override
    public Stream<ProcessHandle> children() {
        return scanner.childrenOfHandle(this).stream();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return scanner.descendantsOfHandles(java.util.List.of(this)).stream();
    }

    @Override
    public Info info() {
        return required("procwright-provider-handle-info-", delegate::info);
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
        CompletableFuture<ProcessHandle> terminal = required("procwright-provider-handle-exit-", delegate::onExit);
        return terminal.thenApply(handle -> new GuardedProcessHandle(handle, scanner, identity));
    }

    @Override
    public boolean supportsNormalTermination() {
        return required("procwright-provider-handle-termination-support-", delegate::supportsNormalTermination);
    }

    @Override
    public boolean destroy() {
        return required("procwright-provider-handle-destroy-", delegate::destroy);
    }

    @Override
    public boolean destroyForcibly() {
        return required("procwright-provider-handle-force-destroy-", delegate::destroyForcibly);
    }

    @Override
    public boolean isAlive() {
        return required("procwright-provider-handle-liveness-", delegate::isAlive);
    }

    @Override
    public int compareTo(ProcessHandle other) {
        return Long.compare(pid(), other.pid());
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof GuardedProcessHandle guarded && identity.equals(guarded.identity);
    }

    @Override
    public int hashCode() {
        return identity.hashCode();
    }

    boolean isAliveWithin(Duration requested) throws InterruptedException {
        return scanner.required("procwright-provider-handle-liveness-", operationBudget(requested), delegate::isAlive);
    }

    boolean destroyWithin(Duration requested, boolean forceful) throws InterruptedException {
        return scanner.required(
                forceful ? "procwright-provider-handle-force-destroy-" : "procwright-provider-handle-destroy-",
                operationBudget(requested),
                forceful ? delegate::destroyForcibly : delegate::destroy);
    }

    private <T> T required(String operation, java.util.concurrent.Callable<T> action) {
        try {
            return scanner.required(operation, scanner.providerOperationTimeout(), action);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new io.github.ulviar.procwright.command.CommandExecutionException(
                    io.github.ulviar.procwright.command.CommandExecutionException.Reason.RUNTIME_FAILURE,
                    "Interrupted while invoking provider process handle",
                    interruption);
        }
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
