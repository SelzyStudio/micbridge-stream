#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "MicrophoneKeepalive.h"

int main(void) {
    LC_MIC_KEEPALIVE_STATE state;
    uint8_t payload[MIC_KEEPALIVE_BYTES] = {0};
    MkaInitialize(&state, 100);
    assert(!MkaPrepare(&state, 100000, true, true, true, false, payload));
    MkaPublishReady(&state);
    assert(!MkaPrepare(&state, 5099, true, true, true, false, payload));
    assert(MkaPrepare(&state, 5100, true, true, true, false, payload));
    assert(sizeof(payload) == 4 && memcmp(payload, "PING", 4) == 0);
    assert(!MkaPrepare(&state, 5100, true, true, true, false, payload));
    assert(!MkaPrepare(&state, 10099, true, true, true, false, payload));
    assert(MkaPrepare(&state, 10100, true, true, true, false, payload));
    puts("PASS: readiness publication, exact 5-second cadence, four-byte PING");

    MkaInitialize(&state, 0);
    MkaPublishReady(&state);
    assert(!MkaPrepare(&state, 5000, false, true, true, false, payload));
    assert(!MkaPrepare(&state, 5000, true, false, true, false, payload));
    assert(!MkaPrepare(&state, 5000, true, true, false, false, payload));
    assert(!MkaPrepare(&state, 5000, true, true, true, true, payload));
    assert(state.nextPingMs == 5000);
    assert(MkaPrepare(&state, 5000, true, true, true, false, payload));
    puts("PASS: negotiated microphone, encryption, capability and interruption gates");

    assert(MkaPrepare(&state, 135000, true, true, true, false, payload));
    assert(state.nextPingMs == 140000);
    assert(!MkaPrepare(&state, 135001, true, true, true, false, payload));
    MkaWithdrawReady(&state);
    assert(!MkaPrepare(&state, 145000, true, true, true, false, payload));
    MkaInitialize(&state, 200000);
    assert(!MkaPrepare(&state, 300000, true, true, true, false, payload));
    MkaPublishReady(&state);
    assert(MkaPrepare(&state, 300000, true, true, true, false, payload));
    assert(state.nextPingMs == 305000);
    puts("PASS: delayed polling has no burst, teardown withdrawal and new-session reset");
    puts("MICROPHONE_KEEPALIVE_POLICY_TESTS_OK: 3 scenario groups");
    return 0;
}
