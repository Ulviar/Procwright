/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PtyBootstrap {

    private static final String PROTOCOL_MAGIC = "PROCW3!!";
    private static final String PROTOCOL_END = "!!3WCORP";
    private static final int TOTAL_WIDTH = 6;
    private static final int COUNT_WIDTH = 3;
    private static final int FIELD_WIDTH = 5;
    private static final Charset NATIVE_CHARSET = nativeCharset();
    private static final byte[] READY_MARKER = "\u001ePROCWRIGHT_PTY_READY\u001f".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STARTED_MARKER =
            "\u001ePROCWRIGHT_PTY_STARTED\u001f".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_ENTRY_COUNT = 256;
    static final int MAX_FIELD_BYTES = 32 * 1_024;
    static final int MAX_TOTAL_BYTES = 128 * 1_024;
    static final int MAX_FRAME_BYTES = MAX_TOTAL_BYTES + 4 * 1_024;

    static final String SHELL_PROGRAM = """
            stty_path=$1
            env_path=$2
            dd_path=$3
            rows=$4
            columns=$5
            case $rows in ''|*[!0-9]*) exit 125 ;; esac
            case $columns in ''|*[!0-9]*) exit 125 ;; esac
            [ "${#rows}" -le 5 ] || exit 125
            [ "${#columns}" -le 5 ] || exit 125
            [ "$rows" -gt 0 ] && [ "$rows" -le 65535 ] || exit 125
            [ "$columns" -gt 0 ] && [ "$columns" -le 65535 ] || exit 125
            original_state=$("$stty_path" -g 2>/dev/null) || exit 125
            "$stty_path" raw -echo min 1 time 0 2>/dev/null || exit 125
            bootstrap_fail() {
                exit 125
            }
            read_exact() {
                requested_bytes=$1
                case $requested_bytes in ''|*[!0-9]*) return 1 ;; esac
                [ "$requested_bytes" -le 32768 ] || return 1
                if [ "$requested_bytes" -eq 0 ]; then
                    field=
                    return 0
                fi
                field=$("$dd_path" bs=1 count="$requested_bytes" 2>/dev/null; printf '\001') || return 1
                field=${field%?}
                [ "${#field}" -eq "$requested_bytes" ] || return 1
            }
            read_number() {
                read_exact "$1" || return 1
                case $field in ''|*[!0-9]*) return 1 ;; esac
                number=$field
                while [ "${number#0}" != "$number" ]; do
                    number=${number#0}
                done
                [ -n "$number" ] || number=0
                [ "$number" -le "$2" ] || return 1
            }
            read_payload_field() {
                read_number 5 32768 || return 1
                field_length=$number
                next_total=$((parsed_total + field_length))
                [ "$next_total" -le "$declared_total" ] || return 1
                parsed_total=$next_total
                read_exact "$field_length" || return 1
            }
            printf '\036PROCWRIGHT_PTY_READY\037'

            read_exact 8 || bootstrap_fail
            [ "$field" = 'PROCW3!!' ] || bootstrap_fail
            read_number 6 131072 || bootstrap_fail
            declared_total=$number
            parsed_total=0
            read_number 3 256 || bootstrap_fail
            environment_count=$number
            set --
            index=0
            while [ "$index" -lt "$environment_count" ]; do
                read_payload_field || bootstrap_fail
                environment_name=$field
                case $environment_name in ''|*=*) bootstrap_fail ;; esac
                for environment_entry do
                    existing_name=${environment_entry%%=*}
                    [ "$existing_name" != "$environment_name" ] || bootstrap_fail
                done
                read_payload_field || bootstrap_fail
                set -- "$@" "$environment_name=$field"
                index=$((index + 1))
            done

            read_number 3 256 || bootstrap_fail
            command_count=$number
            [ "$command_count" -gt 0 ] || bootstrap_fail
            index=0
            while [ "$index" -lt "$command_count" ]; do
                read_payload_field || bootstrap_fail
                if [ "$index" -eq 0 ]; then
                    case $field in ''|*=*) bootstrap_fail ;; esac
                fi
                set -- "$@" "$field"
                index=$((index + 1))
            done
            [ "$parsed_total" -eq "$declared_total" ] || bootstrap_fail
            read_exact 8 || bootstrap_fail
            [ "$field" = '!!3WCORP' ] || bootstrap_fail

            "$stty_path" "$original_state" 2>/dev/null || bootstrap_fail
            "$stty_path" rows "$rows" cols "$columns" 2>/dev/null || bootstrap_fail
            terminal_size=$("$stty_path" size 2>/dev/null) || bootstrap_fail
            [ "$terminal_size" = "$rows $columns" ] || bootstrap_fail
            printf '\036PROCWRIGHT_PTY_STARTED\037'
            exec "$env_path" -i -- "$@"
            """;

    private PtyBootstrap() {}

    static Prepared prepare(SystemPtyProvider.PtyPayload payload) throws IOException {
        EncodedPayload encoded = encode(Objects.requireNonNull(payload, "payload"));
        ByteArrayOutputStream frame =
                new ByteArrayOutputStream(Math.min(MAX_FRAME_BYTES, encoded.totalBytes() + 4_096));
        writeAscii(frame, PROTOCOL_MAGIC);
        writeFixedNumber(frame, encoded.totalBytes(), TOTAL_WIDTH);
        writeFixedNumber(frame, encoded.environment().size(), COUNT_WIDTH);
        for (EncodedEnvironmentEntry entry : encoded.environment()) {
            writeField(frame, entry.name());
            writeField(frame, entry.value());
        }
        writeFixedNumber(frame, encoded.command().size(), COUNT_WIDTH);
        for (byte[] argument : encoded.command()) {
            writeField(frame, argument);
        }
        writeAscii(frame, PROTOCOL_END);
        if (frame.size() > MAX_FRAME_BYTES) {
            throw new IOException("PTY bootstrap frame exceeds its byte limit");
        }
        return new Prepared(frame.toByteArray());
    }

    static List<String> commandFor(SystemPtyProvider.SystemPtySupport support, TerminalSize terminalSize) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(terminalSize, "terminalSize");
        List<String> wrapper = List.of(
                support.shellPath().toString(),
                "-c",
                SHELL_PROGRAM,
                "procwright-pty-bootstrap",
                support.sttyPath().toString(),
                support.envPath().toString(),
                support.ddPath().toString(),
                Integer.toString(terminalSize.rows()),
                Integer.toString(terminalSize.columns()));
        return switch (support.flavor()) {
            case BSD -> bsdCommand(support.scriptPath(), wrapper);
            case UTIL_LINUX -> utilLinuxCommand(support.scriptPath(), wrapper);
            case UNAVAILABLE -> throw new IllegalArgumentException("unavailable PTY support has no launch command");
        };
    }

    private static EncodedPayload encode(SystemPtyProvider.PtyPayload payload) throws IOException {
        if (payload.environment().size() > MAX_ENTRY_COUNT || payload.command().size() > MAX_ENTRY_COUNT) {
            throw new IOException("PTY bootstrap payload contains too many entries");
        }
        if (payload.command().isEmpty()) {
            throw new IOException("PTY bootstrap payload has no command");
        }

        ArrayList<EncodedEnvironmentEntry> environment =
                new ArrayList<>(payload.environment().size());
        Set<String> names = new HashSet<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : payload.environment().entrySet()) {
            if (!names.add(entry.getKey())) {
                throw new IOException("PTY bootstrap payload contains a duplicate environment name");
            }
            byte[] name = encodeField(entry.getKey());
            byte[] value = encodeField(entry.getValue());
            totalBytes = addToTotal(totalBytes, name.length);
            totalBytes = addToTotal(totalBytes, value.length);
            environment.add(new EncodedEnvironmentEntry(name, value));
        }

        ArrayList<byte[]> command = new ArrayList<>(payload.command().size());
        for (String argument : payload.command()) {
            byte[] encodedArgument = encodeField(argument);
            totalBytes = addToTotal(totalBytes, encodedArgument.length);
            command.add(encodedArgument);
        }
        if (command.get(0).length == 0 || payload.command().get(0).indexOf('=') >= 0) {
            throw new IOException("PTY bootstrap executable is unsupported");
        }
        return new EncodedPayload(List.copyOf(environment), List.copyOf(command), totalBytes);
    }

    private static byte[] encodeField(String value) throws IOException {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_FIELD_BYTES) {
            throw new IOException("PTY bootstrap payload field exceeds its byte limit");
        }
        CharsetEncoder encoder = NATIVE_CHARSET
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer output = ByteBuffer.allocate(encodingBufferCapacity(encoder, value.length()));
        CharBuffer input = CharBuffer.wrap(value);
        try {
            CoderResult encoded = encoder.encode(input, output, true);
            if (encoded.isError()) {
                encoded.throwException();
            }
            if (encoded.isOverflow() || input.hasRemaining()) {
                throw new IOException("PTY bootstrap payload field exceeds its byte limit");
            }
            CoderResult flushed = encoder.flush(output);
            if (flushed.isError()) {
                flushed.throwException();
            }
            if (flushed.isOverflow() || output.position() > MAX_FIELD_BYTES) {
                throw new IOException("PTY bootstrap payload field exceeds its byte limit");
            }
        } catch (CharacterCodingException exception) {
            throw new IOException(
                    "PTY bootstrap payload contains a field unsupported by the native charset", exception);
        }
        return Arrays.copyOf(output.array(), output.position());
    }

    private static int encodingBufferCapacity(CharsetEncoder encoder, int characterCount) {
        long estimatedBytes = (long) Math.ceil(characterCount * (double) encoder.maxBytesPerChar());
        return (int) Math.min(MAX_FIELD_BYTES + 1L, Math.max(1L, estimatedBytes + 1L));
    }

    private static int addToTotal(int current, int addition) throws IOException {
        if (addition > MAX_TOTAL_BYTES - current) {
            throw new IOException("PTY bootstrap payload exceeds its total byte limit");
        }
        return current + addition;
    }

    private static void writeField(ByteArrayOutputStream output, byte[] value) throws IOException {
        writeFixedNumber(output, value.length, FIELD_WIDTH);
        output.writeBytes(value);
    }

    private static void writeFixedNumber(ByteArrayOutputStream output, int value, int width) throws IOException {
        String digits = Integer.toString(value);
        if (digits.length() > width) {
            throw new IOException("PTY bootstrap frame metadata exceeds its width");
        }
        for (int index = digits.length(); index < width; index++) {
            output.write('0');
        }
        writeAscii(output, digits);
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> bsdCommand(java.nio.file.Path scriptPath, List<String> wrapper) {
        ArrayList<String> command = new ArrayList<>();
        command.add(scriptPath.toString());
        command.add("-q");
        command.add("-e");
        command.add("/dev/null");
        command.addAll(wrapper);
        return List.copyOf(command);
    }

    private static List<String> utilLinuxCommand(java.nio.file.Path scriptPath, List<String> wrapper) {
        return List.of(scriptPath.toString(), "-q", "-e", "-c", "exec " + shellCommand(wrapper), "/dev/null");
    }

    private static String shellCommand(List<String> command) {
        ArrayList<String> quoted = new ArrayList<>(command.size());
        for (String part : command) {
            quoted.add(shellQuote(part));
        }
        return String.join(" ", quoted);
    }

    private static String shellQuote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Charset nativeCharset() {
        try {
            String jnuEncoding = System.getProperty("sun.jnu.encoding");
            if (jnuEncoding != null && Charset.isSupported(jnuEncoding)) {
                return Charset.forName(jnuEncoding);
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Fall back to the standard process-wide charset below.
        }
        return Charset.defaultCharset();
    }

    static final class Prepared {

        private final byte[] frame;

        private Prepared(byte[] frame) {
            this.frame = frame.clone();
        }

        Process initialize(Process process, PtyLaunchAdmission.Context context)
                throws IOException, InterruptedException {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(context, "context");
            InputStream stdout = process.getInputStream();
            OutputStream stdin = process.getOutputStream();
            readMarker(stdout, READY_MARKER, "secure bootstrap", context);
            context.checkpoint();
            stdin.write(frame);
            stdin.flush();
            readMarker(stdout, STARTED_MARKER, "child-launch", context);
            context.checkpoint();
            return process;
        }

        private static void readMarker(
                InputStream input, byte[] expected, String phase, PtyLaunchAdmission.Context context)
                throws IOException, InterruptedException {
            byte[] actual = input.readNBytes(expected.length);
            context.checkpoint();
            if (!Arrays.equals(actual, expected)) {
                throw new IOException("PTY wrapper did not complete its " + phase + " handshake");
            }
        }
    }

    private record EncodedEnvironmentEntry(byte[] name, byte[] value) {}

    private record EncodedPayload(List<EncodedEnvironmentEntry> environment, List<byte[]> command, int totalBytes) {}
}
