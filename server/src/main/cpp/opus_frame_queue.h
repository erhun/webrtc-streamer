#ifndef OPUS_FRAME_QUEUE_H_
#define OPUS_FRAME_QUEUE_H_

#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

namespace scrcpy {

class OpusFrameQueue {
public:
    void Push(const uint8_t* data, size_t len) {
        std::lock_guard<std::mutex> lock(mutex_);
        queue_.emplace_back(data, data + len);
    }

    bool Pop(std::vector<uint8_t>& out) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (queue_.empty()) { return false; }
        out = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    void Clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        queue_.clear();
    }

private:
    std::mutex mutex_;
    std::deque<std::vector<uint8_t>> queue_;
};

}

#endif
