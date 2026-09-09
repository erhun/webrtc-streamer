#include "scrcpy_opus_audio_encoder.h"

namespace scrcpy {

ScrcpyOpusAudioEncoder::ScrcpyOpusAudioEncoder() {
}

void ScrcpyOpusAudioEncoder::OnEncodedFrame(const uint8_t* opus, size_t len, int64_t pts_us) {
    webrtc::MutexLock lock(&mutex_);
    pending_.emplace_back(opus, opus + len);
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
    return -1;
}

void ScrcpyOpusAudioEncoder::Reset() {
    webrtc::MutexLock lock(&mutex_);
    pending_.clear();
}

std::optional<std::pair<webrtc::TimeDelta, webrtc::TimeDelta>> ScrcpyOpusAudioEncoder::GetFrameLengthRange() const {
    return std::nullopt;
}

webrtc::AudioEncoder::EncodedInfo ScrcpyOpusAudioEncoder::EncodeImpl(uint32_t rtp_timestamp,
        std::span<const int16_t> audio, webrtc::Buffer* encoded) {
    webrtc::MutexLock lock(&mutex_);
    webrtc::AudioEncoder::EncodedInfo info;
    if (pending_.empty()) {
        info.encoded_bytes = 0;
        return info;
    }

    const std::vector<uint8_t>& packet = pending_.front();
    encoded->SetData(packet.data(), packet.size());
    pending_.pop_front();

    info.encoded_bytes = packet.size();
    info.encoded_timestamp = rtp_timestamp;
    info.payload_type = 111;
    info.send_even_if_empty = false;
    info.speech = false;
    info.encoder_type = webrtc::AudioEncoder::CodecType::kOpus;
    return info;
}

}
