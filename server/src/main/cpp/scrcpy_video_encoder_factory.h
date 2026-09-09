#ifndef SCRCPY_VIDEO_ENCODER_FACTORY_H_
#define SCRCPY_VIDEO_ENCODER_FACTORY_H_

#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>
#include <vector>

namespace scrcpy {

class ScrcpyVideoEncoderFactory : public webrtc::VideoEncoderFactory {
public:
    std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
    std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment& env,
            const webrtc::SdpVideoFormat& format) override;
};

}

#endif
