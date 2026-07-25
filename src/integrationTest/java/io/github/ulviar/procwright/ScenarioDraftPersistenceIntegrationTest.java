/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.interactiveResponse;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.lineResponse;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.protocolResponse;
import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.streamOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ScenarioDraftPersistenceIntegrationTest {

    @Test
    void runDraftCopiesCallerInputsAndKeepsBranchesIndependent() {
        RunScenario.Draft base = Procwright.command(TestCliSupport.command()).run();

        String[] array = {"exit", "--stdout=array\n"};
        RunScenario.Draft fromArray = base.withArgs(array);
        array[1] = "--stdout=mutated\n";

        ArrayList<String> collection = new ArrayList<>(Arrays.asList("exit", "--stdout=collection\n"));
        RunScenario.Draft fromCollection = base.withArgs(collection);
        collection.set(1, "--stdout=mutated\n");

        RunScenario.Draft left = base.withArgs("exit", "--stdout=left\n");
        RunScenario.Draft right = base.withArgs("exit", "--stdout=right\n");

        assertEquals("array\n", fromArray.execute().stdout());
        assertEquals("collection\n", fromCollection.execute().stdout());
        assertEquals("left\n", left.execute().stdout());
        assertEquals("right\n", right.execute().stdout());
    }

    @Test
    void interactiveDraftCopiesArgumentsAndKeepsBranchesIndependent() throws Exception {
        InteractiveScenario.Draft base =
                Procwright.command(TestCliSupport.command()).interactive();
        String[] arguments = {"controlled-line-repl", "--response-prefix=array:"};
        InteractiveScenario.Draft copied = base.withArgs(arguments);
        arguments[1] = "--response-prefix=mutated:";

        assertEquals("array:value", interactiveResponse(copied, "value"));
        assertEquals(
                "left:value",
                interactiveResponse(base.withArgs("controlled-line-repl", "--response-prefix=left:"), "value"));
        assertEquals(
                "right:value",
                interactiveResponse(base.withArgs("controlled-line-repl", "--response-prefix=right:"), "value"));
    }

    @Test
    void lineDraftCopiesArgumentsAndKeepsBranchesIndependent() {
        LineSessionScenario.Draft base =
                Procwright.command(TestCliSupport.command()).lineSession();
        ArrayList<String> arguments = new ArrayList<>(List.of("controlled-line-repl", "--response-prefix=collection:"));
        LineSessionScenario.Draft copied = base.withArgs(arguments);
        arguments.set(1, "--response-prefix=mutated:");

        assertEquals("collection:value", lineResponse(copied, "value"));
        assertEquals(
                "left:value", lineResponse(base.withArgs("controlled-line-repl", "--response-prefix=left:"), "value"));
        assertEquals(
                "right:value",
                lineResponse(base.withArgs("controlled-line-repl", "--response-prefix=right:"), "value"));
    }

    @Test
    void streamDraftCopiesArgumentsAndKeepsBranchesIndependent() throws Exception {
        StreamScenario.Draft base = Procwright.command(TestCliSupport.command()).listen();
        String[] arguments = {"exit", "--stdout=stream-original"};
        StreamScenario.Draft copied = base.withArgs(arguments);
        arguments[1] = "--stdout=mutated";

        assertEquals("stream-original", streamOutput(copied));
        assertEquals("left", streamOutput(base.withArgs("exit", "--stdout=left")));
        assertEquals("right", streamOutput(base.withArgs("exit", "--stdout=right")));
    }

    @Test
    void protocolDraftCopiesArgumentsAndKeepsBranchesIndependent() {
        ProtocolSessionScenario.Draft<String, String> base =
                Procwright.command(TestCliSupport.command()).protocolSession(TextLineAdapter::new);
        String[] arguments = {"controlled-line-repl", "--response-prefix=array:"};
        ProtocolSessionScenario.Draft<String, String> copied = base.withArgs(arguments);
        arguments[1] = "--response-prefix=mutated:";

        assertEquals("array:value", protocolResponse(copied, "value"));
        assertEquals(
                "left:value",
                protocolResponse(base.withArgs("controlled-line-repl", "--response-prefix=left:"), "value"));
        assertEquals(
                "right:value",
                protocolResponse(base.withArgs("controlled-line-repl", "--response-prefix=right:"), "value"));
    }

    @Test
    void lineCharsetConfigurationUsesTheLastWrite() {
        LineSessionScenario.Draft base =
                Procwright.command(TestCliSupport.command()).lineSession().withArgs("controlled-line-repl");
        LineSessionScenario.Draft strict = base.withCharset(StandardCharsets.UTF_8)
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));
        LineSessionScenario.Draft lenient = base.withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .withCharset(StandardCharsets.UTF_8);

        try (LineSession session = strict.open()) {
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> session.request("malformed-utf8"));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
        }
        try (LineSession session = lenient.open()) {
            assertEquals("\uFFFD", session.request("malformed-utf8").text());
        }
    }

    @Test
    void protocolCharsetConfigurationUsesTheLastWrite() {
        ProtocolSessionScenario.Draft<byte[], String> base = Procwright.command(TestCliSupport.command())
                .protocolSession(FramedBytesAsLineAdapter::new)
                .withArgs("length-line-frame");
        ProtocolSessionScenario.Draft<byte[], String> strict = base.withCharsetPolicy(
                        CharsetPolicy.replace(StandardCharsets.UTF_8))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));
        ProtocolSessionScenario.Draft<byte[], String> lenient = base.withCharsetPolicy(
                        CharsetPolicy.report(StandardCharsets.UTF_8))
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8));

        try (ProtocolSession<byte[], String> session = strict.open()) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {(byte) 0xFF}));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
        }
        try (ProtocolSession<byte[], String> session = lenient.open()) {
            assertEquals("\uFFFD\n", session.request(new byte[] {(byte) 0xFF}));
        }
    }

    private static final class FramedBytesAsLineAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(Arrays.copyOf(request, request.length));
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            parseLength(stdout.readLine(32));
            String body = stdout.readTextUntil((byte) '\n', 32);
            assertEquals("END", stdout.readLine(8));
            return body;
        }
    }
}
