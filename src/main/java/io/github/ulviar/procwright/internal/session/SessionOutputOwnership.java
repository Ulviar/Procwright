/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.CommandValidation;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Selects one output consumer and transfers close responsibility together with an internal helper claim. */
final class SessionOutputOwnership {

    private static final String PUBLIC_OUTPUT_OWNER = "public output streams";

    private final Object lock = new Object();
    private String owner;
    private CloseResponsibility closeResponsibility = CloseResponsibility.LIFECYCLE;
    private boolean lifecycleCloseClaimed;

    InputStream publicStream(InputStream stream) {
        return new OutputGuardInputStream(stream, this);
    }

    void claim(String requestedOwner) {
        CommandValidation.requireText(requestedOwner, "owner");
        synchronized (lock) {
            if (lifecycleCloseClaimed) {
                throw new IllegalStateException("Session output is closed by the session lifecycle");
            }
            if (owner != null) {
                throw new IllegalStateException("Session output is already owned by " + owner);
            }
            owner = requestedOwner;
            closeResponsibility = CloseResponsibility.OUTPUT_OWNER;
        }
    }

    void ensureOwnedBy(String requestedOwner) {
        CommandValidation.requireText(requestedOwner, "owner");
        synchronized (lock) {
            if (requestedOwner.equals(owner)) {
                return;
            }
            if (owner == null) {
                throw new IllegalStateException("Session output is not owned by " + requestedOwner);
            }
            throw new IllegalStateException("Session output is already owned by " + owner);
        }
    }

    boolean claimLifecycleClose() {
        synchronized (lock) {
            if (lifecycleCloseClaimed || closeResponsibility == CloseResponsibility.OUTPUT_OWNER) {
                return false;
            }
            lifecycleCloseClaimed = true;
            return true;
        }
    }

    private void beginPublicOperation() {
        synchronized (lock) {
            if (lifecycleCloseClaimed) {
                throw new IllegalStateException("Session output is closed by the session lifecycle");
            }
            if (owner == null) {
                owner = PUBLIC_OUTPUT_OWNER;
            } else if (!PUBLIC_OUTPUT_OWNER.equals(owner)) {
                throw new IllegalStateException("Session output is owned by " + owner);
            }
        }
    }

    private void closePublicStream(OutputCloseOperation closeOperation) throws IOException {
        synchronized (lock) {
            if (lifecycleCloseClaimed) {
                return;
            }
            if (owner == null) {
                owner = PUBLIC_OUTPUT_OWNER;
            } else if (!PUBLIC_OUTPUT_OWNER.equals(owner)) {
                throw new IllegalStateException("Session output is owned by " + owner);
            }
        }
        closeOperation.run();
    }

    @FunctionalInterface
    private interface OutputCloseOperation {

        void run() throws IOException;
    }

    private enum CloseResponsibility {
        LIFECYCLE,
        OUTPUT_OWNER
    }

    private static final class OutputGuardInputStream extends FilterInputStream {

        private final SessionOutputOwnership ownership;

        private OutputGuardInputStream(InputStream delegate, SessionOutputOwnership ownership) {
            super(Objects.requireNonNull(delegate, "delegate"));
            this.ownership = Objects.requireNonNull(ownership, "ownership");
        }

        @Override
        public int read() throws IOException {
            return withAccess(in::read);
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length == 0) {
                return 0;
            }
            return withAccess(() -> in.read(bytes));
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            return withAccess(() -> in.read(bytes, offset, length));
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return withAccess(in::readAllBytes);
        }

        @Override
        public byte[] readNBytes(int length) throws IOException {
            if (length < 0) {
                throw new IllegalArgumentException("len < 0");
            }
            if (length == 0) {
                return new byte[0];
            }
            return withAccess(() -> in.readNBytes(length));
        }

        @Override
        public int readNBytes(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            return withAccess(() -> in.readNBytes(bytes, offset, length));
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            return withAccess(() -> in.skip(count));
        }

        @Override
        public void skipNBytes(long count) throws IOException {
            if (count <= 0) {
                return;
            }
            withAccessVoid(() -> in.skipNBytes(count));
        }

        @Override
        public int available() throws IOException {
            return withAccess(in::available);
        }

        @Override
        public synchronized void mark(int readLimit) {
            ownership.beginPublicOperation();
            in.mark(readLimit);
        }

        @Override
        public boolean markSupported() {
            ownership.beginPublicOperation();
            return in.markSupported();
        }

        @Override
        public synchronized void reset() throws IOException {
            withAccessVoid(in::reset);
        }

        @Override
        public long transferTo(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            return withAccess(() -> in.transferTo(output));
        }

        @Override
        public void close() throws IOException {
            ownership.closePublicStream(in::close);
        }

        private <T> T withAccess(IoSupplier<T> operation) throws IOException {
            ownership.beginPublicOperation();
            return operation.get();
        }

        private void withAccessVoid(IoRunnable operation) throws IOException {
            ownership.beginPublicOperation();
            operation.run();
        }

        @FunctionalInterface
        private interface IoSupplier<T> {

            T get() throws IOException;
        }

        @FunctionalInterface
        private interface IoRunnable {

            void run() throws IOException;
        }
    }
}
