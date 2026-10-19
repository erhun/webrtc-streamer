#include "scrcpy_peer_connection.h"

#include "api/audio/builtin_audio_processing_builder.h"
#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/enable_media.h"
#include "api/environment/environment_factory.h"
#include "api/field_trials.h"
#include "api/units/time_delta.h"
#include "api/jsep.h"
#include "api/rtp_sender_interface.h"
#include "api/set_local_description_observer_interface.h"
#include "api/set_remote_description_observer_interface.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "pc/peer_connection_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "scrcpy_passthrough_encoder.h"
#include "scrcpy_video_encoder_factory.h"

#include <utility>
#include <time.h>
#include "api/stats/rtc_stats_collector_callback.h"
#include "api/stats/rtc_stats_report.h"
#include "api/stats/rtcstats_objects.h"

#include <android/log.h>

#define SCP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "scrcpy_native", __VA_ARGS__)

namespace scrcpy {

namespace {
void ConfigureVideoSender(webrtc::RtpSenderInterface& sender) {
    auto parameters = sender.GetParameters();
    if (parameters.encodings.empty()) {
        SCP_LOGE("Video sender: waiting for negotiated encodings before setting max_framerate");
        return;
    }
    for (auto& encoding : parameters.encodings) { encoding.max_framerate = 60.0; }
    auto configured = sender.SetParameters(parameters);
    if (!configured.ok()) {
        SCP_LOGE("Video sender rejected max_framerate=60: %s", configured.message());
        return; // Preserve media on implementations that reject this optional ceiling update.
    }
    bool twcc = false;
    for (const auto& extension : parameters.header_extensions) {
        twcc |= extension.uri == webrtc::RtpExtension::kTransportSequenceNumberUri;
    }
    SCP_LOGE("Video sender configured: max_framerate=60 twcc_extension=%d", twcc);
}

class LatencyStatsCallback : public webrtc::RTCStatsCollectorCallback {
public:
    explicit LatencyStatsCallback(std::function<void(const webrtc::RTCStatsReport&)> callback)
        : callback_(std::move(callback)) {}
    void OnStatsDelivered(const webrtc::scoped_refptr<const webrtc::RTCStatsReport>& report) override {
        callback_(*report);
    }
private:
    std::function<void(const webrtc::RTCStatsReport&)> callback_;
};


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
        if (lifetime_.expired()) { return; }
        if (state == webrtc::PeerConnectionInterface::PeerConnectionState::kConnected) {
            owner_->OnConnectionReady();
        }
        if (state == webrtc::PeerConnectionInterface::PeerConnectionState::kFailed
                || state == webrtc::PeerConnectionInterface::PeerConnectionState::kDisconnected) {
            owner_->OnConnectionInterrupted();
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
        if (!lifetime_.expired()) {
            SCP_LOGE("DataChannelObserver::OnStateChange: state=%d", (int)owner_->GetDataChannelState());
        }
    }

    void OnMessage(const webrtc::DataBuffer& buffer) override {
        if (lifetime_.expired()) { return; }
        if (!buffer.binary) {
            const std::string request(reinterpret_cast<const char*>(buffer.data.cdata()), buffer.data.size());
            if (request == "scrcpy.latency.v1") { owner_->RequestLatencyStats(); }
            return; // Never feed telemetry text into the binary control parser.
        }
        owner_->OnDataMessage(buffer.data.cdata(), buffer.data.size());
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
            ClosePeerConnection();
            factory_ = nullptr;
            audio_source_ = nullptr;
        });
    }
}

