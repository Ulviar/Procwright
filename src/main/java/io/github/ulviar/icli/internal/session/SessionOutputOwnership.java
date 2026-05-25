package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.internal.CommandValidation;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Supplier;

final class SessionOutputOwnership {

    private static final String PUBLIC_OUTPUT_OWNER = "public output streams";

    private final Object lock = new Object();
    private String owner;
    private int activePublicOperations;

    InputStream publicStream(InputStream stream) {
        return new OutputGuardInputStream(stream, this::beginPublicOperation);
    }

    void claim(String requestedOwner) {
        CommandValidation.requireText(requestedOwner, "owner");
        synchronized (lock) {
            if (activePublicOperations > 0) {
                throw new IllegalStateException("Session output is in use by " + PUBLIC_OUTPUT_OWNER);
            }
            if (owner != null) {
                throw new IllegalStateException("Session output is already owned by " + owner);
            }
            owner = requestedOwner;
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

    private OutputAccess beginPublicOperation() {
        synchronized (lock) {
            if (owner == null) {
                owner = PUBLIC_OUTPUT_OWNER;
            } else if (!PUBLIC_OUTPUT_OWNER.equals(owner)) {
                throw new IllegalStateException("Session output is owned by " + owner);
            }
            activePublicOperations++;
        }
        return this::endPublicOperation;
    }

    private void endPublicOperation() {
        synchronized (lock) {
            activePublicOperations--;
        }
    }

    private interface OutputAccess extends AutoCloseable {

        @Override
        void close();
    }

    private static final class OutputGuardInputStream extends FilterInputStream {

        private final Supplier<OutputAccess> accessSupplier;

        private OutputGuardInputStream(InputStream delegate, Supplier<OutputAccess> accessSupplier) {
            super(Objects.requireNonNull(delegate, "delegate"));
            this.accessSupplier = Objects.requireNonNull(accessSupplier, "accessSupplier");
        }

        @Override
        public int read() throws IOException {
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.read();
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.read(bytes, offset, length);
            }
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.readAllBytes();
            }
        }

        @Override
        public byte[] readNBytes(int length) throws IOException {
            if (length < 0) {
                throw new IllegalArgumentException("len < 0");
            }
            if (length == 0) {
                return new byte[0];
            }
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.readNBytes(length);
            }
        }

        @Override
        public int readNBytes(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.readNBytes(bytes, offset, length);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.skip(count);
            }
        }

        @Override
        public int available() throws IOException {
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.available();
            }
        }

        @Override
        public long transferTo(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            try (OutputAccess ignored = accessSupplier.get()) {
                return super.transferTo(output);
            }
        }

        @Override
        public void close() throws IOException {
            try (OutputAccess ignored = accessSupplier.get()) {
                super.close();
            }
        }
    }
}
