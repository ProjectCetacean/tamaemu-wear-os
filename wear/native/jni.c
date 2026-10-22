#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include "backend.h"
/* The Java contract serializes every call on one worker. Opaque monotonically
 * assigned tokens ensure a stale Java handle never dereferences freed memory. */
typedef struct Handle {jlong token;WearBackend *backend;struct Handle *next;} Handle;
static Handle *handles;
static jlong next_token=1;
static void jni_throw_exception(JNIEnv *env,const char *type,const char *message) {
    jclass cls=(*env)->FindClass(env,type);if(cls) (*env)->ThrowNew(env,cls,message);
}
static WearBackend *lookup(JNIEnv *env,jlong token) {
    for(Handle *p=handles;p;p=p->next) if(p->token==token) return p->backend;
    jni_throw_exception(env,"java/lang/IllegalArgumentException","Invalid or closed emulator handle");return NULL;
}
#define JNI_NAME(name) Java_io_github_projectcetacean_tamaemu_NativeBridge_##name
JNIEXPORT jlong JNICALL JNI_NAME(open)(JNIEnv *env,jclass cls,jstring rom,jstring save) {
    (void)cls;
    if(!rom || !save) {jni_throw_exception(env,"java/lang/IllegalArgumentException","Missing local firmware or save destination");return 0;}
    const char *rp=(*env)->GetStringUTFChars(env,rom,NULL);if(!rp) return 0;
    const char *sp=(*env)->GetStringUTFChars(env,save,NULL);
    if(!sp) {(*env)->ReleaseStringUTFChars(env,rom,rp);return 0;}
    char error[160];WearBackend *b=wear_open(rp,sp,error);
    (*env)->ReleaseStringUTFChars(env,save,sp);(*env)->ReleaseStringUTFChars(env,rom,rp);
    if(!b) {jni_throw_exception(env,"java/io/IOException",error);return 0;}
    Handle *h=malloc(sizeof *h);
    if(!h || next_token==INT64_MAX) {free(h);wear_close(b);jni_throw_exception(env,"java/lang/OutOfMemoryError","Emulator handle allocation failed");return 0;}
    h->token=next_token++;h->backend=b;h->next=handles;handles=h;return h->token;
}
JNIEXPORT void JNICALL JNI_NAME(close)(JNIEnv *env,jclass cls,jlong token) {
    (void)cls;Handle **p=&handles;
    while(*p) {
        Handle *h=*p;
        if(h->token==token) {*p=h->next;wear_close(h->backend);free(h);return;}
        p=&h->next;
    }
    jni_throw_exception(env,"java/lang/IllegalArgumentException","Invalid or closed emulator handle");
}
JNIEXPORT jint JNICALL JNI_NAME(width)(JNIEnv *env,jclass cls,jlong token) {(void)cls;return lookup(env,token)?PANEL_W:0;}
JNIEXPORT jint JNICALL JNI_NAME(height)(JNIEnv *env,jclass cls,jlong token) {(void)cls;return lookup(env,token)?PANEL_H:0;}
JNIEXPORT void JNICALL JNI_NAME(setAudioEnabled)(JNIEnv *env,jclass cls,jlong token,jboolean enabled) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return;
    wear_audio_set_enabled(b,enabled?1:0);
}
JNIEXPORT jshortArray JNICALL JNI_NAME(drainAudio)(JNIEnv *env,jclass cls,jlong token,jint capacity) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return NULL;
    if(capacity<0 || capacity>9600) {
        jni_throw_exception(env,"java/lang/IllegalArgumentException","Audio capacity must be within 0..9600 samples");return NULL;
    }
    int16_t samples[9600];size_t n=wear_audio_drain(b,samples,(size_t)capacity);
    jshortArray out=(*env)->NewShortArray(env,(jsize)n);
    if(out && n) (*env)->SetShortArrayRegion(env,out,0,(jsize)n,(const jshort *)samples);
    return out;
}
JNIEXPORT void JNICALL JNI_NAME(advance)(JNIEnv *env,jclass cls,jlong token,jint ms,jintArray argb) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return;
    if(!argb || (*env)->GetArrayLength(env,argb)!=PANEL_W*PANEL_H || ms<0) {
        jni_throw_exception(env,"java/lang/IllegalArgumentException","Invalid frame buffer or elapsed time");return;
    }
    uint32_t *frame=malloc(PANEL_W*PANEL_H*sizeof *frame);
    if(!frame) {jni_throw_exception(env,"java/lang/OutOfMemoryError","Frame allocation failed");return;}
    char error[160];
    if(wear_advance(b,ms,frame,PANEL_W*PANEL_H,error)) (*env)->SetIntArrayRegion(env,argb,0,PANEL_W*PANEL_H,(const jint *)frame);
    else jni_throw_exception(env,"java/io/IOException",error);
    free(frame);
}
JNIEXPORT void JNICALL JNI_NAME(advanceHeadless)(JNIEnv *env,jclass cls,jlong token,jint ms) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return;
    if(ms<0 || ms>100) {jni_throw_exception(env,"java/lang/IllegalArgumentException","Headless elapsed time must be within 0..100ms");return;}
    char error[160];if(!wear_advance_headless(b,ms,error)) jni_throw_exception(env,"java/io/IOException",error);
}
/* This API groups existing literal operations; it never widens the backend quantum.
 * At least one operation completes on success. A failure, including a failed clock,
 * throws without acknowledging a prefix: the owner must not save unknown guest state.
 * The nominal wall window excludes scheduling/preemption and one in-flight operation.
 */
