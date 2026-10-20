#include <assert.h>
#include <stdio.h>
#include "BackgroundStreamControl.h"

int main(void) {
    LC_BACKGROUND_STREAM_CONTROL state;
    uint8_t payload[4];
    BscInitialize(&state);
    assert(!BscPrepare(&state, payload));
    BscRequest(&state, true);
    for (int tick = 0; tick < 100; tick++) {
        assert(!BscPrepare(&state, payload)); // No pause before complete IDR.
    }
    BscNotifyFirstFrame(&state);
    assert(BscPrepare(&state, payload));
    assert(payload[0] == 1 && payload[1] == 1 && payload[2] == 0 && payload[3] == 0);
    // Failure must retry; only a successful send commits the state.
    assert(BscPrepare(&state, payload));
    BscCommit(&state, payload);
    assert(!BscPrepare(&state, payload));
    BscRequest(&state, false);
    assert(BscPrepare(&state, payload));
    assert(payload[1] == 0);
    BscCommit(&state, payload);
    assert(!BscPrepare(&state, payload));

    BscInitialize(&state);
    BscRequest(&state, true);
    BscRequest(&state, false); // Return before handshake: no stale pause.
    BscNotifyFirstFrame(&state);
    assert(!BscPrepare(&state, payload));
    BscRequest(&state, true);
    assert(BscPrepare(&state, payload));
    BscCommit(&state, payload);
    BscInitialize(&state); // New connection never inherits old pause/keyframe.
    BscRequest(&state, true);
    assert(!BscPrepare(&state, payload));
    puts("BACKGROUND_NATIVE_POLICY_TESTS_OK: first-frame, retry, resume, coalescing, session reset");
    return 0;
}
