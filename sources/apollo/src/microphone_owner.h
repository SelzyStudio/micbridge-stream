/** A single microphone decoder owner; caller serializes all operations and packet writes. */
#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace microphone_owner {
  struct identity_t {
    std::string_view authenticated_device_uuid;
    std::uint32_t launch_session_id;
    bool encrypted_transport;
    bool can_view;
  };

  enum class claim_result_e { acquired, handed_off, denied, backend_failure };

  template<class Session>
  class state_t {
  public:
    Session *get() const { return _owner; }

    /**
     * The device UUID is supplied by the verified HTTPS certificate, never by an
     * incoming UDP packet. A replacement must have a newer authenticated launch.
     * Reinitializing the backend resets its Opus state and packet sequence/jitter queue.
     */
    template<class RevokeOwner, class ReleaseBackend, class InitBackend>
    claim_result_e claim(Session *candidate, const identity_t &identity,
        RevokeOwner revoke_owner, ReleaseBackend release_backend, InitBackend init_backend) {
      if (!candidate) return claim_result_e::denied;
      const bool handoff = _owner != nullptr;
      if (handoff && (candidate == _owner || _uuid.empty() ||
          identity.authenticated_device_uuid.empty() || _uuid != identity.authenticated_device_uuid ||
          identity.launch_session_id <= _launch_session_id || !_encrypted_transport ||
          !identity.encrypted_transport || !_can_view || !identity.can_view)) {
        return claim_result_e::denied;
      }

      if (handoff) {
        revoke_owner(_owner);
        // Revoke ownership before touching the backend. A failed replacement leaves
        // no owner; neither an old join nor an old packet can touch a future backend.
        clear();
        release_backend();
      }
      if (!init_backend()) return claim_result_e::backend_failure;

      _owner = candidate;
      _uuid = identity.authenticated_device_uuid;
      _launch_session_id = identity.launch_session_id;
      _encrypted_transport = identity.encrypted_transport;
      _can_view = identity.can_view;
      return handoff ? claim_result_e::handed_off : claim_result_e::acquired;
    }

    template<class ReleaseBackend>
    bool release(Session *candidate, std::uint32_t launch_session_id, ReleaseBackend release_backend) {
      if (_owner != candidate || _launch_session_id != launch_session_id || !_owner) return false;
      clear();
      release_backend();
      return true;
    }

  private:
    void clear() {
      _owner = nullptr;
      _uuid.clear();
      _launch_session_id = 0;
      _encrypted_transport = _can_view = false;
    }

    Session *_owner = nullptr;
    std::string _uuid;
    std::uint32_t _launch_session_id = 0;
    bool _encrypted_transport = false;
    bool _can_view = false;
  };
}  // namespace microphone_owner
