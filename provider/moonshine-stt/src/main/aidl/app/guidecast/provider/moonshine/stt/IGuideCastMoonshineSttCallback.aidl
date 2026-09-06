package app.guidecast.provider.moonshine.stt;

/** Events are one-way so a slow or closing UI process cannot block the native worker. */
oneway interface IGuideCastMoonshineSttCallback {
    void onPreparationStatus(
        long operationId,
        int readiness,
        String message,
        float progress,
        String currentFile
    );
    void onSessionReady(long sessionId);
    void onTranscript(
        long sessionId,
        long lineId,
        String text,
        boolean isFinal,
        long capturedAtElapsedRealtimeNanos
    );
    void onPcmConsumed(long sessionId, long frameId);
    void onSessionStopped(long sessionId);
    void onError(long operationId, int errorCode, String message);
    /** Terminal acknowledgement for a prepare request after JNI work has really returned. */
    void onFinished(long operationId);
}
