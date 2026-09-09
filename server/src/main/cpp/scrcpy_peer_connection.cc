#include "scrcpy_peer_connection.h"

#include "api/audio/builtin_audio_processing_builder.h"
#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/enable_media.h"
#include "api/environment/environment_factory.h"
#include "api/jsep.h"
#include "api/set_local_description_observer_interface.h"
#include "api/set_remote_description_observer_interface.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "pc/peer_connection_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "scrcpy_passthrough_encoder.h"
#include "scrcpy_video_encoder_factory.h"

#include <utility>

#include <android/log.h>

#define SCP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "scrcpy_native", __VA_ARGS__)

namespace scrcpy {

namespace {

class AnswerObserver : public webrtc::CreateSessionDescriptionObserver {
public:
    explicit AnswerObserver(ScrcpyPeerConnection* owner) : owner_(owner), lifetime_(owner->lifetime()) {
    }

    void OnSuccess(webrtc::SessionDescriptionInterface* desc) override {
        if (lifetime_.expired()) { delete desc; return; }
        owner_->OnAnswerCreated(std::unique_ptr<webrtc::SessionDescriptionInterface>(desc));
    }

    void OnFailure(webrtc::RTCError error) override {
        if (lifetime_.expired()) { return; }
        owner_->OnConnectionClosed();
    }

private:
    ScrcpyPeerConnection* owner_;
    std::weak_ptr<bool> lifetime_;
};

class RemoteDescriptionObserver : public webrtc::SetRemoteDescriptionObserverInterface {
public:
    explicit RemoteDescriptionObserver(ScrcpyPeerConnection* owner) : owner_(owner), lifetime_(owner->lifetime()) {
    }

    void OnSetRemoteDescriptionComplete(webrtc::RTCError error) override {
        if (lifetime_.expired()) { return; }
        if (error.ok()) {
            owner_->OnRemoteDescriptionSet();
        } else { owner_->OnConnectionClosed(); }
    }

private:
    ScrcpyPeerConnection* owner_;
    std::weak_ptr<bool> lifetime_;
};

class LocalDescriptionObserver : public webrtc::SetLocalDescriptionObserverInterface {
public:
    explicit LocalDescriptionObserver(ScrcpyPeerConnection* owner) : owner_(owner), lifetime_(owner->lifetime()) {
    }

    void OnSetLocalDescriptionComplete(webrtc::RTCError error) override {
        if (lifetime_.expired()) { return; }
        if (error.ok()) {
            owner_->OnLocalDescriptionSet();
        } else { owner_->OnConnectionClosed(); }
    }

private:
    ScrcpyPeerConnection* owner_;
    std::weak_ptr<bool> lifetime_;
};

class PeerObserver : public webrtc::PeerConnectionObserver {
public:
    explicit PeerObserver(ScrcpyPeerConnection* owner) : owner_(owner), lifetime_(owner->lifetime()) {
    }

    void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState new_state) override {
    }

    void OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel) override {
        if (!lifetime_.expired()) { owner_->OnDataChannelOpened(data_channel); }
    }

    void OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState state) override {
        if (!lifetime_.expired() && state == webrtc::PeerConnectionInterface::PeerConnectionState::kFailed) {
            owner_->OnConnectionClosed();
        }
    }
    void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState new_state) override {
    }

    void OnIceCandidate(const webrtc::IceCandidate* candidate) override {
        if (!lifetime_.expired() && candidate != nullptr) {
            owner_->OnIceCandidateGathered(candidate->sdp_mid(), candidate->sdp_mline_index(), candidate->ToString());
        }
    }

private:
    ScrcpyPeerConnection* owner_;
    std::weak_ptr<bool> lifetime_;
};

}

class DataChannelObserver : public webrtc::DataChannelObserver {
public:
    explicit DataChannelObserver(ScrcpyPeerConnection* owner) : owner_(owner), lifetime_(owner->lifetime()) {
    }

    ~DataChannelObserver() override = default;

    void OnStateChange() override {
    }

    void OnMessage(const webrtc::DataBuffer& buffer) override {
        if (!lifetime_.expired()) { owner_->OnDataMessage(buffer.data.cdata(), buffer.data.size()); }
    }

private:
    ScrcpyPeerConnection* owner_;
    std::weak_ptr<bool> lifetime_;
};

