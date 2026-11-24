#include <jni.h>
#include <string>
#include <atomic>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ChipITLlama", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeLoadModel(
        JNIEnv *env, jobject /* thiz */,
        jstring model_path, jint threads, jint gpu_layers,
        jint context_len, jboolean use_vulkan, jboolean use_opencl) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("nativeLoadModel path=%s threads=%d gpuLayers=%d ctx=%d vulkan=%d opencl=%d",
         path, threads, gpu_layers, context_len, use_vulkan, use_opencl);
    env->ReleaseStringUTFChars(model_path, path);
    // Stub handle when llama.cpp submodule is not yet attached
    return 0L;
}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeGenerate(
        JNIEnv *env, jobject /* thiz */,
        jlong /* handle */, jstring /* prompt */,
        jint /* max_tokens */, jfloat /* temperature */, jfloat /* top_p */,
        jint /* top_k */, jfloat /* repeat_penalty */, jobject /* callback */) {
    // Wired to llama_decode loop when CHIPIT_HAS_LLAMA=1
}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeCancel(JNIEnv * /* env */, jobject /* thiz */, jlong /* handle */) {}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeUnload(JNIEnv * /* env */, jobject /* thiz */, jlong /* handle */) {}

JNIEXPORT jboolean JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeSupportsVulkan(JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_FALSE;
}

}
