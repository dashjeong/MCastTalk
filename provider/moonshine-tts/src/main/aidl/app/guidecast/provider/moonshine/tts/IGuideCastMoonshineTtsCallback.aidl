package app.guidecast.provider.moonshine.tts;

oneway interface IGuideCastMoonshineTtsCallback {
    void onProgress(long requestId, String currentFile, int fileIndex, int fileCount, long bytesRead, long totalBytes);
    void onPrepared(long requestId);
    void onSynthesized(long requestId, String path);
    void onPcmChunk(long requestId, in byte[] pcm);
    void onStreamCompleted(long requestId);
    void onError(long requestId, String message);
    void onFinished(long requestId);
}
