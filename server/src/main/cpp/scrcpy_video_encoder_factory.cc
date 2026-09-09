#include "scrcpy_video_encoder_factory.h"

#include "scrcpy_passthrough_encoder.h"

namespace scrcpy {

std::vector<webrtc::SdpVideoFormat> ScrcpyVideoEncoderFactory::GetSupportedFormats() const {
    return {webrtc::SdpVideoFormat("H264",
            {{"packetization-mode", "1"}, {"profile-level-id", "42001f"},
                    {"level-asymmetry-allowed", "1"}})};
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
