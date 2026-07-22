/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class CommandServiceTest {

    @Test
    void commandServiceExposesOnlyScenarioSelectors() {
        assertEquals(
                Set.of("run", "interactive", "lineSession", "listen", "protocolSession"),
                Arrays.stream(CommandService.class.getDeclaredMethods())
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
    }

    @Test
    void selectorsReturnNestedDraftContracts() {
        CommandService service = Procwright.command("tool");

        assertInstanceOf(RunScenario.Draft.class, service.run());
        assertInstanceOf(InteractiveScenario.Draft.class, service.interactive());
        assertInstanceOf(LineSessionScenario.Draft.class, service.lineSession());
        assertInstanceOf(StreamScenario.Draft.class, service.listen());
        assertInstanceOf(ProtocolSessionScenario.Draft.class, service.protocolSession(NoopAdapter::new));
    }

    @Test
    void protocolSelectorAcceptsOnlyANonNullFactory() {
        CommandService service = Procwright.command("tool");

        assertThrows(
                NullPointerException.class,
                () -> service.protocolSession((java.util.function.Supplier<
                                ? extends io.github.ulviar.procwright.session.ProtocolAdapter<String, String>>)
                        null));
        assertEquals(
                1,
                Arrays.stream(CommandService.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("protocolSession"))
                        .count());
    }

    @Test
    void draftSettersValidateImmediatelyButPoolCrossFieldsWaitForOpen() {
        CommandService service = Procwright.command("tool");

        assertThrows(NullPointerException.class, () -> service.run().withArgs((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> service.run().withTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> service.lineSession().withMaxRequestChars(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.lineSession().pooled().withMaxSize(0));

        LineSessionScenario.PoolDraft temporarilyInconsistent =
                service.lineSession().pooled().withWarmupSize(2);
        assertThrows(IllegalArgumentException.class, temporarilyInconsistent::open);
    }

    @Test
    void procwrightHasNoCompetingShellEntryPoint() {
        assertThrows(NoSuchMethodException.class, () -> Procwright.class.getMethod("shellCommand", String.class));
        assertThrows(NullPointerException.class, () -> Procwright.command((CommandSpec) null));
    }

    private static final class NoopAdapter implements ProtocolAdapter<String, String> {
        @Override
        public void writeRequest(String request, ProtocolWriter writer) {}

        @Override
        public String readResponse(ProtocolReaders readers) {
            return "";
        }
    }
}
