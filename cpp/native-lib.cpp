#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "B.b.Renderer"

extern "C" JNIEXPORT void JNICALL
Java_com_bb_renderer_core_NativeRenderer_testConnection(JNIEnv* env, jobject /* this */) {
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Native Bridge Active");
}

extern "C" JNIEXPORT void JNICALL
Java_com_bb_renderer_core_NativeRenderer_applyHtxmPatch(
        JNIEnv* env, jobject /* this */, jint patchId, jfloatArray data) {
    // GPUバッファ操作の筋肉
    // glBufferSubData(...) 等の実装をここに追加
}
