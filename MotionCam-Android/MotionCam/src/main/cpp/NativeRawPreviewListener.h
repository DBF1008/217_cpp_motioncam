#ifndef MOTIONCAM_ANDROID_NATIVERAWPREVIEWLISTENER_H
#define MOTIONCAM_ANDROID_NATIVERAWPREVIEWLISTENER_H

#include <jni.h>
#include <android/bitmap.h>
#include <shared_mutex>

#include "camera/RawPreviewListener.h"

namespace motioncam {
    class NativeRawPreviewListener : public RawPreviewListener {

    public:
        NativeRawPreviewListener(JNIEnv *env, jobject listener);
        ~NativeRawPreviewListener();

        void onPreviewGenerated(const void* data, const int len, const int width, const int height);

    private:
        JavaVM *mJavaVm;
        jobject mListenerInstance;
        jclass mListenerClass;
        jobject mBitmap;

        // Guards against use-after-free: callbacks take a shared (read) lock
        // so they can run concurrently; the destructor takes a unique (write)
        // lock to wait for every in-flight callback to finish before deleting
        // the JNI GlobalRefs.
        mutable std::shared_mutex mCallbackMutex;
    };

} // namespace motioncam

#endif //MOTIONCAM_ANDROID_NATIVERAWPREVIEWLISTENER_H
