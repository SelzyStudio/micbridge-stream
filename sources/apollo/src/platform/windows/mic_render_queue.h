/**
 * @file src/platform/windows/mic_render_queue.h
 * @brief Decoded microphone frames awaiting a successful render submission.
 */
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>
#include <utility>
#include <vector>

namespace platf::audio {
  class mic_render_queue_t {
  public:
    struct packet_t {
      std::uint16_t sequence_number;
      float peak;
    };

    float append(const float *samples, std::size_t count, std::uint16_t sequence_number) {
      if (samples == nullptr || count == 0) {
        return 0.0f;
      }
      queued_packet_t packet {{sequence_number, 0.0f}, {}, 0, false};
      packet.samples.reserve(count);
      for (std::size_t frame = 0; frame < count; ++frame) {
        const float sample = std::clamp(samples[frame], -1.0f, 1.0f);
        packet.peak = std::max(packet.peak, std::fabs(sample));
        packet.samples.push_back(sample);
      }
      const auto peak = packet.peak;
      queued_frames += count;
      packets.push_back(std::move(packet));
      return peak;
    }

    std::size_t size() const {
      return queued_frames;
    }

    std::uint16_t front_sequence_number() const {
      return packets.empty() ? 0 : packets.front().sequence_number;
    }

    // Preparing a WASAPI buffer does not consume frames or report rendering.
    void copy_stereo(float *destination, std::size_t count) const {
      std::size_t written = 0;
      for (const auto &packet : packets) {
        for (auto frame = packet.offset; frame < packet.samples.size() && written < count; ++frame, ++written) {
          destination[written * 2] = packet.samples[frame];
          destination[written * 2 + 1] = packet.samples[frame];
        }
        if (written == count) {
          break;
        }
      }
    }

    // Call only after ReleaseBuffer succeeds. A split packet is counted once,
    // after all of its frames have been accepted by WASAPI.
    std::vector<packet_t> commit(std::size_t count) {
      return consume(count, false);
    }

    template<class Submit>
    std::optional<std::vector<packet_t>> submit_stereo(float *destination, std::size_t count, Submit &&submit) {
      copy_stereo(destination, count);
      if (!submit()) {
        return std::nullopt;
      }
      return commit(count);
    }

    void trim_to(std::size_t maximum) {
      if (queued_frames > maximum) {
        consume(queued_frames - maximum, true);
      }
    }

    void clear() {
      packets.clear();
      queued_frames = 0;
    }

  private:
    struct queued_packet_t: packet_t {
      std::vector<float> samples;
      std::size_t offset;
      bool discarded;
    };

    std::vector<packet_t> consume(std::size_t count, bool discard) {
      std::vector<packet_t> completed;
      while (count != 0 && !packets.empty()) {
        auto &packet = packets.front();
        const auto consumed = std::min(count, packet.samples.size() - packet.offset);
        packet.offset += consumed;
        packet.discarded = packet.discarded || discard;
        count -= consumed;
        queued_frames -= consumed;
        if (packet.offset == packet.samples.size()) {
          if (!packet.discarded) {
            completed.push_back({packet.sequence_number, packet.peak});
          }
          packets.pop_front();
        }
      }
      return completed;
    }

    std::deque<queued_packet_t> packets;
    std::size_t queued_frames = 0;
  };
}  // namespace platf::audio
