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
#include "scrcpy_audio_encoder_factory.h"
#include "scrcpy_passthrough_encoder.h"
#include "scrcpy_video_encoder_factory.h"

#include <utility>

#include <android/log.h>

#define SCP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "scrcpy_native", __VA_ARGS__)

namespace scrcpy {

namespace {

class AnswerObserver : public webrtc::CreateSessionDescriptionObserver {
public:
    explicit AnswerObserver(ScrcpyPeerConnection* owner) : owner_(owner) {
    }

    void OnSuccess(webrtc::SessionDescriptionInterface* desc) override {
        owner_->OnAnswerCreated(std::unique_ptr<webrtc::SessionDescriptionInterface>(desc));
    }

    void OnFailure(webrtc::RTCError error) override {
        (void) error;
    }

private:
    ScrcpyPeerConnection* owner_;
};

class RemoteDescriptionObserver : public webrtc::SetRemoteDescriptionObserverInterface {
public:
    explicit RemoteDescriptionObserver(ScrcpyPeerConnection* owner) : owner_(owner) {
    }

    void OnSetRemoteDescriptionComplete(webrtc::RTCError error) override {
        if (error.ok()) {
            owner_->OnRemoteDescriptionSet();
        }
    }

private:
    ScrcpyPeerConnection* owner_;
};

class LocalDescriptionObserver : public webrtc::SetLocalDescriptionObserverInterface {
public:
    explicit LocalDescriptionObserver(ScrcpyPeerConnection* owner) : owner_(owner) {
    }

    void OnSetLocalDescriptionComplete(webrtc::RTCError error) override {
        if (error.ok()) {
            owner_->OnLocalDescriptionSet();
        }
    }

private:
    ScrcpyPeerConnection* owner_;
};

class PeerObserver : public webrtc::PeerConnectionObserver {
public:
    explicit PeerObserver(ScrcpyPeerConnection* owner) : owner_(owner) {
    }

    void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState new_state) override {
    }

    void OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel) override {
        owner_->OnDataChannelOpened(data_channel);
    }

    void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState new_state) override {
    }

    void OnIceCandidate(const webrtc::IceCandidate* candidate) override {
        if (candidate != nullptr) {
            owner_->OnIceCandidateGathered(candidate->sdp_mid(), candidate->sdp_mline_index(), candidate->ToString());
        }
    }

private:
    ScrcpyPeerConnection* owner_;
};

}

class DataChannelObserver : public webrtc::DataChannelObserver {
public:
    explicit DataChannelObserver(ScrcpyPeerConnection* owner) : owner_(owner) {
    }

    ~DataChannelObserver() override = default;

    void OnStateChange() override {
    }

    void OnMessage(const webrtc::DataBuffer& buffer) override {
        owner_->OnDataMessage(buffer.data.cdata(), buffer.data.size());
    }

private:
    ScrcpyPeerConnection* owner_;
};

ScrcpyPeerConnection::ScrcpyPeerConnection() {
}

ScrcpyPeerConnection::~ScrcpyPeerConnection() {
    if (peer_connection_ != nullptr) {
        peer_connection_->Close();
    }
    delete data_channel_observer_;
    data_channel_observer_ = nullptr;
}

