/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import java.math.BigDecimal;
import java.util.Objects;

final class JsonNumbers {

    private JsonNumbers() {}

    static BigDecimal parse(String value) {
        Objects.requireNonNull(value, "value");
        if (!isJsonNumber(value)) {
            throw new JsonParseException("Invalid JSON number");
        }
        return new BigDecimal(value);
    }

    static BigDecimal canonicalize(BigDecimal value) {
        // Dispatch on the trusted BigDecimal receiver so hostile subclass overrides cannot affect the snapshot.
        return BigDecimal.ONE.multiply(Objects.requireNonNull(value, "value"));
    }

    static boolean isJsonNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int index = 0;
        if (value.charAt(index) == '-') {
            index++;
            if (index == value.length()) {
                return false;
            }
        }
        char first = value.charAt(index);
        if (first == '0') {
            index++;
            if (index < value.length() && Character.isDigit(value.charAt(index))) {
                return false;
            }
        } else if (first >= '1' && first <= '9') {
            index++;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
        } else {
            return false;
        }
        if (index < value.length() && value.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (index == fractionStart) {
                return false;
            }
        }
        if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
                index++;
            }
            int exponentStart = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (index == exponentStart) {
                return false;
            }
        }
        return index == value.length();
    }
}
