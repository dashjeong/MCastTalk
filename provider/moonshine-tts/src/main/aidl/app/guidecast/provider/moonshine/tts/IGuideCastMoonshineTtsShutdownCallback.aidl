package app.guidecast.provider.moonshine.tts;

/** Acknowledges only after the isolated worker's native runtime has really closed. */
oneway interface IGuideCastMoonshineTtsShutdownCallback {
    void onShutdownComplete();
    void onShutdownError(String message);
}
