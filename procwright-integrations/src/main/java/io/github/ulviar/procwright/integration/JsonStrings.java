/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import java.util.Objects;

/** Validates the Unicode scalar-value invariant shared by every JSON transport. */
final class JsonStrings {

    private JsonStrings() {}

    static String requireWellFormedUtf16(String value, String nullName, String description) {
        Objects.requireNonNull(value, nullName);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw malformed(description, index);
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw malformed(description, index);
            }
        }
        return value;
    }

    private static JsonParseException malformed(String description, int index) {
        return new JsonParseException(description + " contains an unpaired UTF-16 surrogate at index " + index);
    }
}
