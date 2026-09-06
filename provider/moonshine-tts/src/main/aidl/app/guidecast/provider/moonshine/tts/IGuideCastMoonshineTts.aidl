package app.guidecast.provider.moonshine.tts;

import app.guidecast.provider.moonshine.tts.IGuideCastMoonshineTtsCallback;
import app.guidecast.provider.moonshine.tts.IGuideCastMoonshineTtsShutdownCallback;

oneway interface IGuideCastMoonshineTts {
    void prepareAssets(String clientId, long requestId, String languageTag, IGuideCastMoonshineTtsCallback callback);
    void prepare(String clientId, long requestId, String languageTag, IGuideCastMoonshineTtsCallback callback);
    void synthesizeToFile(String clientId, long requestId, String text, String languageTag, IGuideCastMoonshineTtsCallback callback);
    void synthesizeStreaming(String clientId, long requestId, String text, String languageTag, IGuideCastMoonshineTtsCallback callback);
    void cancel(String clientId, long requestId);
    void shutdown();
    void shutdownWhenIdle(IGuideCastMoonshineTtsShutdownCallback callback);
}
