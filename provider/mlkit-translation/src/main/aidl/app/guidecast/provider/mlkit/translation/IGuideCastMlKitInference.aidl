package app.guidecast.provider.mlkit.translation;

import app.guidecast.provider.mlkit.translation.IGuideCastMlKitInferenceCallback;

/** One-way entry points keep a failed private worker from blocking the broadcast process. */
oneway interface IGuideCastMlKitInference {
    void translateAsync(
        long requestId,
        String text,
        String sourceLanguageTag,
        String targetLanguageTag,
        IGuideCastMlKitInferenceCallback callback
    );
    void prepareAsync(
        long requestId,
        String sourceLanguageTag,
        String targetLanguageTag,
        boolean requireWifi,
        IGuideCastMlKitInferenceCallback callback
    );
    void drainAndCloseAsync(
        long requestId,
        IGuideCastMlKitInferenceCallback callback
    );
    void cancel(long requestId);
    void shutdown();
}
