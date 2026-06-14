#include "NativeCameraBridgeListener.h"
#include "JavaUtils.h"

#include "camera/Logger.h"

namespace motioncam {
    NativeCameraBridgeListener::NativeCameraBridgeListener(JNIEnv* env, jobject listener) :
        mJavaVm(nullptr) {

        if (env->GetJavaVM(&mJavaVm) != 0) {
            LOGE("Failed to get Java VM!");
            throw std::runtime_error("Failed to obtain java vm");
        }

        mListenerInstance = env->NewGlobalRef(listener);
        if(!mListenerInstance)
            throw std::runtime_error("Failed to get listener instance reference");

        jobject listenerClass = env->GetObjectClass(mListenerInstance);
        if(!listenerClass)
            throw std::runtime_error("Failed to get listener class");

        mListenerClass = reinterpret_cast<jclass>(env->NewGlobalRef(listenerClass));
        if(!mListenerClass)
            throw std::runtime_error("Failed to get listener class reference");
    }

    NativeCameraBridgeListener::~NativeCameraBridgeListener() {
        // Acquire the write lock: this blocks until every in-flight callback
        // (which holds a shared/read lock) has returned, so no callback can
        // touch the GlobalRefs after we start deleting them.
        std::unique_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("~NativeCameraBridgeListener() no environment");
            return;
        }

        if(mListenerClass)
            env.getEnv()->DeleteGlobalRef(mListenerClass);

        if(mListenerInstance)
            env.getEnv()->DeleteGlobalRef(mListenerInstance);

        mListenerClass = nullptr;
        mListenerInstance = nullptr;
    }

    void NativeCameraBridgeListener::onCameraStateChanged(const CameraCaptureSessionState state) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraSessionStateChanged()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraSessionStateChanged", "(I)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, (int) state);
    }

    void NativeCameraBridgeListener::onCameraError(int error) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraError()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraError", "(I)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, error);
    }

    void NativeCameraBridgeListener::onCameraDisconnected() {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraDisconnected()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraDisconnected", "()V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod);
    }

    void NativeCameraBridgeListener::onCameraExposureStatus(const int32_t iso, const int64_t exposureTime) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraExposureStatus()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraExposureStatus", "(IJ)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, iso, exposureTime);
    }

    void NativeCameraBridgeListener::onCameraAutoFocusStateChanged(const CameraFocusState state) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraAutoFocusStateChanged()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraAutoFocusStateChanged", "(I)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, (int) state);
    }

    void NativeCameraBridgeListener::onCameraAutoExposureStateChanged(const CameraExposureState state) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraAutoExposureStateChanged()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraAutoExposureStateChanged", "(I)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, (int) state);
    }

    void NativeCameraBridgeListener::onCameraHdrImageCaptureProgress(int progress) {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraHdrImageCaptureProgress()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraHdrImageCaptureProgress", "(I)V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod, progress);
    }

    void NativeCameraBridgeListener::onCameraHdrImageCaptureCompleted() {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraHdrImageCaptureCompleted()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraHdrImageCaptureCompleted", "()V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod);
    }

    void NativeCameraBridgeListener::onCameraHdrImageCaptureFailed() {
        std::shared_lock<std::shared_mutex> lock(mCallbackMutex);

        JavaEnv env(mJavaVm);
        if (!env.getEnv()) {
            LOGE("Dropped onCameraHdrImageCaptureFailed()");
            return;
        }

        if(!mListenerClass || !mListenerInstance)
            return;

        jmethodID callbackMethod = env.getEnv()->GetMethodID(mListenerClass, "onCameraHdrImageCaptureFailed", "()V");
        if(callbackMethod)
            env.getEnv()->CallVoidMethod(mListenerInstance, callbackMethod);
    }
}