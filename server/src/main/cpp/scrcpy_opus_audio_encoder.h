#ifndef SCRCPY_OPUS_AUDIO_ENCODER_H_
#define SCRCPY_OPUS_AUDIO_ENCODER_H_

#include "api/audio_codecs/audio_encoder.h"
#include "opus_frame_queue.h"
#include "rtc_base/buffer.h"

#include <memory>
#include <optional>
#include <span>

namespace scrcpy {

class ScrcpyOpusAudioEncoder : public webrtc::AudioEncoder {
public:
    explicit ScrcpyOpusAudioEncoder(std::shared_ptr<OpusFrameQueue> queue);
    ~ScrcpyOpusAudioEncoder() override = default;

    int SampleRateHz() const override;
    size_t NumChannels() const override;
    size_t Num10MsFramesInNextPacket() const override;
    size_t Max10MsFramesInAPacket() const override;
    int GetTargetBitrate() const override;
    void Reset() override;
    std::optional<std::pair<webrtc::TimeDelta, webrtc::TimeDelta>> GetFrameLengthRange() const override;

protected:
    EncodedInfo EncodeImpl(uint32_t rtp_timestamp,
            std::span<const int16_t> audio, webrtc::Buffer* encoded) override;

private:
    std::shared_ptr<OpusFrameQueue> queue_;
};

}

#endif
