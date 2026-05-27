package io.github.ulviar.procwright.session;

enum NoopStreamListener implements StreamListener {
    INSTANCE;

    @Override
    public void onChunk(StreamChunk chunk) {
        // Intentionally ignored.
    }
}
