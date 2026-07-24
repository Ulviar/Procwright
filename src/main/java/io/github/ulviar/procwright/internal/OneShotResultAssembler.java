/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalInt;

/** Decodes completed one-shot captures and preserves raw bytes in success and typed decode-failure results. */
final class OneShotResultAssembler {

    private OneShotResultAssembler() {}

    static CommandResult assemble(
            CapturedOutput stdout,
            CapturedOutput stderr,
            CharsetPolicy charsetPolicy,
            OptionalInt exitCode,
            boolean timedOut,
            Duration elapsed) {
        try {
            return new CommandResult(
                    exitCode,
                    stdout.bytes(),
                    stderr.bytes(),
                    decode(stdout, charsetPolicy),
                    decode(stderr, charsetPolicy),
                    stdout.truncated(),
                    stderr.truncated(),
                    timedOut,
                    elapsed);
        } catch (OutputDecodingException decodeFailure) {
            CommandResult diagnosticResult = new CommandResult(
                    exitCode,
                    stdout.bytes(),
                    stderr.bytes(),
                    diagnosticText(stdout.bytes(), charsetPolicy, decodeFailure.getCause()),
                    diagnosticText(stderr.bytes(), charsetPolicy, decodeFailure.getCause()),
                    stdout.truncated(),
                    stderr.truncated(),
                    timedOut,
                    elapsed);
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.DECODE_ERROR,
                    decodeFailure.getMessage() + " with "
                            + charsetPolicy.charset().name(),
                    decodeFailure.getCause(),
                    diagnosticResult);
        }
    }

    private static String decode(CapturedOutput output, CharsetPolicy charsetPolicy) {
        try {
            if (output.truncated()) {
                return decodeTruncatedPrefix(output.bytes(), charsetPolicy);
            }
            return OneShotTextDecoder.decode(output.bytes(), charsetPolicy);
        } catch (CharacterCodingException | RuntimeException | CoderMalfunctionError exception) {
            throw new OutputDecodingException("Could not decode command output", exception);
        }
    }

    private static String decodeTruncatedPrefix(byte[] bytes, CharsetPolicy charsetPolicy)
            throws CharacterCodingException {
        int completePrefixLength = OneShotTextDecoder.completePrefixLength(bytes, charsetPolicy);
        byte[] completePrefix =
                completePrefixLength == bytes.length ? bytes : Arrays.copyOf(bytes, completePrefixLength);
        return OneShotTextDecoder.decode(completePrefix, charsetPolicy);
    }

    private static String diagnosticText(byte[] bytes, CharsetPolicy charsetPolicy, Throwable decodeFailure) {
        if (decodeFailure instanceof CharacterCodingException) {
            try {
                return new String(bytes, charsetPolicy.charset());
            } catch (RuntimeException | Error diagnosticFailure) {
                SuppressionSupport.attach(decodeFailure, diagnosticFailure);
            }
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @SuppressWarnings("serial")
    private static final class OutputDecodingException extends RuntimeException {

        private OutputDecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