bool ScrcpyPeerConnection::Initialize(webrtc::scoped_refptr<EncodedVideoTrackSource> track_source,
        bool audio,
        const std::string& turn_url, const std::string& turn_user, const std::string& turn_password,
        int min_port, int max_port,
        std::function<void(int, double)> bitrate, std::function<void()> keyframe,
        webrtc::Thread* network_thread, webrtc::Thread* worker_thread, webrtc::Thread* signaling_thread) {
    video_source_ = track_source;
    keyframe_callback_ = keyframe;
    network_thread_ = network_thread;
    worker_thread_ = worker_thread;
    signaling_thread_ = signaling_thread;
    bool success = false;
    signaling_thread_->BlockingCall([&] {
        webrtc::PeerConnectionFactoryDependencies deps;
        deps.network_thread = network_thread_;
        deps.worker_thread = worker_thread_;
        deps.signaling_thread = signaling_thread_;
        // Enable periodic ALR probing so the BWE measures real network capacity
        // instead of staying application-limited by the passthrough encoder's
        // content-dependent output rate.
        // Request a small playout delay range. This is a receiver hint, not a
        // guarantee that measured or target jitter-buffer delay stays below 100ms.
        // FastRetransmissions lets RTX packets jump the pacer queue so NACK recovery
        // completes in ~1 RTT instead of waiting behind queued media; this lowers the
        // residual loss the decoder sees and cuts PLI re-requests on a lossy path.
        // Probes/padding are paced by WebRTC and evaluated from transport feedback.
        // Java does not manufacture load by exceeding SetRates. Intra-refresh is optional;
        // join/recovery still produce IDRs, whose requests must be coalesced at the source.
        static webrtc::FieldTrials field_trials(
                "WebRTC-VideoRateControl/alr_probing:true/"
                "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/"
                "WebRTC-Pacer-FastRetransmissions/Enabled/");
        auto env = webrtc::CreateEnvironment(&field_trials);
        deps.env = env;
        deps.adm = webrtc::CreateAudioDeviceModule(env, webrtc::AudioDeviceModule::kDummyAudio);
        deps.audio_encoder_factory = webrtc::CreateBuiltinAudioEncoderFactory();
        deps.audio_decoder_factory = webrtc::CreateBuiltinAudioDecoderFactory();
        deps.video_encoder_factory = std::make_unique<ScrcpyVideoEncoderFactory>(
            [this, bitrate](const webrtc::VideoEncoder::RateControlParameters& rates) {
                const int bps = rates.bitrate.get_sum_bps();
                allocated_bps_.store(bps);
                target_bps_.store(rates.target_bitrate.get_sum_bps());
                bandwidth_allocation_bps_.store(rates.bandwidth_allocation.bps<int64_t>());
                timespec now{};
                if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
                    rate_updated_us_.store(now.tv_sec * 1000000LL + now.tv_nsec / 1000);
                }
                if (bitrate) { bitrate(bps, rates.framerate_fps); }
            }, keyframe);
        webrtc::EnableMedia(deps);
        factory_ = webrtc::PeerConnectionFactory::Create(std::move(deps));
        if (factory_ == nullptr) { return; }
        if (!turn_url.empty()) {
            webrtc::PeerConnectionInterface::IceServer turn;
            turn.uri = turn_url; turn.username = turn_user; turn.password = turn_password;
            configuration_.servers.push_back(turn);
        } else {
            webrtc::PeerConnectionInterface::IceServer stun;
            stun.uri = "stun:stun.l.google.com:19302";
            configuration_.servers.push_back(stun);
        }
        configuration_.bundle_policy = webrtc::PeerConnectionInterface::kBundlePolicyMaxBundle;
        if (min_port > 0 && max_port >= min_port && max_port <= 65535) {
            configuration_.set_min_port(min_port);
            configuration_.set_max_port(max_port);
        }
        configuration_.ice_candidate_pool_size = 0;
        if (audio) { audio_source_ = webrtc::make_ref_counted<PcmAudioSource>(); }
        success = CreatePeerConnection();
    });
    return success;
}
// All peer creation/destruction and observer lifetime changes run on the signaling thread.
void ScrcpyPeerConnection::ClosePeerConnection() {
    lifetime_.reset(); // Invalidate old answers, ICE callbacks, and recovery timers first.
    if (data_channel_) {
        data_channel_->UnregisterObserver();
        data_channel_->Close();
        data_channel_ = nullptr;
    }
    delete data_channel_observer_;
    data_channel_observer_ = nullptr;
    if (peer_connection_) { peer_connection_->Close(); peer_connection_ = nullptr; }
    observer_.reset();
    pending_ice_.clear();
    remote_description_set_ = false;
    stats_pending_ = false;
    telemetry_gate_.Reset();
    recovering_ = false;
    ++recovery_generation_;
    allocated_bps_.store(-1);
    target_bps_.store(-1);
    bandwidth_allocation_bps_.store(-1);
    rate_updated_us_.store(-1);
}

