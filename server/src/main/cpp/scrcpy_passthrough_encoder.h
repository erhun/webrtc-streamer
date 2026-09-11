#ifndef SCRCPY_PASSTHROUGH_ENCODER_H_
#define SCRCPY_PASSTHROUGH_ENCODER_H_

#include "api/video/encoded_image.h"
#include "api/video/video_broadcaster.h"
#include "api/video/video_frame.h"
#include "api/video/video_frame_buffer.h"
#include "api/video/video_source_interface.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "pc/video_track_source.h"
#include "api/scoped_refptr.h"
#include "rtc_base/synchronization/mutex.h"

#include "media_clock.h"
#include <cstdint>
#include <functional>
#include <vector>
#include <utility>

namespace scrcpy {

class EncodedVideoFrameBuffer : public webrtc::VideoFrameBuffer {
public:
    EncodedVideoFrameBuffer(std::vector<uint8_t> data, int width, int height, bool keyframe);

    webrtc::VideoFrameBuffer::Type type() const override;
    int width() const override;
    int height() const override;
    webrtc::scoped_refptr<webrtc::I420BufferInterface> ToI420() override;

    const uint8_t* data() const;
    size_t size() const;
    bool keyframe() const;

private:
    const std::vector<uint8_t> data_;
    const int width_;
    const int height_;
    const bool keyframe_;
};

// VideoBroadcaster's default RequestRefreshFrame is a no-op. Forward requests
// without holding its sink lock; Java coalesces them on the encoder thread.
class RefreshVideoBroadcaster : public webrtc::VideoBroadcaster {
public:
    explicit RefreshVideoBroadcaster(std::function<void()> refresh) : refresh_(std::move(refresh)) {}
    void RequestRefreshFrame() override;
private:
    const std::function<void()> refresh_;
};

class EncodedVideoTrackSource : public webrtc::VideoTrackSource {
public:
    explicit EncodedVideoTrackSource(std::function<void()> refresh = {});

    void OnEncodedFrame(const uint8_t* annexb, size_t len, int64_t pts_us, bool config, bool keyframe, int width, int height);

protected:
    webrtc::VideoSourceInterface<webrtc::VideoFrame>* source() override;

private:
    MediaClock clock_;
    RefreshVideoBroadcaster broadcaster_;
    std::vector<uint8_t> sps_pps_;
    int width_ = 0;
    int height_ = 0;
};

class ScrcpyPassthroughEncoder final : public webrtc::VideoEncoder {
public:
    ScrcpyPassthroughEncoder();
    ~ScrcpyPassthroughEncoder() override;

    void SetKeyFrameRequestCallback(std::function<void()> callback);
    void SetBitrateCallback(std::function<void(int, double)> callback);

    int32_t InitEncode(const webrtc::VideoCodec* codec, const Settings& settings) override;
    int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback* callback) override;
    int32_t Release() override;
    int32_t Encode(const webrtc::VideoFrame& frame, const std::vector<webrtc::VideoFrameType>* frame_types) override;
    void SetRates(const RateControlParameters& parameters) override;
    EncoderInfo GetEncoderInfo() const override;

private:
    webrtc::Mutex mutex_;
    bool received_positive_rate_ = false;
    webrtc::EncodedImageCallback* callback_ = nullptr;
    std::function<void()> key_frame_request_callback_;
    std::function<void(int, double)> bitrate_callback_;
};

}
#endif
