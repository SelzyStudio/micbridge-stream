#pragma once

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

#define MIC_KEEPALIVE_INTERVAL_MS 5000
#define MIC_KEEPALIVE_BYTES 4

typedef struct _LC_MIC_KEEPALIVE_STATE {
    atomic_bool socketReady;
    // Written before readiness publication, then owned by the control thread.
    uint64_t nextPingMs;
} LC_MIC_KEEPALIVE_STATE;

static inline void MkaInitialize(LC_MIC_KEEPALIVE_STATE* state, uint64_t nowMs) {
    atomic_store_explicit(&state->socketReady, false, memory_order_release);
    state->nextPingMs = nowMs + MIC_KEEPALIVE_INTERVAL_MS;
}

static inline void MkaPublishReady(LC_MIC_KEEPALIVE_STATE* state) {
    atomic_store_explicit(&state->socketReady, true, memory_order_release);
}

// The control thread must be joined before this state or its socket is closed.
static inline void MkaWithdrawReady(LC_MIC_KEEPALIVE_STATE* state) {
    atomic_store_explicit(&state->socketReady, false, memory_order_release);
}

static inline bool MkaPrepare(LC_MIC_KEEPALIVE_STATE* state, uint64_t nowMs,
        bool negotiated, bool encrypted, bool hostSupportsBackground, bool interrupted,
        uint8_t payload[MIC_KEEPALIVE_BYTES]) {
    if (!negotiated || !encrypted || !hostSupportsBackground || interrupted ||
            !atomic_load_explicit(&state->socketReady, memory_order_acquire) ||
            nowMs < state->nextPingMs) {
        return false;
    }
    // This is a transport heartbeat, not an Opus packet. It contains no audio
    // and consumes no microphone media sequence number, timestamp, or IV.
    payload[0] = 'P'; payload[1] = 'I'; payload[2] = 'N'; payload[3] = 'G';
    state->nextPingMs = nowMs + MIC_KEEPALIVE_INTERVAL_MS;
    return true;
}