JNIEXPORT jint JNICALL JNI_NAME(advanceHeadlessBatch)(JNIEnv *env,jclass cls,jlong token,jint requested) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return 0;
    if(requested<=0 || requested>60000) {
        jni_throw_exception(env,"java/lang/IllegalArgumentException","Replay request must be within 1..60000ms");return 0;
    }
    struct timespec start,now;
    if(clock_gettime(CLOCK_MONOTONIC,&start)!=0) {
        jni_throw_exception(env,"java/io/IOException","Replay monotonic clock unavailable");return 0;
    }
    jint completed=0;char error[160];
    do {
        jint step=requested-completed;if(step>100)step=100;
        if(!wear_advance_headless(b,step,error)) {jni_throw_exception(env,"java/io/IOException",error);return 0;}
        completed+=step;
        if(completed==requested)return completed;
        if(clock_gettime(CLOCK_MONOTONIC,&now)!=0) {
            jni_throw_exception(env,"java/io/IOException","Replay monotonic clock unavailable");return 0;
        }
    } while((int64_t)(now.tv_sec-start.tv_sec)*INT64_C(1000000000)+now.tv_nsec-start.tv_nsec<INT64_C(2000000));
    return completed;
}
JNIEXPORT jlongArray JNICALL JNI_NAME(readLife)(JNIEnv *env,jclass cls,jlong token) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return NULL;
    uint64_t fields[8];if(!wear_read_life(b,fields)) {jni_throw_exception(env,"java/lang/IllegalStateException","Cannot read life metadata");return NULL;}
    jlong values[8];for(int i=0;i<8;i++) values[i]=(jlong)fields[i];
    jlongArray out=(*env)->NewLongArray(env,8);if(out) (*env)->SetLongArrayRegion(env,out,0,8,values);return out;
}
JNIEXPORT void JNICALL JNI_NAME(writeLife)(JNIEnv *env,jclass cls,jlong token,jlongArray candidate) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return;
    if(!candidate || (*env)->GetArrayLength(env,candidate)!=8) {jni_throw_exception(env,"java/lang/IllegalArgumentException","Life metadata requires exactly eight fields");return;}
    jlong values[8];(*env)->GetLongArrayRegion(env,candidate,0,8,values);if((*env)->ExceptionCheck(env)) return;
    uint64_t fields[8];for(int i=0;i<8;i++) {
        if(values[i]<0) {jni_throw_exception(env,"java/lang/IllegalArgumentException","Life metadata must be nonnegative");return;}
        fields[i]=(uint64_t)values[i];
    }
    char error[160];if(!wear_write_life(b,fields,error)) jni_throw_exception(env,"java/lang/IllegalArgumentException",error);
}
JNIEXPORT jbyteArray JNICALL JNI_NAME(committedPayloadHash)(JNIEnv *env,jclass cls,jlong token) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return NULL;
    uint8_t hash[32];if(!wear_committed_payload_hash(b,hash)) return NULL;
    jbyteArray out=(*env)->NewByteArray(env,32);if(out) (*env)->SetByteArrayRegion(env,out,0,32,(const jbyte *)hash);return out;
}
JNIEXPORT void JNICALL JNI_NAME(setButton)(JNIEnv *env,jclass cls,jlong token,jint button,jboolean pressed) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return;
    if(!wear_button(b,button,pressed)) jni_throw_exception(env,"java/lang/IllegalArgumentException","Invalid button");
}
JNIEXPORT jboolean JNICALL JNI_NAME(save)(JNIEnv *env,jclass cls,jlong token) {
    (void)cls;WearBackend *b=lookup(env,token);if(!b) return JNI_FALSE;
    char error[160];return wear_save(b,error)?JNI_TRUE:JNI_FALSE;
}