bool ScrcpyPeerConnection::CreatePeerConnection() {
    lifetime_ = std::make_shared<bool>(true);
    observer_ = std::make_unique<PeerObserver>(this);
    webrtc::PeerConnectionDependencies dependencies(observer_.get());
    auto result = factory_->CreatePeerConnectionOrError(configuration_, std::move(dependencies));
    if (!result.ok()) { return false; }
    peer_connection_ = result.MoveValue();
    auto video_track = factory_->CreateVideoTrack(video_source_, "video");
    if (!video_track) { return false; }
    auto video_result = peer_connection_->AddTrack(video_track, {"scrcpy"});
    if (!video_result.ok()) { return false; }
    auto sender = video_result.MoveValue();
    // Lift the media-engine default 30fps ceiling. Java/capture still enforce each tier,
    // the user's max_fps, and the actual MediaCodec capabilities. Do not force a minimum rate.
    ConfigureVideoSender(*sender);
    if (audio_source_) {
        auto audio_track = factory_->CreateAudioTrack("audio", audio_source_.get());
        if (!audio_track || !peer_connection_->AddTrack(audio_track, {"scrcpy"}).ok()) { return false; }
    }
    return true;
}

bool ScrcpyPeerConnection::ResetPeerConnection() {
    bool success = false;
    signaling_thread_->BlockingCall([&] {
        ClosePeerConnection();
        // Serialize input release after all old messages and before the new channel.
        // ControlMessage.TYPE_RELEASE_INPUTS; never clear a partially read byte stream.
        const uint8_t release_inputs = 23;
        if (data_callback_) { data_callback_(&release_inputs, 1); }
        success = CreatePeerConnection();
        if (success) { OnConnectionInterrupted(); } // Bound a reset that never negotiates.
    });
    return success;
}

void ScrcpyPeerConnection::PushAudio(const uint8_t* data, size_t len, int64_t pts_us) {
    if (audio_source_ != nullptr) { audio_source_->Push(data, len, pts_us); }
}

void ScrcpyPeerConnection::SetClosedCallback(std::function<void()> callback) {
    signaling_thread_->BlockingCall([&] { closed_callback_ = std::move(callback); });
}
void ScrcpyPeerConnection::OnConnectionReady() {
    recovering_ = false;
    ++recovery_generation_;
    SCP_LOGE("Transport connected: request fresh keyframe");
    if (keyframe_callback_) { keyframe_callback_(); }
}
void ScrcpyPeerConnection::OnConnectionInterrupted() {
    if (recovering_) { return; }
    recovering_ = true;
    const auto generation = ++recovery_generation_;
    auto weak = lifetime();
    SCP_LOGE("Transport interrupted: waiting up to 45s for ICE recovery");
    signaling_thread_->PostDelayedTask([this, weak, generation] {
        if (weak.expired()) { return; }
        if (recovering_ && recovery_generation_ == generation) {
            SCP_LOGE("Transport recovery timed out");
            OnConnectionClosed();
        }
    }, webrtc::TimeDelta::Seconds(45));
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
    if (data_channel->label() != "control") { data_channel->Close(); return; }
    if (data_channel_ != nullptr && data_channel_->state() == webrtc::DataChannelInterface::kOpen) {
        SCP_LOGE("OnDataChannelOpened: rejecting duplicate control channel");
        data_channel->Close();
        return;
    }
    if (data_channel_ != nullptr) {
        SCP_LOGE("OnDataChannelOpened: replacing closed control channel");
        data_channel_->UnregisterObserver();
        data_channel_->Close();
        delete data_channel_observer_;
        data_channel_observer_ = nullptr;
    }
    data_channel_ = data_channel;
    data_channel_observer_ = new DataChannelObserver(this);
    data_channel_->RegisterObserver(data_channel_observer_);
    SCP_LOGE("OnDataChannelOpened: label=%s state=%d", data_channel_->label().c_str(), (int)data_channel_->state());
}

