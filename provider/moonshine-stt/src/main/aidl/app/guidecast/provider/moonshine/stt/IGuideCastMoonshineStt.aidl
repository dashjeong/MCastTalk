package app.guidecast.provider.moonshine.stt;

import app.guidecast.provider.moonshine.stt.IGuideCastMoonshineSttCallback;

/** Private-process transport for Moonshine's native STT runtime. */
oneway interface IGuideCastMoonshineStt {
    void prepare(long operationId, String languageTag, IGuideCastMoonshineSttCallback callback);
    void startRecognition(
        long sessionId,
        String languageTag,
        int sampleRateHz,
        int channelCount,
        IGuideCastMoonshineSttCallback callback
    );
    void pushPcm(long sessionId, long frameId, in byte[] pcm16Le);
    void stopRecognition(long sessionId);
    void shutdown();
}
