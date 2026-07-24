/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Owns publication of the public exit view after every registered cleanup dependency has settled. */
final class SessionExitBarrier implements Runnable {

    private final Object lock = new Object();
    private final BoundedLifecyclePublisher.Permit publication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private int helpers;
    private boolean processCompleted;
    private boolean physicalOutputCompleted;
    private boolean publicationClaimed;
    private SessionExit processResult;
    private Throwable processFailure;
    private Throwable outputFailure;
    private SessionExit publicationResult;
    private Throwable publicationFailure;

    SessionExitBarrier(BoundedLifecyclePublisher.Permit publication) {
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    void observe(CompletableFuture<SessionExit> processTerminal, CompletableFuture<Throwable> outputCleanup) {
        Objects.requireNonNull(processTerminal, "processTerminal").whenComplete(this::processCompleted);
        Objects.requireNonNull(outputCleanup, "outputCleanup").whenComplete(this::outputCompleted);
    }

    CompletableFuture<SessionExit> view() {
        return exit.copy();
    }

    Registration registerHelper() {
        Registration registration = new Registration(this);
        synchronized (lock) {
            if (publicationClaimed) {
                throw new IllegalStateException("Session cleanup publication has already been claimed");
            }
            helpers++;
        }
        return registration;
    }

    @Override
    public void run() {
        if (publicationFailure == null) {
            exit.complete(publicationResult);
        } else {
            exit.completeExceptionally(publicationFailure);
        }
    }

    private void processCompleted(SessionExit result, Throwable failure) {
        boolean publish;
        synchronized (lock) {
            if (processCompleted) {
                throw new IllegalStateException("Process terminal cleanup was already recorded");
            }
            processCompleted = true;
            processResult = result;
            processFailure = failure;
            publish = claimPublicationLocked();
        }
        publishIfReady(publish);
    }

    private void outputCompleted(Throwable terminalFailure, Throwable impossibleFailure) {
        boolean publish;
        synchronized (lock) {
            if (physicalOutputCompleted) {
                throw new IllegalStateException("Physical output cleanup was already recorded");
            }
            physicalOutputCompleted = true;
            outputFailure = impossibleFailure == null ? terminalFailure : impossibleFailure;
            publish = claimPublicationLocked();
        }
        publishIfReady(publish);
    }

    private void helperCompleted() {
        boolean publish;
        synchronized (lock) {
            if (helpers <= 0) {
                throw new IllegalStateException("Helper cleanup barrier accounting underflow");
            }
            helpers--;
            publish = claimPublicationLocked();
        }
        publishIfReady(publish);
    }

    private boolean claimPublicationLocked() {
        if (publicationClaimed || !processCompleted || !physicalOutputCompleted || helpers != 0) {
            return false;
        }
        publicationClaimed = true;
        publicationResult = processResult;
        publicationFailure = SuppressionSupport.combine(processFailure, outputFailure);
        return true;
    }

    private void publishIfReady(boolean publish) {
        if (publish) {
            publication.publish(this);
        }
    }

    static final class Registration {

        private final SessionExitBarrier owner;
        private boolean settled;

        private Registration(SessionExitBarrier owner) {
            this.owner = owner;
        }

        void complete() {
            synchronized (this) {
                if (settled) {
                    return;
                }
                settled = true;
            }
            owner.helperCompleted();
        }

        void rollback() {
            complete();
        }
    }
}
