package com.github.ulviar.icli;

enum NoopStreamListener implements StreamListener {
    INSTANCE;

    @Override
    public void onChunk(StreamChunk chunk) {
        // Intentionally ignored.
    }
}