bool ScrcpyPeerConnection::Initialize(webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> track_source) {
    network_thread_ = webrtc::Thread::CreateWithSocketServer();
    network_thread_->Start();
    worker_thread_ = webrtc::Thread::Create();
    worker_thread_->Start();
    signaling_thread_ = webrtc::Thread::Create();
    signaling_thread_->Start();

    auto* factory_dependencies = new webrtc::PeerConnectionFactoryDependencies();
    factory_dependencies->network_thread = network_thread_.get();
    factory_dependencies->worker_thread = worker_thread_.get();
    factory_dependencies->signaling_thread = signaling_thread_.get();
    SCP_LOGE("threads: network=%p worker=%p signaling=%p", network_thread_.get(), worker_thread_.get(), signaling_thread_.get());
    webrtc::Environment env = webrtc::CreateEnvironment();
    factory_dependencies->env = env;
    factory_dependencies->adm = webrtc::CreateAudioDeviceModule(
            env, webrtc::AudioDeviceModule::kDummyAudio);
    factory_dependencies->audio_encoder_factory = webrtc::make_ref_counted<ScrcpyAudioEncoderFactory>();
    factory_dependencies->audio_decoder_factory = webrtc::CreateBuiltinAudioDecoderFactory();
    factory_dependencies->video_encoder_factory = std::make_unique<ScrcpyVideoEncoderFactory>();
    webrtc::EnableMedia(*factory_dependencies);
    factory_ = webrtc::PeerConnectionFactory::Create(std::move(*factory_dependencies));

    if (factory_ == nullptr) {
        return false;
    }
    SCP_LOGE("factory threads: worker=%p signaling=%p",
            static_cast<webrtc::PeerConnectionFactory*>(factory_.get())->worker_thread(),
            static_cast<webrtc::PeerConnectionFactory*>(factory_.get())->signaling_thread());

    webrtc::PeerConnectionInterface::RTCConfiguration config;
    webrtc::PeerConnectionInterface::IceServer turn_server;
    turn_server.uri = "turn:10.0.2.2:3478";
    turn_server.username = "turnuser";
    turn_server.password = "turnpassword";
    config.servers.push_back(turn_server);
    observer_ = std::make_unique<PeerObserver>(this);
    webrtc::PeerConnectionDependencies dependencies(observer_.get());

    auto result = factory_->CreatePeerConnectionOrError(config, std::move(dependencies));
    if (!result.ok()) {
        return false;
    }
    peer_connection_ = result.MoveValue();

    webrtc::scoped_refptr<webrtc::VideoTrackInterface> video_track;
    bool add_track_ok = false;
    signaling_thread_->BlockingCall([&] {
        video_track = factory_->CreateVideoTrack(track_source, "video");
        if (video_track != nullptr) {
            add_track_ok = peer_connection_->AddTrack(video_track, {"video"}).ok();
        }
    });
    SCP_LOGE("Initialize: track_source=%p video_track=%p add=%d", track_source.get(), video_track.get(), add_track_ok);
    if (video_track == nullptr) {
        return false;
    }
    return add_track_ok;
}

void ScrcpyPeerConnection::SetAnswerCallback(std::function<void(const std::string&)> callback) {
    answer_callback_ = std::move(callback);
}

void ScrcpyPeerConnection::SetIceCandidateCallback(std::function<void(const std::string&, int, const std::string&)> callback) {
    ice_candidate_callback_ = std::move(callback);
}

void ScrcpyPeerConnection::SetDataChannelCallback(std::function<void(const uint8_t*, size_t)> callback) {
    data_callback_ = std::move(callback);
}

bool ScrcpyPeerConnection::SendData(const uint8_t* data, size_t len) {
    if (data_channel_ == nullptr) {
        return false;
    }
    webrtc::DataBuffer buffer(webrtc::CopyOnWriteBuffer(data, len), true);
    return data_channel_->Send(buffer);
}

void ScrcpyPeerConnection::OnDataChannelOpened(webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel) {
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
    std::unique_ptr<webrtc::SessionDescriptionInterface> desc =
            webrtc::CreateSessionDescription(webrtc::SdpType::kOffer, sdp);
    if (desc == nullptr) {
        return;
    }
    peer_connection_->SetRemoteDescription(
            std::move(desc),
            webrtc::make_ref_counted<RemoteDescriptionObserver>(this));
}

void ScrcpyPeerConnection::OnIceCandidate(const std::string& sdp_mid, int sdp_mline_index, const std::string& sdp) {
    std::unique_ptr<webrtc::IceCandidate> candidate =
            webrtc::IceCandidate::Create(sdp_mid, sdp_mline_index, sdp);
    if (candidate != nullptr) {
        peer_connection_->AddIceCandidate(candidate.get());
    }
}

void ScrcpyPeerConnection::OnRemoteDescriptionSet() {
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
