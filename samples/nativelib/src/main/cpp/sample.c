#include <jni.h>

#if defined(__aarch64__)
#define ABI "arm64-v8a"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__arm__)
#define ABI "armeabi-v7a"
#elif defined(__i386__)
#define ABI "x86"
#else
#define ABI "unknown"
#endif

JNIEXPORT jstring JNICALL
Java_com_clonsapps_sample_nativelib_MainActivity_nativeHello(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "ok:" ABI);
}
