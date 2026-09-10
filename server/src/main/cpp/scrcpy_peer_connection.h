#ifndef SCRCPY_PEER_CONNECTION_H_
#define SCRCPY_PEER_CONNECTION_H_

#include "api/create_peerconnection_factory.h"
#include "api/data_channel_interface.h"
#include "api/peer_connection_interface.h"
#include "rtc_base/thread.h"

#include "scrcpy_pcm_audio_source.h"
#include <functional>
#include <memory>
#include <string>

namespace scrcpy {

class EncodedVideoTrackSource;
class DataChannelObserver;

class ScrcpyPeerConnection {
public:
    ScrcpyPeerConnection();
    std::weak_ptr<bool> lifetime() const { return lifetime_; }
    ~ScrcpyPeerConnection();

    bool Initialize(webrtc::scoped_refptr<webrtc::VideoTrackSourceInterface> track_source,
            bool audio, const std::string& turn_url, const std::string& turn_user, const std::string& turn_password,
            std::function<void(int, double)> bitrate, std::function<void()> keyframe);
    void PushAudio(const uint8_t* data, size_t len, int64_t pts_us);
    void SetClosedCallback(std::function<void()> callback);
    void OnConnectionClosed();

    void SetAnswerCallback(std::function<void(const std::string&)> callback);
    void SetIceCandidateCallback(std::function<void(const std::string&, int, const std::string&)> callback);
    void SetDataChannelCallback(std::function<void(const uint8_t*, size_t)> callback);

    bool SendData(const uint8_t* data, size_t len);
    void OnDataChannelOpened(webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel);
    void OnDataMessage(const uint8_t* data, size_t len);

    void OnOffer(const std::string& sdp);
    void OnIceCandidate(const std::string& sdp_mid, int sdp_mline_index, const std::string& sdp);

    void OnRemoteDescriptionSet();
    void OnAnswerCreated(std::unique_ptr<webrtc::SessionDescriptionInterface> desc);
    void OnLocalDescriptionSet();
    void OnIceCandidateGathered(const std::string& sdp_mid, int sdp_mline_index, const std::string& sdp);

private:
    std::shared_ptr<bool> lifetime_ = std::make_shared<bool>(true);
    webrtc::scoped_refptr<PcmAudioSource> audio_source_;
    std::function<void()> closed_callback_;
    bool remote_description_set_ = false;
    std::vector<std::unique_ptr<webrtc::IceCandidate>> pending_ice_;
    std::unique_ptr<webrtc::Thread> network_thread_;
    std::unique_ptr<webrtc::Thread> worker_thread_;
    std::unique_ptr<webrtc::Thread> signaling_thread_;
    webrtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> factory_;
    webrtc::scoped_refptr<webrtc::PeerConnectionInterface> peer_connection_;
    std::unique_ptr<webrtc::PeerConnectionObserver> observer_;
    webrtc::scoped_refptr<webrtc::DataChannelInterface> data_channel_;
    DataChannelObserver* data_channel_observer_ = nullptr;
    std::function<void(const std::string&)> answer_callback_;
    std::function<void(const std::string&, int, const std::string&)> ice_candidate_callback_;
    std::function<void(const uint8_t*, size_t)> data_callback_;
};

}

#endif
