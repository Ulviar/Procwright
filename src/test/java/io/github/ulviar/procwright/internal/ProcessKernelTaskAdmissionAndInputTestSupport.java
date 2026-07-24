/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

abstract class ProcessKernelTaskAdmissionAndInputTestSupport extends ProcessKernelProcessFixtureSupport {

    static final class ReadErrorInputStream extends TrackingInputStream {

        final AssertionError failure;

        ReadErrorInputStream(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            throw failure;
        }
    }

    static final class FailingWriteOutputStream extends TrackingOutputStream {

        final AssertionError failure;
        final AssertionError closeFailure;

        FailingWriteOutputStream(AssertionError failure) {
            this(failure, null);
        }

        FailingWriteOutputStream(AssertionError failure, AssertionError closeFailure) {
            this.failure = failure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void write(int value) {
            throw failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            throw failure;
        }

        @Override
        public void close() {
            super.close();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    static final class ImmediateFailingOutputStream extends TrackingOutputStream {

        final Throwable failure;

        ImmediateFailingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }
    }
}
