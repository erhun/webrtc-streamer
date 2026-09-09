#ifndef SCRCPY_OPUS_AUDIO_ENCODER_H_
#define SCRCPY_OPUS_AUDIO_ENCODER_H_

#include "api/audio_codecs/audio_encoder.h"
#include "api/units/time_delta.h"
#include "rtc_base/synchronization/mutex.h"

#include <cstdint>
#include <deque>
#include <optional>
#include <utility>
#include <vector>

namespace scrcpy {

class ScrcpyOpusAudioEncoder final : public webrtc::AudioEncoder {
public:
    ScrcpyOpusAudioEncoder();

    void OnEncodedFrame(const uint8_t* opus, size_t len, int64_t pts_us);

    int SampleRateHz() const override;
    size_t NumChannels() const override;
    size_t Num10MsFramesInNextPacket() const override;
    size_t Max10MsFramesInAPacket() const override;
    int GetTargetBitrate() const override;
    void Reset() override;
    std::optional<std::pair<webrtc::TimeDelta, webrtc::TimeDelta>> GetFrameLengthRange() const override;

protected:
    webrtc::AudioEncoder::EncodedInfo EncodeImpl(uint32_t rtp_timestamp,
            std::span<const int16_t> audio, webrtc::Buffer* encoded) override;

private:
    webrtc::Mutex mutex_;
    std::deque<std::vector<uint8_t>> pending_;
};

}

#endif