ScrcpyPeerConnection::ScrcpyPeerConnection() {
}

ScrcpyPeerConnection::~ScrcpyPeerConnection() {
    if (signaling_thread_ != nullptr) {
        signaling_thread_->BlockingCall([this] {
            lifetime_.reset();
            answer_callback_ = nullptr;
            ice_candidate_callback_ = nullptr;
            data_callback_ = nullptr;
            closed_callback_ = nullptr;
            if (data_channel_ != nullptr) {
                data_channel_->UnregisterObserver();
                data_channel_->Close();
                data_channel_ = nullptr;
            }
            delete data_channel_observer_;
            data_channel_observer_ = nullptr;
            if (peer_connection_ != nullptr) { peer_connection_->Close(); peer_connection_ = nullptr; }
            observer_.reset();
            pending_ice_.clear();
            factory_ = nullptr;
            audio_source_ = nullptr;
        });
        signaling_thread_->Stop();
    }
    if (worker_thread_ != nullptr) { worker_thread_->Stop(); }
    if (network_thread_ != nullptr) { network_thread_->Stop(); }
}

bool ScrcpyPeerConnection::Initialize(webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> track_source,
        bool audio, const std::string& turn_url, const std::string& turn_user, const std::string& turn_password,
        std::function<void(int, double)> bitrate, std::function<void()> keyframe) {
    network_thread_ = webrtc::Thread::CreateWithSocketServer();
    worker_thread_ = webrtc::Thread::Create();
    signaling_thread_ = webrtc::Thread::Create();
    network_thread_->Start(); worker_thread_->Start(); signaling_thread_->Start();
    bool success = false;
    signaling_thread_->BlockingCall([&] {
        webrtc::PeerConnectionFactoryDependencies deps;
        deps.network_thread = network_thread_.get();
        deps.worker_thread = worker_thread_.get();
        deps.signaling_thread = signaling_thread_.get();
        auto env = webrtc::CreateEnvironment();
        deps.env = env;
        deps.adm = webrtc::CreateAudioDeviceModule(env, webrtc::AudioDeviceModule::kDummyAudio);
        deps.audio_encoder_factory = webrtc::CreateBuiltinAudioEncoderFactory();
        deps.audio_decoder_factory = webrtc::CreateBuiltinAudioDecoderFactory();
        deps.video_encoder_factory = std::make_unique<ScrcpyVideoEncoderFactory>(bitrate, keyframe);
        webrtc::EnableMedia(deps);
        factory_ = webrtc::PeerConnectionFactory::Create(std::move(deps));
        if (factory_ == nullptr) { return; }
        webrtc::PeerConnectionInterface::RTCConfiguration config;
        if (!turn_url.empty()) {
            webrtc::PeerConnectionInterface::IceServer turn;
            turn.uri = turn_url; turn.username = turn_user; turn.password = turn_password;
            config.servers.push_back(turn);
        }
        observer_ = std::make_unique<PeerObserver>(this);
        webrtc::PeerConnectionDependencies dependencies(observer_.get());
        auto result = factory_->CreatePeerConnectionOrError(config, std::move(dependencies));
        if (!result.ok()) { return; }
        peer_connection_ = result.MoveValue();
        auto video_track = factory_->CreateVideoTrack(track_source, "video");
        if (!video_track || !peer_connection_->AddTrack(video_track, {"scrcpy"}).ok()) { return; }
        if (audio) {
            audio_source_ = webrtc::make_ref_counted<PcmAudioSource>();
            auto audio_track = factory_->CreateAudioTrack("audio", audio_source_.get());
            if (!audio_track || !peer_connection_->AddTrack(audio_track, {"scrcpy"}).ok()) { return; }
        }
        success = true;
    });
    return success;
}
void ScrcpyPeerConnection::PushAudio(const uint8_t* data, size_t len, int64_t pts_us) {
    if (audio_source_ != nullptr) { audio_source_->Push(data, len, pts_us); }
}
void ScrcpyPeerConnection::SetClosedCallback(std::function<void()> callback) {
    signaling_thread_->BlockingCall([&] { closed_callback_ = std::move(callback); });
}
void ScrcpyPeerConnection::OnConnectionClosed() {
    if (closed_callback_) { closed_callback_(); }
}

