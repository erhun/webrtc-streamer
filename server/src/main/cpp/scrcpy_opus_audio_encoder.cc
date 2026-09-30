#include "scrcpy_opus_audio_encoder.h"

#include "api/audio_codecs/opus/audio_encoder_opus_config.h"
#include "api/units/time_delta.h"
#include "rtc_base/logging.h"

namespace scrcpy {

ScrcpyOpusAudioEncoder::ScrcpyOpusAudioEncoder(std::shared_ptr<OpusFrameQueue> queue)
    : queue_(std::move(queue)) {
    RTC_LOG(LS_INFO) << "ScrcpyOpusAudioEncoder created";
}

int ScrcpyOpusAudioEncoder::SampleRateHz() const {
    return 48000;
}

size_t ScrcpyOpusAudioEncoder::NumChannels() const {
    return 2;
}

size_t ScrcpyOpusAudioEncoder::Num10MsFramesInNextPacket() const {
    return 2;
}

size_t ScrcpyOpusAudioEncoder::Max10MsFramesInAPacket() const {
    return 2;
}

int ScrcpyOpusAudioEncoder::GetTargetBitrate() const {
    return 128000;
}

void ScrcpyOpusAudioEncoder::Reset() {
    queue_->Clear();
}

std::optional<std::pair<webrtc::TimeDelta, webrtc::TimeDelta>>
ScrcpyOpusAudioEncoder::GetFrameLengthRange() const {
    return {{webrtc::TimeDelta::Millis(20), webrtc::TimeDelta::Millis(20)}};
}

webrtc::AudioEncoder::EncodedInfo ScrcpyOpusAudioEncoder::EncodeImpl(
        uint32_t rtp_timestamp, std::span<const int16_t> /* audio */, webrtc::Buffer* encoded) {
    EncodedInfo info;
    info.encoded_timestamp = rtp_timestamp;
    info.send_even_if_empty = false;
    info.speech = true;
    info.encoder_type = CodecType::kOpus;

    std::vector<uint8_t> frame;
    if (!queue_->Pop(frame)) {
        info.encoded_bytes = 0;
        info.payload_type = 0;
        return info;
    }
    encoded->AppendData(frame.data(), frame.size());
    info.encoded_bytes = frame.size();
    info.payload_type = 0;
    return info;
}

}
