#include "scrcpy_passthrough_encoder.h"

#include "modules/video_coding/codecs/h264/include/h264_globals.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"

#include <utility>

#include <android/log.h>

#define SCP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "scrcpy_native", __VA_ARGS__)

namespace scrcpy {

EncodedVideoFrameBuffer::EncodedVideoFrameBuffer(std::vector<uint8_t> data, int width, int height, bool keyframe)
        : data_(std::move(data)), width_(width), height_(height), keyframe_(keyframe) {
}

webrtc::VideoFrameBuffer::Type EncodedVideoFrameBuffer::type() const {
    return webrtc::VideoFrameBuffer::Type::kNative;
}

int EncodedVideoFrameBuffer::width() const {
    return width_;
}

int EncodedVideoFrameBuffer::height() const {
    return height_;
}

webrtc::scoped_refptr<webrtc::I420BufferInterface> EncodedVideoFrameBuffer::ToI420() {
    return nullptr;
}

const uint8_t* EncodedVideoFrameBuffer::data() const {
    return data_.data();
}

size_t EncodedVideoFrameBuffer::size() const {
    return data_.size();
}

bool EncodedVideoFrameBuffer::keyframe() const {
    return keyframe_;
}

EncodedVideoTrackSource::EncodedVideoTrackSource() : webrtc::VideoTrackSource(false) {
}

void EncodedVideoTrackSource::OnEncodedFrame(const uint8_t* annexb, size_t len, int64_t pts_us, bool config, bool keyframe,
        int width, int height) {
    static int frame_count = 0;
    if (config) {
        SCP_LOGE("OnEncodedFrame: config len=%zu", len);
        sps_pps_.assign(annexb, annexb + len);
        return;
    }
    if (++frame_count % 60 == 1) {
        SCP_LOGE("OnEncodedFrame: frame#%d key=%d len=%zu %dx%d", frame_count, keyframe, len, width, height);
    }

    width_ = width;
    height_ = height;

    std::vector<uint8_t> data;
    if (keyframe && !sps_pps_.empty()) {
        data.reserve(sps_pps_.size() + len);
        data.insert(data.end(), sps_pps_.begin(), sps_pps_.end());
        data.insert(data.end(), annexb, annexb + len);
    } else {
        data.assign(annexb, annexb + len);
    }

    webrtc::scoped_refptr<EncodedVideoFrameBuffer> buffer =
            webrtc::make_ref_counted<EncodedVideoFrameBuffer>(std::move(data), width, height, keyframe);

    const int64_t capture_us = clock_.Normalize(pts_us);
    webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
            .set_video_frame_buffer(buffer)
            .set_timestamp_us(capture_us)
            .set_rtp_timestamp(MediaClock::Rtp90k(capture_us))
            .build();

    broadcaster_.OnFrame(frame);
}

webrtc::VideoSourceInterface<webrtc::VideoFrame>* EncodedVideoTrackSource::source() {
    return &broadcaster_;
}

ScrcpyPassthroughEncoder::ScrcpyPassthroughEncoder() {
}

ScrcpyPassthroughEncoder::~ScrcpyPassthroughEncoder() {
}

void ScrcpyPassthroughEncoder::SetKeyFrameRequestCallback(std::function<void()> callback) {
    webrtc::MutexLock lock(&mutex_);
    key_frame_request_callback_ = std::move(callback);
}

void ScrcpyPassthroughEncoder::SetBitrateCallback(std::function<void(int, double)> callback) {
    webrtc::MutexLock lock(&mutex_);
    bitrate_callback_ = std::move(callback);
}

int32_t ScrcpyPassthroughEncoder::InitEncode(const webrtc::VideoCodec* codec, const Settings& settings) {
    return WEBRTC_VIDEO_CODEC_OK;
}

int32_t ScrcpyPassthroughEncoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback* callback) {
    webrtc::MutexLock lock(&mutex_);
    callback_ = callback;
    return WEBRTC_VIDEO_CODEC_OK;
}

