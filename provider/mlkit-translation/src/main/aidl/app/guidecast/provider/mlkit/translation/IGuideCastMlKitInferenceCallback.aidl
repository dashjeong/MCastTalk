package app.guidecast.provider.mlkit.translation;

/** Result channel for exactly one language worker request. */
oneway interface IGuideCastMlKitInferenceCallback {
    void onSuccess(long requestId, String translatedText);
    void onError(long requestId, String message);
    /** Underlying Google Task/native work reached its real terminal state. */
    void onFinished(long requestId);
}
