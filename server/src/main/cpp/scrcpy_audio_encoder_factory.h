#ifndef SCRCPY_AUDIO_ENCODER_FACTORY_H_
#define SCRCPY_AUDIO_ENCODER_FACTORY_H_

#include "api/audio_codecs/audio_encoder.h"
#include "api/audio_codecs/audio_encoder_factory.h"
#include "api/audio_codecs/audio_format.h"

#include <memory>
#include <optional>
#include <vector>

namespace scrcpy {

class ScrcpyAudioEncoderFactory : public webrtc::AudioEncoderFactory {
public:
    std::vector<webrtc::AudioCodecSpec> GetSupportedEncoders() override;
    std::optional<webrtc::AudioCodecInfo> QueryAudioEncoder(const webrtc::SdpAudioFormat& format) override;
    std::unique_ptr<webrtc::AudioEncoder> Create(const webrtc::Environment& env,
            const webrtc::SdpAudioFormat& format, Options options) override;
};

}

#endif
