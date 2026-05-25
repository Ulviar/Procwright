package io.github.ulviar.icli.session;

/**
 * Protocol response readers for stdout and stderr.
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
