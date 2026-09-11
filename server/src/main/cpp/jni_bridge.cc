#include <jni.h>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include "scrcpy_passthrough_encoder.h"
#include "scrcpy_peer_connection.h"
namespace {
JavaVM* jvm = nullptr;
class Env {
public:
    Env() {
        if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = jvm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~Env() { if (attached) { jvm->DetachCurrentThread(); } }
    JNIEnv* env = nullptr;
    bool attached = false;
};
// Per-session reference; local references protect in-flight callbacks at close.
class Callbacks {
public:
    Callbacks(JNIEnv* env, jobject bridge) : bridge_(env->NewGlobalRef(bridge)) {
        auto cls = env->GetObjectClass(bridge);
        bitrate = env->GetMethodID(cls, "onNativeBitrate", "(ID)V");
        keyframe = env->GetMethodID(cls, "onNativeKeyFrame", "()V");
        answer = env->GetMethodID(cls, "onNativeAnswer", "(Ljava/lang/String;)V");
        ice = env->GetMethodID(cls, "onNativeIce", "(Ljava/lang/String;ILjava/lang/String;)V");
        data = env->GetMethodID(cls, "onNativeData", "([B)V");
        closed = env->GetMethodID(cls, "onNativeClosed", "()V");
        env->DeleteLocalRef(cls);
    }
    ~Callbacks() { Clear(); }
    void Clear() {
        Env scope;
        std::lock_guard<std::mutex> lock(mutex_);
        if (scope.env && bridge_) { scope.env->DeleteGlobalRef(bridge_); bridge_ = nullptr; }
    }
    void Call(std::function<void(JNIEnv*, jobject)> fn) {
        Env scope;
        if (!scope.env) { return; }
        jobject target;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            target = bridge_ ? scope.env->NewLocalRef(bridge_) : nullptr;
        }
        if (!target) { return; }
        fn(scope.env, target);
        if (scope.env->ExceptionCheck()) { scope.env->ExceptionClear(); }
        scope.env->DeleteLocalRef(target);
    }
    jmethodID bitrate, keyframe, answer, ice, data, closed;
private:
    std::mutex mutex_;
    jobject bridge_;
};
struct Session {
    std::shared_ptr<Callbacks> callbacks;
    webrtc::scoped_refptr<scrcpy::EncodedVideoTrackSource> video;
    std::unique_ptr<scrcpy::ScrcpyPeerConnection> pc;
    ~Session() { callbacks->Clear(); pc.reset(); video = nullptr; }
};
Session* Get(jlong handle) { return reinterpret_cast<Session*>(static_cast<intptr_t>(handle)); }
std::string String(JNIEnv* env, jstring value) {
    if (!value) { return {}; }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) { return {}; }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}
extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) { jvm = vm; return JNI_VERSION_1_6; }
JNIEXPORT jlong JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeCreate(
        JNIEnv* env, jobject bridge, jboolean audio, jstring turn, jstring user, jstring password) {
    auto session = std::make_unique<Session>();
    auto cb = std::make_shared<Callbacks>(env, bridge);
    session->callbacks = cb;
    session->video = webrtc::make_ref_counted<scrcpy::EncodedVideoTrackSource>(
            [cb] { cb->Call([&](JNIEnv* e, jobject o) { e->CallVoidMethod(o, cb->keyframe); }); });
    session->pc = std::make_unique<scrcpy::ScrcpyPeerConnection>();
    if (!session->pc->Initialize(session->video, audio, String(env, turn), String(env, user), String(env, password),
            [cb](int bps, double fps) { cb->Call([&](JNIEnv* e, jobject o) { e->CallVoidMethod(o, cb->bitrate, bps, fps); }); },
            [cb] { cb->Call([&](JNIEnv* e, jobject o) { e->CallVoidMethod(o, cb->keyframe); }); })) { return 0; }
    session->pc->SetAnswerCallback([cb](const std::string& sdp) {
        cb->Call([&](JNIEnv* e, jobject o) {
            auto value = e->NewStringUTF(sdp.c_str());
            e->CallVoidMethod(o, cb->answer, value); e->DeleteLocalRef(value);
        });
    });
    session->pc->SetIceCandidateCallback([cb](const std::string& mid, int index, const std::string& sdp) {
        cb->Call([&](JNIEnv* e, jobject o) {
            auto m = e->NewStringUTF(mid.c_str()); auto s = e->NewStringUTF(sdp.c_str());
            e->CallVoidMethod(o, cb->ice, m, index, s);
            e->DeleteLocalRef(m); e->DeleteLocalRef(s);
        });
    });
    session->pc->SetDataChannelCallback([cb](const uint8_t* bytes, size_t size) {
        if (size > 262144) { return; }
        cb->Call([&](JNIEnv* e, jobject o) {
            auto data = e->NewByteArray(static_cast<jsize>(size));
            if (!data) { return; }
            e->SetByteArrayRegion(data, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(bytes));
            e->CallVoidMethod(o, cb->data, data); e->DeleteLocalRef(data);
        });
    });
    session->pc->SetClosedCallback([cb] { cb->Call([&](JNIEnv* e, jobject o) { e->CallVoidMethod(o, cb->closed); }); });
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
}
JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete Get(handle);
}
JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativePushVideo(
        JNIEnv* env, jobject, jlong handle, jbyteArray data, jlong pts, jboolean config, jboolean key, jint width, jint height) {
    auto* s = Get(handle); if (!s) { return; }
    auto size = env->GetArrayLength(data);
    auto* bytes = env->GetByteArrayElements(data, nullptr); if (!bytes) { return; }
    s->video->OnEncodedFrame(reinterpret_cast<const uint8_t*>(bytes), size, pts, config, key, width, height);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}
JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativePushPcm(
        JNIEnv* env, jobject, jlong handle, jbyteArray data, jlong pts) {
    auto* s = Get(handle); if (!s) { return; }
    auto size = env->GetArrayLength(data);
    auto* bytes = env->GetByteArrayElements(data, nullptr); if (!bytes) { return; }
    s->pc->PushAudio(reinterpret_cast<const uint8_t*>(bytes), size, pts);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}
JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeOffer(
        JNIEnv* env, jobject, jlong handle, jstring sdp) {
    auto* s = Get(handle); if (s) { s->pc->OnOffer(String(env, sdp)); }
}
JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeIce(
        JNIEnv* env, jobject, jlong handle, jstring mid, jint index, jstring sdp) {
    auto* s = Get(handle); if (s) { s->pc->OnIceCandidate(String(env, mid), index, String(env, sdp)); }
}
JNIEXPORT jboolean JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeSend(
        JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    auto* s = Get(handle); if (!s) { return JNI_FALSE; }
    auto size = env->GetArrayLength(data);
    auto* bytes = env->GetByteArrayElements(data, nullptr); if (!bytes) { return JNI_FALSE; }
    bool sent = s->pc->SendData(reinterpret_cast<const uint8_t*>(bytes), size);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return sent ? JNI_TRUE : JNI_FALSE;
}
}
