// Runs actual microphone poll/init/destroy and connection shutdown bodies.
// Only OS/crypto/control-thread edges are replaced; no real networking occurs.
#include "Limelight-internal.h"
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

static int stubSendto(SOCKET socket, const char* data, int length, int flags,
                      const struct sockaddr* destination, int addressLength);
#define sendto stubSendto
#include "MicrophoneStream.c"
#undef sendto
#include "Connection.c"

static uint64_t clockMs;
static PLT_CRYPTO_CONTEXT fakeCrypto;
static bool failCrypto, failBind, socketOpen, blockSend;
static int closeCount, cryptoDestroyCount, sendCount, sendFailure;
static SOCKET lastSocket;
static struct sockaddr_in lastDestination;
static char lastPayload[4], cleanupOrder[32];
static int cleanupLength;
static HANDLE worker;
static atomic_bool sendEntered, allowSendReturn, sendFinished;
static bool workerJoined;

static void trace(char value) { cleanupOrder[cleanupLength++] = value; }
uint64_t PltGetMillis(void) { return clockMs; }
PPLT_CRYPTO_CONTEXT PltCreateCryptoContext(void) { return failCrypto ? NULL : &fakeCrypto; }
void PltDestroyCryptoContext(PPLT_CRYPTO_CONTEXT context) {
    assert(context == &fakeCrypto); cryptoDestroyCount++;
}
SOCKET bindUdpSocket(int family, struct sockaddr_storage* address, SOCKADDR_LEN length,
                     int bufferSize, int qos) {
    (void)address; (void)bufferSize;
    assert(family == AF_INET && length == sizeof(struct sockaddr_in));
    assert(qos == SOCK_QOS_TYPE_AUDIO);
    if (failBind) return INVALID_SOCKET;
    socketOpen = true;
    return (SOCKET)42;
}
void closeSocket(SOCKET socket) {
    assert(socket == 42 && socketOpen);
    // Old close-before-control-join order fails here with a sender in flight.
    if (worker != NULL) {
        assert(workerJoined && atomic_load(&sendFinished));
    }
    socketOpen = false; closeCount++; trace('m');
}
static int stubSendto(SOCKET socket, const char* data, int length, int flags,
                      const struct sockaddr* destination, int addressLength) {
    assert(socketOpen && socket == 42 && length == 4 && flags == 0);
    assert(addressLength == sizeof(struct sockaddr_in));
    assert(memcmp(data, "PING", 4) == 0);
    lastSocket = socket;
    memcpy(&lastDestination, destination, sizeof(lastDestination));
    memcpy(lastPayload, data, sizeof(lastPayload));
    sendCount++;
    if (blockSend) {
        atomic_store(&sendEntered, true);
        while (!atomic_load(&allowSendReturn)) Sleep(1);
        assert(socketOpen); // Descriptor cannot be closed/reused before return.
        atomic_store(&sendFinished, true);
    }
    return sendFailure ? SOCKET_ERROR : length;
}
int stopInputStream(void) { trace('I'); return 0; }
void stopAudioStream(void) { trace('A'); }
void stopVideoStream(void) { trace('V'); }
int stopControlStream(void) {
    trace('C');
    if (worker != NULL) {
        atomic_store(&allowSendReturn, true);
        assert(WaitForSingleObject(worker, 3000) == WAIT_OBJECT_0);
        workerJoined = true;
    }
    return 0;
}
void destroyInputStream(void) { trace('i'); }
void destroyVideoStream(void) { trace('v'); }
void destroyControlStream(void) { trace('c'); }
void destroyAudioStream(void) { trace('a'); }
void cleanupPlatform(void) { trace('p'); }

static void configure(void) {
    memset(&StreamConfig, 0, sizeof(StreamConfig));
    StreamConfig.enableMic = true;
    MicPortNumber = 48001;
    EncryptionFeaturesEnabled = SS_ENC_MICROPHONE;
    SunshineFeatureFlags = LI_FF_BACKGROUND_STREAM_CONTROL;
    AppVersionQuad[3] = -1;
    ConnectionInterrupted = false;
    memset(&RemoteAddr, 0, sizeof(RemoteAddr));
    struct sockaddr_in* address = (struct sockaddr_in*)&RemoteAddr;
    address->sin_family = AF_INET;
    address->sin_addr.s_addr = htonl(0xc0000201); // Documentation-only address.
    AddrLen = sizeof(*address);
    clockMs = 100;
}
static DWORD WINAPI pollWorker(LPVOID context) {
    (void)context;
    pollMicrophoneKeepalive(5100);
    return 0;
}

