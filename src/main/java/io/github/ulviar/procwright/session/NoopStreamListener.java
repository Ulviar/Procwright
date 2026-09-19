/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/** Stateless shared listener used by {@link StreamListener#noop()}. */
enum NoopStreamListener implements StreamListener {
    INSTANCE;

    @Override
    public void onChunk(StreamChunk chunk) {
        // Intentionally ignored.
    }
}
