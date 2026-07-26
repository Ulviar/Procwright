/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class PooledProtocolSessionIntegrationFixtures {

    private PooledProtocolSessionIntegrationFixtures() {}

    static <I, O> ProtocolSessionScenario.PoolDraft<I, O> poolDraft(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            String... workerArguments) {
        return service.protocolSession(adapterFactory).withArgs(workerArguments).pooled();
    }

    static final class CoordinatedResponseAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponse = new CountDownLatch(1);
        private String request;

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            responseEntered.countDown();
            awaitIgnoringInterrupts(releaseResponse);
            return "slept:" + request;
        }

        boolean awaitResponseEntered() throws InterruptedException {
            return responseEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseResponse() {
            releaseResponse.countDown();
        }
    }
}