void ScrcpyPeerConnection::RequestLatencyStats() {
    if (stats_pending_ || !peer_connection_ || !data_channel_
            || data_channel_->state() != webrtc::DataChannelInterface::kOpen) { return; }
    timespec request_time{};
    if (clock_gettime(CLOCK_MONOTONIC, &request_time) != 0) { return; }
    const int64_t request_us = request_time.tv_sec * 1000000LL + request_time.tv_nsec / 1000;
    if (!telemetry_gate_.Allow(request_us, allocated_bps_.load(), data_channel_->buffered_amount())) { return; }
    stats_pending_ = true;
    auto weak = lifetime();
    auto callback = webrtc::make_ref_counted<LatencyStatsCallback>(
        [this, weak](const webrtc::RTCStatsReport& report) {
            // GetStats delivers on the signaling thread, as does destruction.
            if (weak.expired()) { return; }
            stats_pending_ = false;
            if (!data_channel_ || data_channel_->state() != webrtc::DataChannelInterface::kOpen
                    || data_channel_->buffered_amount() > 1024) { return; }
            auto age = video_source_->CaptureAgeTotals();
            timespec now{};
            clock_gettime(CLOCK_MONOTONIC, &now);
            const int64_t sample_us = now.tv_sec * 1000000LL + now.tv_nsec / 1000;
            std::string payload = "{\"type\":\"scrcpy.latency.v1\",\"captureUs\":"
                + std::to_string(age.first) + ",\"captureFrames\":" + std::to_string(age.second)
                + ",\"allocatedBps\":" + std::to_string(allocated_bps_.load())
                + ",\"configuredBps\":" + std::to_string(configured_bps_.load())
                + ",\"targetBps\":" + std::to_string(target_bps_.load())
                + ",\"bandwidthAllocationBps\":" + std::to_string(bandwidth_allocation_bps_.load())
                + ",\"rateUpdatedUs\":" + std::to_string(rate_updated_us_.load())
                + ",\"encodedBytes\":" + std::to_string(video_source_->EncodedBytes())
                + ",\"sampleUs\":" + std::to_string(sample_us)
                + ",\"outbound\":[";
            bool first = true;
            for (const auto& stat : report) {
                if (std::string(stat.type()) != "outbound-rtp") { continue; }
                const auto& rtp = stat.cast_to<webrtc::RTCOutboundRtpStreamStats>();
                if (rtp.kind.value_or("") != "video") { continue; }
                webrtc::RTCOutboundRtpStreamStats compact(stat.id(), stat.timestamp());
                compact.kind = rtp.kind;
                compact.packets_sent = rtp.packets_sent;
                compact.total_packet_send_delay = rtp.total_packet_send_delay;
                compact.frames_encoded = rtp.frames_encoded;
                compact.bytes_sent = rtp.bytes_sent;
                compact.key_frames_encoded = rtp.key_frames_encoded;
                compact.nack_count = rtp.nack_count;
                compact.pli_count = rtp.pli_count;
                compact.fir_count = rtp.fir_count;
                compact.quality_limitation_reason = rtp.quality_limitation_reason;
                if (!first) { payload += ","; }
                first = false;
                payload += compact.ToJson();
            }
            payload += "],\"network\":[";
            first = true;
            for (const auto& stat : report) {
                if (std::string(stat.type()) != "transport") { continue; }
                const auto& transport = stat.cast_to<webrtc::RTCTransportStats>();
                if (!transport.selected_candidate_pair_id) { continue; }
                const auto* selected = report.Get(*transport.selected_candidate_pair_id);
                if (!selected || std::string(selected->type()) != "candidate-pair") { continue; }
                const auto& pair = selected->cast_to<webrtc::RTCIceCandidatePairStats>();
                webrtc::RTCTransportStats compact_transport(stat.id(), stat.timestamp());
                compact_transport.selected_candidate_pair_id = transport.selected_candidate_pair_id;
                webrtc::RTCIceCandidatePairStats compact_pair(selected->id(), selected->timestamp());
                compact_pair.available_outgoing_bitrate = pair.available_outgoing_bitrate;
                compact_pair.current_round_trip_time = pair.current_round_trip_time;
                if (!first) { payload += ","; }
                first = false;
                payload += compact_transport.ToJson() + "," + compact_pair.ToJson();
            }
            payload += "]}";
            // Bound diagnostics on the reliable input channel, including their queued bytes.
            if (payload.size() > 2048 || data_channel_->buffered_amount() + payload.size() > 4096) { return; }
            data_channel_->Send(webrtc::DataBuffer(payload));
        });
    peer_connection_->GetStats(callback.get());
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
    remote_description_set_ = false;
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
    SCP_LOGE("Local answer created");
    peer_connection_->SetLocalDescription(
            std::move(desc),
            webrtc::make_ref_counted<LocalDescriptionObserver>(this));
}

void ScrcpyPeerConnection::OnLocalDescriptionSet() {
    // Reapply after negotiation and report the negotiated transport-feedback extension.
    for (const auto& sender : peer_connection_->GetSenders()) {
        auto track = sender->track();
        if (track && track->kind() == webrtc::MediaStreamTrackInterface::kVideoKind) {
            ConfigureVideoSender(*sender);
        }
    }
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
