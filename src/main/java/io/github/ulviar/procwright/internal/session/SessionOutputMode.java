/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Process-output topology selected before a session process is launched. */
public enum SessionOutputMode {
    RAW,
    EXPECT,
    LINE,
    PROTOCOL,
    STREAM;

    String owner() {
        return switch (this) {
            case RAW -> "RawSession";
            case EXPECT -> "Expect";
            case LINE -> "LineSession";
            case PROTOCOL -> "ProtocolSession";
            case STREAM -> "StreamSession";
        };
    }

    boolean raw() {
        return this == RAW;
    }

    void requireHelper() {
        if (raw()) {
            throw new IllegalArgumentException("Raw output does not use helper pumps");
        }
    }
}
