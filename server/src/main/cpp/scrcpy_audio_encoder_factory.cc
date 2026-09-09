#include "scrcpy_audio_encoder_factory.h"

#include "scrcpy_opus_audio_encoder.h"

namespace scrcpy {

std::vector<webrtc::AudioCodecSpec> ScrcpyAudioEncoderFactory::GetSupportedEncoders() {
    return {{webrtc::SdpAudioFormat("opus", 48000, 2), webrtc::AudioCodecInfo(48000, 2, 128000)}};
}

std::optional<webrtc::AudioCodecInfo> ScrcpyAudioEncoderFactory::QueryAudioEncoder(const webrtc::SdpAudioFormat& format) {
    return webrtc::AudioCodecInfo(48000, 2, 128000);
}

std::unique_ptr<webrtc::AudioEncoder> ScrcpyAudioEncoderFactory::Create(const webrtc::Environment& env,
        const webrtc::SdpAudioFormat& format, Options options) {
    return std::make_unique<ScrcpyOpusAudioEncoder>();
}

}
