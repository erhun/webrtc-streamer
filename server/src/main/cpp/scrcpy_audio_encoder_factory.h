#ifndef SCRCPY_AUDIO_ENCODER_FACTORY_H_
#define SCRCPY_AUDIO_ENCODER_FACTORY_H_

#include "api/audio_codecs/audio_encoder_factory.h"
#include "opus_frame_queue.h"

#include <memory>
#include <string>

namespace scrcpy {

webrtc::scoped_refptr<webrtc::AudioEncoderFactory> CreateScrcpyAudioEncoderFactory(
        const std::string& scheme, std::shared_ptr<OpusFrameQueue> opus_queue);

}

#endif
