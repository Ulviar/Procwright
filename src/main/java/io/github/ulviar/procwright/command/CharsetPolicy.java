/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/**
 * Text decoding policy for command and session output.
 *
 * <p>{@link CodingErrorAction#REPLACE} uses the forgiving JDK replacement behavior. {@link
 * CodingErrorAction#REPORT} turns malformed or unmappable bytes into typed Procwright failures instead of silently inserting
 * replacement characters.
 * {@link CodingErrorAction#IGNORE} is not supported. These error actions apply only to decoding.
 * Line and protocol sessions also use {@link #charset()} for stdin text, with replacement during encoding.
 *
 * @param charset text charset
 * @param malformedInputAction action for malformed byte sequences
 * @param unmappableCharacterAction action for unmappable byte sequences
 */
public record CharsetPolicy(
        Charset charset, CodingErrorAction malformedInputAction, CodingErrorAction unmappableCharacterAction) {

    /**
     * Creates a charset policy.
     *
     * @param charset text charset
     * @param malformedInputAction {@link CodingErrorAction#REPORT} or {@link CodingErrorAction#REPLACE}
     * @param unmappableCharacterAction {@link CodingErrorAction#REPORT} or {@link CodingErrorAction#REPLACE}
     * @throws IllegalArgumentException if either action is neither {@code REPORT} nor {@code REPLACE}
     */
    public CharsetPolicy {
        Objects.requireNonNull(charset, "charset");
        requireSupportedAction(malformedInputAction, "malformedInputAction");
        requireSupportedAction(unmappableCharacterAction, "unmappableCharacterAction");
    }

    /**
     * Returns a policy that replaces malformed and unmappable input.
     *
     * @param charset text charset
     * @return replacing policy
     */
    public static CharsetPolicy replace(Charset charset) {
        return new CharsetPolicy(charset, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
    }

    /**
     * Returns a policy that reports malformed and unmappable input.
     *
     * @param charset text charset
     * @return strict reporting policy
     */
    public static CharsetPolicy report(Charset charset) {
        return new CharsetPolicy(charset, CodingErrorAction.REPORT, CodingErrorAction.REPORT);
    }

    /**
     * Decodes bytes according to this policy.
     *
     * <p>Each call creates a new decoder and treats the array as complete input. The array is read without being
     * modified and must not be changed concurrently. Unlike a command run, this method reports JDK coding exceptions
     * directly and does not produce a {@link CommandExecutionException} or diagnostic result.
     *
     * @param bytes bytes to decode
     * @return decoded text
     * @throws CharacterCodingException when this policy reports malformed or unmappable input
     */
    public String decode(byte[] bytes) throws CharacterCodingException {
        Objects.requireNonNull(bytes, "bytes");
        return charset.newDecoder()
                .onMalformedInput(malformedInputAction)
                .onUnmappableCharacter(unmappableCharacterAction)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static void requireSupportedAction(CodingErrorAction action, String name) {
        Objects.requireNonNull(action, name);
        if (action != CodingErrorAction.REPORT && action != CodingErrorAction.REPLACE) {
            throw new IllegalArgumentException(name + " must be REPORT or REPLACE");
        }
    }
}
