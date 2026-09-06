package app.guidecast.provider.gemma.translation;

/** One-way result channel so a cancelled realtime request never blocks the broadcast process. */
oneway interface IGuideCastGemmaInferenceCallback {
    void onSuccess(long requestId, String translatedText);
    void onError(long requestId, String message);
    /** Native work has actually returned, including after client cancellation. */
    void onFinished(long requestId);
}