void ScrcpyPeerConnection::SetAnswerCallback(std::function<void(const std::string&)> callback) {
    signaling_thread_->BlockingCall([&] { answer_callback_ = std::move(callback); });
}

void ScrcpyPeerConnection::SetIceCandidateCallback(std::function<void(const std::string&, int, const std::string&)> callback) {
    signaling_thread_->BlockingCall([&] { ice_candidate_callback_ = std::move(callback); });
}

void ScrcpyPeerConnection::SetDataChannelCallback(std::function<void(const uint8_t*, size_t)> callback) {
    signaling_thread_->BlockingCall([&] { data_callback_ = std::move(callback); });
}

bool ScrcpyPeerConnection::SendData(const uint8_t* data, size_t len) {
    bool sent = false;
    signaling_thread_->BlockingCall([&] {
        if (data_channel_ == nullptr || data_channel_->state() != webrtc::DataChannelInterface::kOpen
                || len > 262144 || data_channel_->buffered_amount() + len > 1048576) { return; }
        sent = data_channel_->Send(webrtc::DataBuffer(webrtc::CopyOnWriteBuffer(data, len), true));
    });
    return sent;
}

void ScrcpyPeerConnection::OnDataChannelOpened(webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel) {
    if (data_channel_ != nullptr || data_channel->label() != "control") { data_channel->Close(); return; }
    data_channel_ = data_channel;
    data_channel_observer_ = new DataChannelObserver(this);
    data_channel_->RegisterObserver(data_channel_observer_);
    SCP_LOGE("OnDataChannelOpened: label=%s", data_channel_->label().c_str());
}

void ScrcpyPeerConnection::OnDataMessage(const uint8_t* data, size_t len) {
    SCP_LOGE("OnDataMessage: len=%zu type=%d", len, len > 0 ? (int) data[0] : -1);
    if (data_callback_) {
        data_callback_(data, len);
    }
}

void ScrcpyPeerConnection::OnOffer(const std::string& sdp) {
    signaling_thread_->BlockingCall([&] {
    std::unique_ptr<webrtc::SessionDescriptionInterface> desc =
            webrtc::CreateSessionDescription(webrtc::SdpType::kOffer, sdp);
    if (desc == nullptr) {
        return;
    }
    peer_connection_->SetRemoteDescription(
            std::move(desc),
            webrtc::make_ref_counted<RemoteDescriptionObserver>(this));
    });
}

void ScrcpyPeerConnection::OnIceCandidate(const std::string& mid, int index, const std::string& sdp) {
    signaling_thread_->BlockingCall([&] {
        auto candidate = webrtc::IceCandidate::Create(mid, index, sdp);
        if (!candidate) { return; }
        if (!remote_description_set_) {
            if (pending_ice_.size() < 256) { pending_ice_.push_back(std::move(candidate)); }
        } else { peer_connection_->AddIceCandidate(candidate.get()); }
    });
}

void ScrcpyPeerConnection::OnRemoteDescriptionSet() {
    remote_description_set_ = true;
    for (const auto& candidate : pending_ice_) { peer_connection_->AddIceCandidate(candidate.get()); }
    pending_ice_.clear();
    webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
    auto observer = webrtc::make_ref_counted<AnswerObserver>(this);
    peer_connection_->CreateAnswer(observer.get(), options);
}

void ScrcpyPeerConnection::OnAnswerCreated(std::unique_ptr<webrtc::SessionDescriptionInterface> desc) {
    if (desc != nullptr) {
        std::string sdp;
        desc->ToString(&sdp);
        SCP_LOGE("OnAnswerCreated: %s", sdp.c_str());
    }
    peer_connection_->SetLocalDescription(
            std::move(desc),
            webrtc::make_ref_counted<LocalDescriptionObserver>(this));
}

void ScrcpyPeerConnection::OnLocalDescriptionSet() {
    if (answer_callback_) {
        std::string sdp;
        if (peer_connection_->local_description() != nullptr) {
            sdp = peer_connection_->local_description()->ToString();
        }
        answer_callback_(sdp);
    }
}

void ScrcpyPeerConnection::OnIceCandidateGathered(const std::string& sdp_mid, int sdp_mline_index, const std::string& sdp) {
    if (ice_candidate_callback_) {
        ice_candidate_callback_(sdp_mid, sdp_mline_index, sdp);
    }
}

}
