package app.guidecast.provider.gemma.translation;

import app.guidecast.provider.gemma.translation.IGuideCastGemmaInferenceCallback;

interface IGuideCastGemmaInference {
    oneway void translateAsync(
        long requestId,
        String modelVariantId,
        String text,
        String contextBefore,
        String sourceLanguageTag,
        String targetLanguageTag,
        String glossaryHints,
        String reviewDraft,
        IGuideCastGemmaInferenceCallback callback
    );
    oneway void cancel(long requestId);
    oneway void resetEngine(long cancelThroughRequestId);
}
