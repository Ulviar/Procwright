/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/**
 * Settles a failure-reporting producer only after both the caller and provider worker have
 * reached a terminal state.
 */
final class ProcessProviderOperationSettlement {

    private final LateTaskFailureReporter lateFailure;
    private final BoundedFailureReporter.ProducerRegistration producer;
    private boolean callerSettled;
    private boolean workerSettled;
    private boolean producerSettled;

    ProcessProviderOperationSettlement(
            LateTaskFailureReporter lateFailure, BoundedFailureReporter.ProducerRegistration producer) {
        this.lateFailure = Objects.requireNonNull(lateFailure, "lateFailure");
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    void bind(Thread thread) {
        lateFailure.bind(thread);
    }

    void resultObserved() {
        settleCaller();
    }

    void abandon() {
        try {
            lateFailure.abandon();
        } finally {
            settleCaller();
        }
    }

    void workerCompleted(Throwable failure) {
        try {
            if (failure != null && !(failure instanceof InterruptedException)) {
                lateFailure.record(failure);
            }
        } finally {
            settleWorker();
        }
    }

    private void settleCaller() {
        BoundedFailureReporter.ProducerRegistration settledProducer;
        synchronized (this) {
            callerSettled = true;
            settledProducer = claimSettledProducer();
        }
        complete(settledProducer);
    }

    private void settleWorker() {
        BoundedFailureReporter.ProducerRegistration settledProducer;
        synchronized (this) {
            workerSettled = true;
            settledProducer = claimSettledProducer();
        }
        complete(settledProducer);
    }

    private BoundedFailureReporter.ProducerRegistration claimSettledProducer() {
        if (!callerSettled || !workerSettled || producerSettled) {
            return null;
        }
        producerSettled = true;
        return producer;
    }

    private static void complete(BoundedFailureReporter.ProducerRegistration producer) {
        if (producer != null) {
            producer.complete();
        }
    }
}
