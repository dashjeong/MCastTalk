#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

JNIEXPORT jlong JNICALL Java_app_guidecast_core_audio_NativeRnNoiseFilter_create(
        JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT void JNICALL Java_app_guidecast_core_audio_NativeRnNoiseFilter_processFrame(
        JNIEnv *env, jobject self, jlong handle, jfloatArray samples) {
    (void)self;
    if (!handle || !samples || (*env)->GetArrayLength(env, samples) != 480) {
        jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (cls) (*env)->ThrowNew(env, cls, "RNNoise requires 480 samples and an active state");
        return;
    }
    float frame[480];
    (*env)->GetFloatArrayRegion(env, samples, 0, 480, frame);
    if ((*env)->ExceptionCheck(env)) return;
    rnnoise_process_frame((DenoiseState *)(intptr_t)handle, frame, frame);
    (*env)->SetFloatArrayRegion(env, samples, 0, 480, frame);
}

JNIEXPORT void JNICALL Java_app_guidecast_core_audio_NativeRnNoiseFilter_destroy(
        JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    if (handle) rnnoise_destroy((DenoiseState *)(intptr_t)handle);
}
