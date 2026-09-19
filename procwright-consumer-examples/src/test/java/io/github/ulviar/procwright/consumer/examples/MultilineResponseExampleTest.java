/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.examples;

import io.github.ulviar.procwright.examples.MultilineResponseExample;
import org.junit.jupiter.api.Test;

final class MultilineResponseExampleTest {

    @Test
    void readsTwoResponsesWithoutLeavingTheTerminatorForTheNextRequest() {
        MultilineResponseExample.main(new String[0]);
    }
}
