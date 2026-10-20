// Actual stopControlStream + graceful-disconnect helper; ENet clock/ACK and OS edges are fake.
#include "Limelight-internal.h"
#include "ConnectionStopPolicy.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "ControlStream.c"
#include "Misc.c"

CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;
DECODER_RENDERER_CALLBACKS VideoCallbacks;
int NegotiatedVideoFormat;
bool ReferenceFrameInvalidationSupported;
volatile bool ConnectionInterrupted;

static LC_CONNECTION_STOP_POLICY policy;
static ENetHost fakeHost;
static ENetPeer fakePeer;
static unsigned waitedMs;
static int joins, interrupts, gracefulRequests, immediateRequests, destroyed;

bool isConnectionRecoveryStopRequested(void) { return CspIsRecoveryRequested(&policy); }
void LbqSignalQueueShutdown(PLINKED_BLOCKING_QUEUE queue) { (void)queue; }
void LbqSignalQueueDrain(PLINKED_BLOCKING_QUEUE queue) { (void)queue; }
void PltSetEvent(PLT_EVENT* event) { (void)event; }
void PltInterruptThread(PLT_THREAD* thread) { thread->cancelled = true; interrupts++; }
void PltJoinThread(PLT_THREAD* thread) { assert(thread->cancelled); joins++; }
void shutdownTcpSocket(SOCKET socket) { (void)socket; }
void closeSocket(SOCKET socket) { (void)socket; }
int enet_host_service(ENetHost* host, ENetEvent* event, enet_uint32 timeout) {
    (void)event;
    assert(host == &fakeHost && joins == 4);
    waitedMs += timeout; // Simulate a lost old route: no ACK ever arrives.
    return 0;
}
void enet_peer_disconnect_later(ENetPeer* target, enet_uint32 data) {
    (void)data;
    assert(target == &fakePeer && joins == 4);
    gracefulRequests++;
}
void enet_peer_disconnect_now(ENetPeer* target, enet_uint32 data) {
    (void)data;
    assert(target == &fakePeer && joins == 4);
    immediateRequests++;
}
void enet_host_destroy(ENetHost* host) { assert(host == &fakeHost && joins == 4); destroyed++; }
void enet_packet_destroy(ENetPacket* packet) { (void)packet; }

static void configure(void) {
    memset(&fakeHost, 0, sizeof(fakeHost));
    memset(&fakePeer, 0, sizeof(fakePeer));
    fakePeer.state = ENET_PEER_STATE_CONNECTED;
    client = &fakeHost;
    peer = &fakePeer;
    ctlSock = INVALID_SOCKET;
    ConnectionInterrupted = true;
    ReferenceFrameInvalidationSupported = false;
    waitedMs = 0;
    joins = interrupts = gracefulRequests = immediateRequests = destroyed = 0;
}

static void checkNormal(void) {
    configure();
    assert(stopControlStream() == 0);
    assert(joins == 4 && interrupts == 4 && destroyed == 1);
    assert(gracefulRequests == 1 && immediateRequests == 0 && waitedMs == 2000);
    assert(client == NULL && peer == NULL);
}

static void checkRecovery(void) {
    configure();
    assert(stopControlStream() == 0);
    assert(joins == 4 && interrupts == 4 && destroyed == 1);
    assert(gracefulRequests == 0 && immediateRequests == 1 && waitedMs == 0);
    assert(client == NULL && peer == NULL);
}

int main(void) {
    CspPrepareStart(&policy);
    assert(CspBeginStart(&policy));
    checkNormal();
    puts("PASS: ordinary close keeps actual 2000 ms graceful ACK wait after all joins");

    CspRequestRecovery(&policy);
    checkRecovery();
    puts("PASS: recovery close keeps every join but performs best-effort disconnect with zero ACK wait");

    CspPrepareStart(&policy);
    assert(CspBeginStart(&policy));
    assert(!CspIsRecoveryRequested(&policy));
    checkNormal();
    puts("PASS: next prepared generation cannot inherit the old recovery mode");

    CspPrepareStart(&policy);
    CspRequestRecovery(&policy); // Cancellation between prepare and native entry.
    assert(CspBeginStart(&policy));
    assert(CspIsRecoveryRequested(&policy));
    checkRecovery();
    puts("PASS: an early cancellation survives prepared start consumption");

    CspRequestRecovery(&policy);
    assert(!CspBeginStart(&policy)); // Legacy standalone C caller, no prepare hook.
    assert(!CspIsRecoveryRequested(&policy));
    checkNormal();
    puts("PASS: standalone legacy start also resets recovery mode");
    return 0;
}
