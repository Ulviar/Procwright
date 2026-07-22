/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import java.math.BigDecimal;
import java.math.BigInteger;

/** BigDecimal whose overridable representation accessors cannot be used for canonicalization. */
final class AdversarialBigDecimal extends BigDecimal {

    private static final long serialVersionUID = 1L;

    private int stringCalls;

    AdversarialBigDecimal(String value) {
        super(value);
    }

    @Override
    public String toString() {
        return stringCalls++ == 0 ? "0" : "999999999999999999999999999999";
    }

    @Override
    public int scale() {
        throw new AssertionError("canonicalization must not dispatch to BigDecimal.scale()");
    }

    @Override
    public BigInteger unscaledValue() {
        throw new AssertionError("canonicalization must not dispatch to BigDecimal.unscaledValue()");
    }

    int stringCalls() {
        return stringCalls;
    }
}
