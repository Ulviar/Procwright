/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.ProcessIoResources;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Classifies output close failures and publishes one outcome after both streams physically close. */
final class SessionOutputCleanup {

    private final Object lock = new Object();
    private final CompletableFuture<Throwable> completion = new CompletableFuture<>();
    private final CompletableFuture<Void> physicalCompletion = new CompletableFuture<>();
    private Throwable inlineFailure;
    private boolean bound;
    private boolean settled;

    void bind(ProcessIoResources.Resource<InputStream> stdout, ProcessIoResources.Resource<InputStream> stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        synchronized (lock) {
            if (bound) {
                throw new IllegalStateException("Session output cleanup is already bound");
            }
            bound = true;
        }
        CompletableFuture.allOf(stdout.closeCompletion(), stderr.closeCompletion())
                .whenComplete((ignored, impossible) -> settle(stdout.closeResult(), stderr.closeResult()));
    }

    void inlineFailed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (lock) {
            if (settled) {
                throw new IllegalStateException("Inline output failure arrived after physical cleanup");
            }
            inlineFailure = SuppressionSupport.combine(inlineFailure, failure);
        }
    }

    CompletableFuture<Throwable> completion() {
        return completion;
    }

    CompletableFuture<Void> physicalView() {
        return physicalCompletion.copy();
    }

    void afterSettlement(Runnable publication) {
        Objects.requireNonNull(publication, "publication");
        completion.whenComplete((ignored, impossible) -> {
            try {
                publication.run();
            } catch (Throwable failure) {
                BoundedFailureReporter.shared().report(BoundedFailureReporter.captureFailureTarget(), failure);
            }
        });
    }

    private void settle(Throwable stdoutFailure, Throwable stderrFailure) {
        Throwable physicalFailure = prioritize(stdoutFailure, stderrFailure);
        Throwable terminalFailure;
        synchronized (lock) {
            if (settled) {
                throw new IllegalStateException("Session output cleanup was already settled");
            }
            settled = true;
            terminalFailure = inlineFailure;
        }
        if (physicalFailure == null) {
            physicalCompletion.complete(null);
        } else {
            physicalCompletion.completeExceptionally(physicalFailure);
        }
        completion.complete(terminalFailure);
    }

    private static Throwable prioritize(Throwable stdoutFailure, Throwable stderrFailure) {
        if (stdoutFailure == null) {
            return stderrFailure;
        }
        if (stderrFailure == null) {
            return stdoutFailure;
        }
        Throwable primary = priority(stderrFailure) > priority(stdoutFailure) ? stderrFailure : stdoutFailure;
        Throwable secondary = primary == stdoutFailure ? stderrFailure : stdoutFailure;
        SuppressionSupport.attach(primary, secondary);
        return primary;
    }

    private static int priority(Throwable failure) {
        if (failure instanceof Error) {
            return 2;
        }
        return failure instanceof RuntimeException ? 1 : 0;
    }
}
