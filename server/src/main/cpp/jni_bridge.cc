#include <jni.h>

#include <string>

#include "scrcpy_opus_audio_encoder.h"
#include "scrcpy_passthrough_encoder.h"
#include "scrcpy_peer_connection.h"

namespace {

JavaVM* g_jvm = nullptr;
jobject g_callback = nullptr;
jmethodID g_on_bitrate = nullptr;
jmethodID g_on_key_frame = nullptr;

void BridgeOnBitrate(int bps, double fps) {
    if (g_jvm == nullptr || g_callback == nullptr || g_on_bitrate == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    env->CallVoidMethod(g_callback, g_on_bitrate, static_cast<jint>(bps), static_cast<jdouble>(fps));
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

void BridgeOnKeyFrameRequest() {
    if (g_jvm == nullptr || g_callback == nullptr || g_on_key_frame == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    env->CallVoidMethod(g_callback, g_on_key_frame);
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

jobject g_signal_callback = nullptr;
jmethodID g_on_answer = nullptr;
jmethodID g_on_ice = nullptr;

void BridgeOnAnswer(const std::string& sdp) {
    if (g_jvm == nullptr || g_signal_callback == nullptr || g_on_answer == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jstring jsdp = env->NewStringUTF(sdp.c_str());
    env->CallVoidMethod(g_signal_callback, g_on_answer, jsdp);
    env->DeleteLocalRef(jsdp);
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

void BridgeOnIceCandidate(const std::string& sdp_mid, int mline_index, const std::string& sdp) {
    if (g_jvm == nullptr || g_signal_callback == nullptr || g_on_ice == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jstring jmid = env->NewStringUTF(sdp_mid.c_str());
    jstring jsdp = env->NewStringUTF(sdp.c_str());
    env->CallVoidMethod(g_signal_callback, g_on_ice, jmid, static_cast<jint>(mline_index), jsdp);
    env->DeleteLocalRef(jmid);
    env->DeleteLocalRef(jsdp);
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

jobject g_data_callback = nullptr;
jmethodID g_on_data = nullptr;

void BridgeOnData(const uint8_t* data, size_t len) {
    if (g_jvm == nullptr || g_data_callback == nullptr || g_on_data == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jbyteArray jdata = env->NewByteArray(static_cast<jsize>(len));
    if (jdata == nullptr) {
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
        return;
    }
    env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(g_data_callback, g_on_data, jdata);
    env->DeleteLocalRef(jdata);
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

jlong toJlong(void* ptr) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ptr));
}

template <typename T>
T* fromJlong(jlong handle) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeSetCallbacks(JNIEnv* env, jobject thiz, jobject callback) {
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
        g_on_bitrate = nullptr;
        g_on_key_frame = nullptr;
    }
    if (callback != nullptr) {
        g_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_bitrate = env->GetMethodID(cls, "onBitrate", "(ID)V");
        g_on_key_frame = env->GetMethodID(cls, "onKeyFrameRequest", "()V");
    }
    scrcpy::SetGlobalCallbacks(BridgeOnBitrate, BridgeOnKeyFrameRequest);
}

JNIEXPORT jlong JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeCreateTrackSource(JNIEnv* env, jobject thiz) {
    return toJlong(webrtc::make_ref_counted<scrcpy::EncodedVideoTrackSource>().release());
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeDestroyTrackSource(JNIEnv* env, jobject thiz, jlong handle) {
    auto* source = fromJlong<scrcpy::EncodedVideoTrackSource>(handle);
    if (source != nullptr) {
        source->Release();
    }
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativePushEncodedVideoFrame(
        JNIEnv* env, jobject thiz, jlong handle, jbyteArray data, jlong ptsUs, jboolean config, jboolean keyFrame, jint width, jint height) {
    auto* source = fromJlong<scrcpy::EncodedVideoTrackSource>(handle);
    if (source == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(data);
    jbyte* elements = env->GetByteArrayElements(data, nullptr);
    if (elements == nullptr) {
        return;
    }
    source->OnEncodedFrame(reinterpret_cast<const uint8_t*>(elements), static_cast<size_t>(len), static_cast<int64_t>(ptsUs),
            config == JNI_TRUE, keyFrame == JNI_TRUE, width, height);
    env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
}

JNIEXPORT jlong JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeCreateAudioEncoder(JNIEnv* env, jobject thiz) {
    return toJlong(new scrcpy::ScrcpyOpusAudioEncoder());
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeDestroyAudioEncoder(JNIEnv* env, jobject thiz, jlong handle) {
    delete fromJlong<scrcpy::ScrcpyOpusAudioEncoder>(handle);
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativePushEncodedAudioFrame(
        JNIEnv* env, jobject thiz, jlong handle, jbyteArray data, jlong ptsUs) {
    auto* encoder = fromJlong<scrcpy::ScrcpyOpusAudioEncoder>(handle);
    if (encoder == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(data);
    jbyte* elements = env->GetByteArrayElements(data, nullptr);
    if (elements == nullptr) {
        return;
    }
    encoder->OnEncodedFrame(reinterpret_cast<const uint8_t*>(elements), static_cast<size_t>(len), static_cast<int64_t>(ptsUs));
    env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeSetSignalCallback(JNIEnv* env, jobject thiz, jobject callback) {
    if (g_signal_callback != nullptr) {
        env->DeleteGlobalRef(g_signal_callback);
        g_signal_callback = nullptr;
        g_on_answer = nullptr;
        g_on_ice = nullptr;
    }
    if (callback != nullptr) {
        g_signal_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_answer = env->GetMethodID(cls, "onAnswer", "(Ljava/lang/String;)V");
        g_on_ice = env->GetMethodID(cls, "onIceCandidate", "(Ljava/lang/String;ILjava/lang/String;)V");
    }
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeSetDataCallback(JNIEnv* env, jobject thiz, jobject callback) {
    if (g_data_callback != nullptr) {
        env->DeleteGlobalRef(g_data_callback);
        g_data_callback = nullptr;
        g_on_data = nullptr;
    }
    if (callback != nullptr) {
        g_data_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_data = env->GetMethodID(cls, "onData", "([B)V");
    }
}

JNIEXPORT jlong JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeCreatePeerConnection(JNIEnv* env, jobject thiz, jlong trackSourceHandle) {
    auto* source = fromJlong<scrcpy::EncodedVideoTrackSource>(trackSourceHandle);
    if (source == nullptr) {
        return 0;
    }
    webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> source_ref(source);
    auto* pc = new scrcpy::ScrcpyPeerConnection();
    if (!pc->Initialize(source_ref)) {
        delete pc;
        return 0;
    }
    pc->SetAnswerCallback(BridgeOnAnswer);
    pc->SetIceCandidateCallback(BridgeOnIceCandidate);
    pc->SetDataChannelCallback(BridgeOnData);
    return toJlong(pc);
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeDestroyPeerConnection(JNIEnv* env, jobject thiz, jlong handle) {
    delete fromJlong<scrcpy::ScrcpyPeerConnection>(handle);
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeOnOffer(JNIEnv* env, jobject thiz, jlong handle, jstring sdp) {
    auto* pc = fromJlong<scrcpy::ScrcpyPeerConnection>(handle);
    if (pc == nullptr) {
        return;
    }
    const char* sdp_c = env->GetStringUTFChars(sdp, nullptr);
    pc->OnOffer(sdp_c);
    env->ReleaseStringUTFChars(sdp, sdp_c);
}

JNIEXPORT void JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeOnIceCandidate(
        JNIEnv* env, jobject thiz, jlong handle, jstring sdpMid, jint mlineIndex, jstring sdp) {
    auto* pc = fromJlong<scrcpy::ScrcpyPeerConnection>(handle);
    if (pc == nullptr) {
        return;
    }
    const char* mid_c = env->GetStringUTFChars(sdpMid, nullptr);
    const char* sdp_c = env->GetStringUTFChars(sdp, nullptr);
    pc->OnIceCandidate(mid_c, mlineIndex, sdp_c);
    env->ReleaseStringUTFChars(sdpMid, mid_c);
    env->ReleaseStringUTFChars(sdp, sdp_c);
}

JNIEXPORT jboolean JNICALL Java_com_genymobile_scrcpy_device_NativeEncoderBridge_nativeSendData(
        JNIEnv* env, jobject thiz, jlong handle, jbyteArray data) {
    auto* pc = fromJlong<scrcpy::ScrcpyPeerConnection>(handle);
    if (pc == nullptr) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    jbyte* elements = env->GetByteArrayElements(data, nullptr);
    if (elements == nullptr) {
        return JNI_FALSE;
    }
    bool ok = pc->SendData(reinterpret_cast<const uint8_t*>(elements), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}
