package com.github.ulviar.icli.session;

enum NoopStreamListener implements StreamListener {
    INSTANCE;

    @Override
    public void onChunk(StreamChunk chunk) {
        // Intentionally ignored.
    }
}
