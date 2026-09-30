#include "scrcpy_audio_encoder_factory.h"

#include "scrcpy_opus_audio_encoder.h"

#include "absl/strings/match.h"
#include "api/audio_codecs/audio_encoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/audio_codecs/opus/audio_encoder_opus_config.h"
#include "api/environment/environment.h"
#include "api/make_ref_counted.h"
#include "rtc_base/logging.h"
#include "rtc_base/ref_counted_object.h"

namespace scrcpy {

namespace {

class ScrcpyOpusEncoderFactory : public webrtc::AudioEncoderFactory {
public:
    explicit ScrcpyOpusEncoderFactory(std::shared_ptr<OpusFrameQueue> queue)
        : queue_(std::move(queue)) {}

    std::vector<webrtc::AudioCodecSpec> GetSupportedEncoders() override {
        std::vector<webrtc::AudioCodecSpec> specs;
        specs.push_back({webrtc::SdpAudioFormat("opus", 48000, 2),
                webrtc::AudioCodecInfo(48000, 2, 128000,
                        webrtc::AudioEncoderOpusConfig::kMinBitrateBps,
                        webrtc::AudioEncoderOpusConfig::kMaxBitrateBps)});
        return specs;
    }

    std::optional<webrtc::AudioCodecInfo> QueryAudioEncoder(
            const webrtc::SdpAudioFormat& format) override {
        if (!absl::EqualsIgnoreCase(format.name, "opus")) { return std::nullopt; }
        return webrtc::AudioCodecInfo(48000, 2, 128000,
                webrtc::AudioEncoderOpusConfig::kMinBitrateBps,
                webrtc::AudioEncoderOpusConfig::kMaxBitrateBps);
    }

    std::unique_ptr<webrtc::AudioEncoder> Create(
            const webrtc::Environment& /* env */,
            const webrtc::SdpAudioFormat& format,
            Options options) override {
        if (!absl::EqualsIgnoreCase(format.name, "opus")) { return nullptr; }
        RTC_LOG(LS_INFO) << "Created ScrcpyOpusAudioEncoder, payload_type=" << options.payload_type;
        return std::make_unique<ScrcpyOpusAudioEncoder>(queue_);
    }

private:
    std::shared_ptr<OpusFrameQueue> queue_;
};

}

webrtc::scoped_refptr<webrtc::AudioEncoderFactory> CreateScrcpyAudioEncoderFactory(
        const std::string& scheme, std::shared_ptr<OpusFrameQueue> opus_queue) {
    RTC_LOG(LS_INFO) << "ScrcpyAudioEncoderFactory: scheme=" << scheme;
    if (scheme == "opus") {
        return webrtc::make_ref_counted<ScrcpyOpusEncoderFactory>(std::move(opus_queue));
    }
    return webrtc::CreateBuiltinAudioEncoderFactory();
}

}
