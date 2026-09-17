#include "scrcpy_video_encoder_factory.h"

#include "scrcpy_passthrough_encoder.h"

namespace scrcpy {

std::vector<webrtc::SdpVideoFormat> ScrcpyVideoEncoderFactory::GetSupportedFormats() const {
    // Without an explicit cap, libwebrtc defaults the rate-controller ceiling to a
    // resolution-based table (~2.5 Mbps at 1080p), which stops the bitrate ladder from
    // climbing past 1280. Raise the ceiling and start high so it can reach 1920 quickly.
    return {webrtc::SdpVideoFormat("H264",
            {{"packetization-mode", "1"}, {"profile-level-id", "42001f"},
                    {"level-asymmetry-allowed", "1"},
                    {"x-google-max-bitrate", "20000"}, {"x-google-start-bitrate", "8000"}})};
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
