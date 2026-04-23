#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "TunnelJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *jvm = NULL;
static jmethodID logCallbackMethod = NULL;
static jobject tunnelManagerObj = NULL;

typedef int (*start_tunnel_func)(const char*, void (*)(const char*));

// Callback function called from Go
void log_callback(const char* message) {
    if (jvm == NULL || logCallbackMethod == NULL || tunnelManagerObj == NULL) {
        LOGI("Log callback not set: %s", message);
        return;
    }

    JNIEnv *env;
    (*jvm)->AttachCurrentThread(jvm, &env, NULL);

    jstring jMessage = (*env)->NewStringUTF(env, message);
    (*env)->CallVoidMethod(env, tunnelManagerObj, logCallbackMethod, jMessage);
    (*env)->DeleteLocalRef(env, jMessage);

    (*jvm)->DetachCurrentThread(jvm);
}

JNIEXPORT void JNICALL Java_com_ismartcoding_plain_tunnel_TunnelManager_setLogCallback(JNIEnv *env, jobject obj) {
    (*env)->GetJavaVM(env, &jvm);
    jclass clazz = (*env)->GetObjectClass(env, obj);
    logCallbackMethod = (*env)->GetMethodID(env, clazz, "onNativeLog", "(Ljava/lang/String;)V");
    tunnelManagerObj = (*env)->NewGlobalRef(env, obj);
}

JNIEXPORT jint JNICALL Java_com_ismartcoding_plain_tunnel_TunnelManager_startTunnel(JNIEnv *env, jobject obj, jstring token) {
    LOGI("Starting tunnel via JNI");

    // Load the cloudflared library
    void *handle = dlopen("libcloudflared.so", RTLD_LAZY);
    if (!handle) {
        LOGE("Failed to load libcloudflared.so: %s", dlerror());
        return -1;
    }

    // Get the function pointer
    start_tunnel_func func = (start_tunnel_func)dlsym(handle, "start_tunnel");
    if (!func) {
        LOGE("Failed to find start_tunnel function: %s", dlerror());
        dlclose(handle);
        return -1;
    }

    // Extract token string
    const char *token_str = (*env)->GetStringUTFChars(env, token, 0);
    if (!token_str) {
        LOGE("Failed to get token string");
        dlclose(handle);
        return -1;
    }

    LOGI("Calling start_tunnel with token and callback");
    int result = func(token_str, log_callback);

    // Release string
    (*env)->ReleaseStringUTFChars(env, token, token_str);

    // Close library
    dlclose(handle);

    LOGI("Tunnel start result: %d", result);
    return result;
}