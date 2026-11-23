#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" JNIEXPORT jlong JNICALL
Java_com_omarea_vtools_SceneJNI_getKernelPropLong(
        JNIEnv *env,
        jobject,
        jstring path) {
    if (path == nullptr) {
        return -1;
    }

    const char *utfPath = env->GetStringUTFChars(path, nullptr);
    if (utfPath == nullptr) {
        return -1;
    }

    FILE *kernelProp = std::fopen(utfPath, "r");
    env->ReleaseStringUTFChars(path, utfPath);

    if (kernelProp == nullptr) {
        return -1;
    }

    long freq = -1;
    if (std::fscanf(kernelProp, "%ld", &freq) != 1) {
        freq = -1;
    }
    std::fclose(kernelProp);
    return static_cast<jlong>(freq);
}
