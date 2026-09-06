package app.guidecast.client

import android.app.Application
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import app.guidecast.provider.moonshine.stt.MoonshineSpeechRecognitionEngine
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider

class GuideCastClientApplication : Application() {
    private val translationProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MlKitTranslationProvider(this, sourceLanguageTag = "ko", requireWifiForModels = false)
    }
    val translationProvider by translationProviderDelegate
    private val speechRecognitionEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MoonshineSpeechRecognitionEngine(this)
    }
    val speechRecognitionEngine by speechRecognitionEngineDelegate
    private val speechSynthesisProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MoonshineSpeechSynthesisProvider(this)
    }
    val speechSynthesisProvider by speechSynthesisProviderDelegate
    private val sessionControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ClientSessionController(this)
    }
    val sessionController by sessionControllerDelegate

    override fun onTerminate() {
        if (sessionControllerDelegate.isInitialized()) sessionController.close()
        if (translationProviderDelegate.isInitialized()) translationProvider.close()
        if (speechRecognitionEngineDelegate.isInitialized()) speechRecognitionEngine.close()
        if (speechSynthesisProviderDelegate.isInitialized()) speechSynthesisProvider.close()
        super.onTerminate()
    }
}