int main(void) {
    configure();
    pollMicrophoneKeepalive(100000); // Control can start before mic init.
    assert(sendCount == 0);
    failCrypto = true;
    assert(initializeMicrophoneStream() == -1);
    pollMicrophoneKeepalive(100000);
    assert(sendCount == 0 && !atomic_load(&micKeepalive.socketReady));
    failCrypto = false; failBind = true;
    (void)initializeMicrophoneStream();
    assert(micSocket == INVALID_SOCKET);
    pollMicrophoneKeepalive(100000);
    assert(sendCount == 0 && !atomic_load(&micKeepalive.socketReady));
    failBind = false;
    assert(initializeMicrophoneStream() == 0);
    assert(atomic_load(&micKeepalive.socketReady));
    pollMicrophoneKeepalive(5099);
    assert(sendCount == 0);
    puts("PASS: control-before-mic and failed initialization never send");

    micSequenceNumber = 17; micTimestamp = 123456; micRiKeyId = 99;
    pollMicrophoneKeepalive(5100);
    assert(sendCount == 1 && lastSocket == micSocket);
    assert(ntohs(lastDestination.sin_port) == 48001);
    assert(lastDestination.sin_addr.s_addr == htonl(0xc0000201));
    assert(memcmp(lastPayload, "PING", 4) == 0);
    assert(micSequenceNumber == 17 && micTimestamp == 123456 && micRiKeyId == 99);
    assert(micEncryptionCtx == &fakeCrypto);
    puts("PASS: same media socket/destination; no media sequence/timestamp/IV consumption");

    StreamConfig.enableMic = false; pollMicrophoneKeepalive(10100);
    StreamConfig.enableMic = true; MicPortNumber = 0; pollMicrophoneKeepalive(10100);
    MicPortNumber = 48001; EncryptionFeaturesEnabled = 0; pollMicrophoneKeepalive(10100);
    EncryptionFeaturesEnabled = SS_ENC_MICROPHONE; SunshineFeatureFlags = 0;
    pollMicrophoneKeepalive(10100);
    SunshineFeatureFlags = LI_FF_BACKGROUND_STREAM_CONTROL; AppVersionQuad[3] = 0;
    pollMicrophoneKeepalive(10100);
    AppVersionQuad[3] = -1; ConnectionInterrupted = true;
    pollMicrophoneKeepalive(10100);
    assert(sendCount == 1);
    ConnectionInterrupted = false; sendFailure = 1;
    pollMicrophoneKeepalive(10100);
    assert(sendCount == 2 && micSocket == 42 && socketOpen);
    assert(micSequenceNumber == 17 && micTimestamp == 123456);
    sendFailure = 0;
    puts("PASS: actual wrapper gates and nonfatal heartbeat send errors");

    destroyMicrophoneStream();
    pollMicrophoneKeepalive(20000);
    assert(sendCount == 2 && micSocket == INVALID_SOCKET && !socketOpen);
    assert(!atomic_load(&micKeepalive.socketReady));
    assert(initializeMicrophoneStream() == 0);
    assert(micSequenceNumber == 0 && micTimestamp == 0);
    pollMicrophoneKeepalive(5099);
    assert(sendCount == 2);
    puts("PASS: destroy withdrawal and new-session media/cadence reset");

    cleanupLength = 0; closeCount = 0; workerJoined = false; blockSend = true;
    atomic_store(&sendEntered, false);
    atomic_store(&allowSendReturn, false);
    atomic_store(&sendFinished, false);
    worker = CreateThread(NULL, 0, pollWorker, NULL, 0, NULL);
    assert(worker != NULL);
    for (int wait = 0; !atomic_load(&sendEntered) && wait < 3000; wait++) Sleep(1);
    assert(atomic_load(&sendEntered));
    stage = STAGE_MICROPHONE_STREAM_INIT;
    LiStopConnection();
    assert(stage == STAGE_NONE && ConnectionInterrupted);
    assert(workerJoined && closeCount == 1 && !socketOpen);
    assert(cleanupLength == 10 && memcmp(cleanupOrder, "IAVCmivcap", 10) == 0);
    CloseHandle(worker); worker = NULL; blockSend = false;
    int cleanupBefore = cleanupLength;
    LiStopConnection();
    assert(cleanupLength == cleanupBefore && closeCount == 1);
    puts("PASS: actual stop joins in-flight sender before close/reuse; double stop inert");

    cleanupLength = 0; closeCount = 0;
    stage = STAGE_CONTROL_STREAM_START;
    LiStopConnection();
    assert(stage == STAGE_NONE && closeCount == 0);
    assert(cleanupLength == 6 && memcmp(cleanupOrder, "Civcap", 6) == 0);
    puts("PASS: partial connection stop skips uninitialized microphone stage");
    printf("MICROPHONE_KEEPALIVE_LIFECYCLE_TESTS_OK: 6 scenario groups, %d fake UDP sends, %d crypto cleanups\n",
           sendCount, cryptoDestroyCount);
    return 0;
}
