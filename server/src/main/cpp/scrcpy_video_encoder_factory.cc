#include "scrcpy_video_encoder_factory.h"

#include "scrcpy_passthrough_encoder.h"

namespace scrcpy {

std::vector<webrtc::SdpVideoFormat> ScrcpyVideoEncoderFactory::GetSupportedFormats() const {
    // Without an explicit cap, libwebrtc defaults the rate-controller ceiling to a
    // resolution-based table (~2.5 Mbps at 1080p), which stops the bitrate ladder from
    // climbing past 1280. Keep a capability ceiling and a conservative startup hint.
    std::vector<webrtc::SdpVideoFormat> formats;
    // Passthrough forwards device-encoded H.264 as-is; the decoder only needs a
    // matching profile declaration to accept it. Declare both Baseline and
    // Constrained Baseline: browsers such as Safari only offer 42e01f and would
    // otherwise reject the video m-line entirely, which also corrupts bundle
    // negotiation ("Failed to setup RTCP mux") and kills the whole session.
    formats.push_back(webrtc::SdpVideoFormat("H264",
            {{"packetization-mode", "1"}, {"profile-level-id", "42e01f"},
                    {"level-asymmetry-allowed", "1"},
                    {"x-google-max-bitrate", "20000"}, {"x-google-start-bitrate", "500"}}));
    formats.push_back(webrtc::SdpVideoFormat("H264",
            {{"packetization-mode", "1"}, {"profile-level-id", "42001f"},
                    {"level-asymmetry-allowed", "1"},
                    {"x-google-max-bitrate", "20000"}, {"x-google-start-bitrate", "500"}}));
    // RED/ULPFEC/RTX capabilities belong to the WebRTC media engine, not to real encoders.
    return formats;
}

std::unique_ptr<webrtc::VideoEncoder> ScrcpyVideoEncoderFactory::Create(const webrtc::Environment& env,
        const webrtc::SdpVideoFormat& format) {
    if (format.name != "H264") { return nullptr; }
    auto encoder = std::make_unique<ScrcpyPassthroughEncoder>();
    encoder->SetBitrateCallback(bitrate_);
    encoder->SetKeyFrameRequestCallback(keyframe_);
    return encoder;
}

}
