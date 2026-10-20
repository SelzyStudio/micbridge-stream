#pragma once

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

// The JNI caller only updates atomics. The control thread owns sent state.
typedef struct _LC_BACKGROUND_STREAM_CONTROL {
    atomic_bool requested;
    atomic_bool firstFrameReceived;
    bool sent;
} LC_BACKGROUND_STREAM_CONTROL;

static inline void BscInitialize(LC_BACKGROUND_STREAM_CONTROL* state) {
    atomic_store(&state->requested, false);
    atomic_store(&state->firstFrameReceived, false);
    state->sent = false;
}

static inline void BscRequest(LC_BACKGROUND_STREAM_CONTROL* state, bool background) {
    atomic_store(&state->requested, background);
}

static inline void BscNotifyFirstFrame(LC_BACKGROUND_STREAM_CONTROL* state) {
    atomic_store(&state->firstFrameReceived, true);
}

static inline bool BscPrepare(LC_BACKGROUND_STREAM_CONTROL* state, uint8_t payload[4]) {
    bool background = atomic_load(&state->requested);
    if (background == state->sent ||
        (background && !atomic_load(&state->firstFrameReceived))) {
        return false;
    }
    payload[0] = 1;
    payload[1] = background ? 1 : 0;
    payload[2] = payload[3] = 0;
    return true;
}

static inline void BscCommit(LC_BACKGROUND_STREAM_CONTROL* state, const uint8_t payload[4]) {
    state->sent = payload[1] != 0;
}
