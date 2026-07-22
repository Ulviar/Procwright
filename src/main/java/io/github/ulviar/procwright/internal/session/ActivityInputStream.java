/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Marks session activity after an input operation actually consumes bytes. */
final class ActivityInputStream extends FilterInputStream {

    private final Runnable activity;

    ActivityInputStream(InputStream delegate, Runnable activity) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        markIfPositive(value < 0 ? 0 : 1);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = super.read(bytes, offset, length);
        markIfPositive(count);
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        long skipped = super.skip(count);
        markIfPositive(skipped);
        return skipped;
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        byte[] bytes = super.readAllBytes();
        markIfPositive(bytes.length);
        return bytes;
    }

    @Override
    public byte[] readNBytes(int length) throws IOException {
        byte[] bytes = super.readNBytes(length);
        markIfPositive(bytes.length);
        return bytes;
    }

    @Override
    public int readNBytes(byte[] bytes, int offset, int length) throws IOException {
        int count = super.readNBytes(bytes, offset, length);
        markIfPositive(count);
        return count;
    }

    @Override
    public long transferTo(OutputStream output) throws IOException {
        long count = super.transferTo(output);
        markIfPositive(count);
        return count;
    }

    private void markIfPositive(long count) {
        if (count > 0) {
            activity.run();
        }
    }
}