int32_t ScrcpyPassthroughEncoder::Release() {
    webrtc::MutexLock lock(&mutex_);
    callback_ = nullptr;
    return WEBRTC_VIDEO_CODEC_OK;
}

int32_t ScrcpyPassthroughEncoder::Encode(const webrtc::VideoFrame& frame,
        const std::vector<webrtc::VideoFrameType>* frame_types) {
    static int enc_count = 0;
    if (++enc_count <= 5 || enc_count % 10 == 0) {
        SCP_LOGE("Encode: #%d", enc_count);
    }
    if (frame_types != nullptr) {
        for (webrtc::VideoFrameType type : *frame_types) {
            if (type == webrtc::VideoFrameType::kVideoFrameKey) {
                std::function<void()> callback;
                {
                    webrtc::MutexLock lock(&mutex_);
                    callback = key_frame_request_callback_;
                }
                if (callback) {
                    callback();

                }
                break;
            }
        }
    }

    webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = frame.video_frame_buffer();
    if (buffer == nullptr || buffer->type() != webrtc::VideoFrameBuffer::Type::kNative) {
        return WEBRTC_VIDEO_CODEC_NO_OUTPUT;
    }

    const auto* encoded = static_cast<const EncodedVideoFrameBuffer*>(buffer.get());
    if (encoded == nullptr) {
        return WEBRTC_VIDEO_CODEC_ERROR;
    }

    webrtc::EncodedImage image;
    image.SetEncodedData(webrtc::EncodedImageBuffer::Create(encoded->data(), encoded->size()));
    image.SetRtpTimestamp(frame.rtp_timestamp());
    image.set_frame_type(encoded->keyframe() ? webrtc::VideoFrameType::kVideoFrameKey
            : webrtc::VideoFrameType::kVideoFrameDelta);
    image._encodedWidth = encoded->width();
    image._encodedHeight = encoded->height();
    static int dbg_count = 0;
    if (++dbg_count <= 2 && encoded->size() >= 8) {
        const uint8_t* d = encoded->data();
        SCP_LOGE("Encode: key=%d size=%zu data[0..7]=%02x %02x %02x %02x %02x %02x %02x %02x",
                encoded->keyframe(), encoded->size(), d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
    }

    webrtc::CodecSpecificInfo codec_specific;
    codec_specific.codecType = webrtc::kVideoCodecH264;
    codec_specific.codecSpecific.H264.packetization_mode = webrtc::H264PacketizationMode::NonInterleaved;
    codec_specific.codecSpecific.H264.idr_frame = encoded->keyframe();

    webrtc::EncodedImageCallback* callback;
    {
        webrtc::MutexLock lock(&mutex_);
        callback = callback_;
    }
    if (callback == nullptr) {
        return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
    }

    webrtc::EncodedImageCallback::Result result = callback->OnEncodedImage(image, &codec_specific);
    static int cb_count = 0;
    if (++cb_count % 30 == 1) {
        SCP_LOGE("Encode: OnEncodedImage result=%d cb#%d", (int) result.error, cb_count);
    }
    return result.error == webrtc::EncodedImageCallback::Result::OK ? WEBRTC_VIDEO_CODEC_OK : WEBRTC_VIDEO_CODEC_ERROR;
}

void ScrcpyPassthroughEncoder::SetRates(const RateControlParameters& parameters) {
    std::function<void(int, double)> callback;
    {
        webrtc::MutexLock lock(&mutex_);
        callback = bitrate_callback_;
    }
    if (callback) {
        callback(parameters.bitrate.get_sum_bps(), parameters.framerate_fps);

    }
}

ScrcpyPassthroughEncoder::EncoderInfo ScrcpyPassthroughEncoder::GetEncoderInfo() const {
    EncoderInfo info;
    info.supports_native_handle = true;
    info.is_hardware_accelerated = true;
    info.implementation_name = "MediaCodecPassthrough";
    return info;
}

}
