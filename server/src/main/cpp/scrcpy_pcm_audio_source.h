#ifndef SCRCPY_PCM_AUDIO_SOURCE_H_
#define SCRCPY_PCM_AUDIO_SOURCE_H_
#include "api/media_stream_interface.h"
#include "api/notifier.h"
#include "rtc_base/synchronization/mutex.h"
#include "pcm_framer.h"
#include <algorithm>
#include <vector>
namespace scrcpy {
class PcmAudioSource : public webrtc::Notifier<webrtc::AudioSourceInterface> {
public:
    SourceState state() const override { return kLive; }
    bool remote() const override { return false; }
    void AddSink(webrtc::AudioTrackSinkInterface* sink) override {
        webrtc::MutexLock lock(&mutex_);
        if (std::find(sinks_.begin(), sinks_.end(), sink) == sinks_.end()) { sinks_.push_back(sink); }
    }
    void RemoveSink(webrtc::AudioTrackSinkInterface* sink) override {
        webrtc::MutexLock lock(&mutex_);
        std::erase(sinks_, sink);
        if (sinks_.empty()) { framer_.Reset(); }
    }
    void Push(const uint8_t* data, size_t len, int64_t pts_us) {
        webrtc::MutexLock lock(&mutex_);
        if (sinks_.empty()) { framer_.Reset(); return; }
        framer_.Push(data, len, pts_us, [this](const int16_t* samples, int64_t pts) {
            for (auto* sink : sinks_) { sink->OnData(samples, 16, 48000, 2, 480, pts / 1000); }
        });
    }
private:
    webrtc::Mutex mutex_;
    std::vector<webrtc::AudioTrackSinkInterface*> sinks_;
    PcmFramer framer_;
};
}
#endif
