package io.github.ulviar.icli.comparison;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

final class BoundedCapture extends OutputStream {

    private final byte[] buffer;
    private final Consumer<byte[]> observer;
    private int size;
    private boolean truncated;

    BoundedCapture(int limit) {
        this(limit, bytes -> {});
    }

    BoundedCapture(int limit, Consumer<byte[]> observer) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.buffer = new byte[limit];
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public synchronized void write(int value) throws IOException {
        write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] source, int offset, int length) {
        byte[] observed = Arrays.copyOfRange(source, offset, offset + length);
        observer.accept(observed);
        int available = buffer.length - size;
        if (available <= 0) {
            truncated = true;
            return;
        }
        int retained = Math.min(available, length);
        System.arraycopy(source, offset, buffer, size, retained);
        size += retained;
        if (retained < length) {
            truncated = true;
        }
    }

    synchronized byte[] bytes() {
        return Arrays.copyOf(buffer, size);
    }

    synchronized boolean truncated() {
        return truncated;
    }
}
