# Moonshine Voice publishes native bindings that are loaded in the private STT process.
-keep class ai.moonshine.voice.** { *; }

# Keep the manifest-created service and generated Binder endpoints across consumer shrinking.
-keep class app.guidecast.provider.moonshine.stt.MoonshineSttInferenceService { *; }
-keep interface app.guidecast.provider.moonshine.stt.IGuideCastMoonshineStt { *; }
-keep class app.guidecast.provider.moonshine.stt.IGuideCastMoonshineStt$Stub { *; }
-keep interface app.guidecast.provider.moonshine.stt.IGuideCastMoonshineSttCallback { *; }
-keep class app.guidecast.provider.moonshine.stt.IGuideCastMoonshineSttCallback$Stub { *; }
