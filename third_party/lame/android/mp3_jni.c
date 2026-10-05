/* MCastTalk's thin JNI client. LAME itself remains a separate LGPL shared library. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>
#include "lame.h"
#define MAX_SAMPLES 4096
#define MP3_BUFFER 16384
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
typedef struct Encoder { jlong id; lame_t lame; int flushed; struct Encoder *next; } Encoder;
static Encoder *encoders;
static jlong next_id = 1;
static void quiet(const char *format, va_list args) { (void)format; (void)args; }
static void error(JNIEnv *env, const char *message) {
    jclass klass = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (klass) (*env)->ThrowNew(env, klass, message);
}
static Encoder *find(jlong id) { for (Encoder *e = encoders; e; e = e->next) if (e->id == id) return e; return NULL; }
JNIEXPORT jlong JNICALL Java_app_guidecast_transmitter_LameMp3Native_create(JNIEnv *env, jobject self, jint rate) {
    (void)self;
    if (rate != 16000 && rate != 24000 && rate != 32000 && rate != 44100 && rate != 48000) { error(env, "Unsupported MP3 sample rate"); return 0; }
    pthread_mutex_lock(&mutex);
    int count = 0; for (Encoder *p = encoders; p; p = p->next) count++;
    Encoder *e = count < 4 ? calloc(1, sizeof(*e)) : NULL;
    if (!e || !(e->lame = lame_init())) { free(e); pthread_mutex_unlock(&mutex); error(env, "MP3 encoder unavailable"); return 0; }
    lame_set_errorf(e->lame, quiet); lame_set_debugf(e->lame, quiet); lame_set_msgf(e->lame, quiet);
    lame_set_write_id3tag_automatic(e->lame, 0);
    int result = lame_set_num_channels(e->lame, 1) | lame_set_in_samplerate(e->lame, rate)
        | lame_set_out_samplerate(e->lame, rate) | lame_set_mode(e->lame, MONO)
        | lame_set_brate(e->lame, 64) | lame_set_quality(e->lame, 2) | lame_set_bWriteVbrTag(e->lame, 1);
    if (result < 0 || lame_init_params(e->lame) < 0) { lame_close(e->lame); free(e); pthread_mutex_unlock(&mutex); error(env, "MP3 encoder initialization failed"); return 0; }
    e->id = next_id++; e->next = encoders; encoders = e;
    jlong id = e->id; pthread_mutex_unlock(&mutex); return id;
}
JNIEXPORT jbyteArray JNICALL Java_app_guidecast_transmitter_LameMp3Native_encode(JNIEnv *env, jobject self, jlong id, jshortArray input, jint count) {
    (void)self;
    if (!input || count < 1 || count > MAX_SAMPLES || count > (*env)->GetArrayLength(env,input)) { error(env, "Invalid PCM chunk"); return NULL; }
    short pcm[MAX_SAMPLES]; unsigned char bytes[MP3_BUFFER];
    (*env)->GetShortArrayRegion(env, input, 0, count, pcm); if ((*env)->ExceptionCheck(env)) return NULL;
    pthread_mutex_lock(&mutex); Encoder *e = find(id);
    int size = e && !e->flushed ? lame_encode_buffer(e->lame, pcm, pcm, count, bytes, MP3_BUFFER) : -1;
    pthread_mutex_unlock(&mutex);
    if (size < 0 || size > MP3_BUFFER) { error(env, "MP3 encoding failed"); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, size); if (out && size) (*env)->SetByteArrayRegion(env,out,0,size,(jbyte *)bytes); return out;
}
JNIEXPORT jbyteArray JNICALL Java_app_guidecast_transmitter_LameMp3Native_flush(JNIEnv *env, jobject self, jlong id) {
    (void)self; unsigned char bytes[MP3_BUFFER];
    pthread_mutex_lock(&mutex); Encoder *e = find(id);
    int size = e && !e->flushed ? lame_encode_flush(e->lame, bytes, MP3_BUFFER) : -1;
    if (e) e->flushed = 1; pthread_mutex_unlock(&mutex);
    if (size < 0 || size > MP3_BUFFER) { error(env, "MP3 flush failed"); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env,size); if (out && size) (*env)->SetByteArrayRegion(env,out,0,size,(jbyte *)bytes); return out;
}
JNIEXPORT jbyteArray JNICALL Java_app_guidecast_transmitter_LameMp3Native_tag(JNIEnv *env, jobject self, jlong id) {
    (void)self; unsigned char bytes[MP3_BUFFER];
    pthread_mutex_lock(&mutex); Encoder *e = find(id);
    size_t size = e && e->flushed ? lame_get_lametag_frame(e->lame, bytes, MP3_BUFFER) : 0;
    pthread_mutex_unlock(&mutex);
    if (!size || size > MP3_BUFFER) { error(env, "MP3 duration tag unavailable"); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env,(jsize)size); if (out) (*env)->SetByteArrayRegion(env,out,0,(jsize)size,(jbyte *)bytes); return out;
}
JNIEXPORT void JNICALL Java_app_guidecast_transmitter_LameMp3Native_close(JNIEnv *env, jobject self, jlong id) {
    (void)env; (void)self; pthread_mutex_lock(&mutex);
    Encoder **link = &encoders;
    while (*link && (*link)->id != id) link = &(*link)->next;
    if (*link) { Encoder *e = *link; *link = e->next; lame_close(e->lame); free(e); }
    pthread_mutex_unlock(&mutex);
}
JNIEXPORT jstring JNICALL Java_app_guidecast_transmitter_LameMp3Native_version(JNIEnv *env, jobject self) {
    (void)self; return (*env)->NewStringUTF(env, get_lame_version());
}
