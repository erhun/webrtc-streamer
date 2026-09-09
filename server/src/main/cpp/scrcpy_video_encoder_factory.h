#ifndef SCRCPY_VIDEO_ENCODER_FACTORY_H_
#define SCRCPY_VIDEO_ENCODER_FACTORY_H_

#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <functional>
#include <memory>
#include <utility>
#include <vector>

namespace scrcpy {

class ScrcpyVideoEncoderFactory : public webrtc::VideoEncoderFactory {
public:
    ScrcpyVideoEncoderFactory(std::function<void(int, double)> bitrate, std::function<void()> keyframe)
        : bitrate_(std::move(bitrate)), keyframe_(std::move(keyframe)) {}
    std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
    std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment& env,
            const webrtc::SdpVideoFormat& format) override;
private:
    std::function<void(int, double)> bitrate_;
    std::function<void()> keyframe_;
};

}

#endif
