/** Per-session media activity; independent of transport and encoder lifetimes. */
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <optional>
#include <string_view>

namespace stream_activity {
  constexpr std::uint32_t feature_flag = 0x04;
  constexpr std::uint16_t packet_type = 0x3003;

  // Fixed, versioned packet. No shell strings, IDs, rates or cross-session targets.
  inline std::optional<bool> parse_command(std::string_view payload) {
    if (payload.size() != 4 || payload[0] != 1 ||
        (payload[1] != 0 && payload[1] != 1) || payload[2] != 0 || payload[3] != 0) {
      return std::nullopt;
    }
    return payload[1] == 1;
  }

  inline bool authorized(bool encrypted_control, bool running, bool can_view) {
    return encrypted_control && running && can_view;
  }

  class state_t {
  public:
    bool set_background(bool background) {
      return _background.exchange(background, std::memory_order_acq_rel) != background;
    }

    bool background() const {
      return _background.load(std::memory_order_acquire);
    }

  private:
    std::atomic_bool _background {false};
  };

  using state_ptr = std::shared_ptr<state_t>;

  inline bool paused(const state_ptr &state) {
    return state && state->background();
  }

  enum class command_result_e { denied, malformed, unchanged, paused, resumed };

  template<class OnResume>
  command_result_e apply_command(std::string_view payload, bool encrypted_control,
      bool running, bool can_view, const state_ptr &state, OnResume on_resume) {
    if (!state || !authorized(encrypted_control, running, can_view)) {
      return command_result_e::denied;
    }
    auto background = parse_command(payload);
    if (!background) {
      return command_result_e::malformed;
    }
    if (!state->set_background(*background)) {
      return command_result_e::unchanged;
    }
    if (!*background) {
      on_resume();
      return command_result_e::resumed;
    }
    return command_result_e::paused;
  }

  template<class Sessions, class IsPaused>
  bool all_paused(const Sessions &sessions, IsPaused is_paused) {
    // Empty capture groups must continue processing newly queued sessions.
    if (sessions.empty()) {
      return false;
    }
    for (const auto &session : sessions) {
      if (!is_paused(session)) {
        return false;
      }
    }
    return true;
  }
}
