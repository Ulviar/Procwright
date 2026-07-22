/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Protocol response readers for stdout and stderr.
 *
 * <p>The returned readers have the callback scope and thread confinement documented by {@link ProtocolReader}.
 */
public interface ProtocolReaders {

    /**
     * Returns the stdout reader.
     *
     * @return stdout reader
     */
    ProtocolReader stdout();

    /**
     * Returns the stderr reader.
     *
     * @return stderr reader
     */
    ProtocolReader stderr();
}
