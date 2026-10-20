// Actual native start/stop generation bodies, with transport/OS stages replaced.
#include "Limelight-internal.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "Connection.c"

static int initialized, started, controlStops, microphoneCloses;
static bool cancelDuringVideoStart, lastControlStopRecovery, controlJoined;
static void stageStarting(int value) {
    if (cancelDuringVideoStart && value == STAGE_VIDEO_STREAM_START) {
        LiInterruptConnectionForReconnect();
    }
}
static void stageComplete(int value) { (void)value; }
static void stageFailed(int value, int error) { (void)value; assert(error != 0); }
static void connected(void) { started++; }
static void terminated(int error) { (void)error; assert(false); }
void fixupMissingCallbacks(PDECODER_RENDERER_CALLBACKS* video, PAUDIO_RENDERER_CALLBACKS* audio,
        PCONNECTION_LISTENER_CALLBACKS* listener) { (void)video; (void)audio; (void)listener; }
int extractVersionQuadFromString(const char* value, int* version) {
    return sscanf(value, "%d.%d.%d.%d", &version[0], &version[1], &version[2], &version[3]) == 4 ? 0 : -1;
}
int initializePlatform(void) { initialized++; return 0; }
void cleanupPlatform(void) {}
int resolveHostName(const char* host, int family, int port, struct sockaddr_storage* address,
        SOCKADDR_LEN* length) {
    (void)host; (void)family; (void)port;
    if (ConnectionInterrupted) return -1;
    memset(address, 0, sizeof(*address));
    address->ss_family = AF_INET;
    *length = sizeof(struct sockaddr_in);
    return 0;
}
bool isNat64SynthesizedAddress(struct sockaddr_storage* address) { (void)address; return false; }
bool isPrivateNetworkAddress(struct sockaddr_storage* address) { (void)address; return true; }
int initializeAudioStream(void) { return 0; }
void destroyAudioStream(void) {}
int performRtspHandshake(PSERVER_INFORMATION info) { (void)info; MicPortNumber = 48001; return 0; }
int initializeControlStream(void) { return 0; }
void destroyControlStream(void) {}
void initializeVideoStream(void) {}
void destroyVideoStream(void) {}
int initializeInputStream(void) { return 0; }
void destroyInputStream(void) {}
int startControlStream(void) { controlJoined = false; return 0; }
int startVideoStream(void* context, int flags) { (void)context; (void)flags; return ConnectionInterrupted ? -1 : 0; }
int startAudioStream(void* context, int flags) { (void)context; (void)flags; return 0; }
int startInputStream(void) { return 0; }
int initializeMicrophoneStream(void) { return 0; }
int stopInputStream(void) { return 0; }
void stopAudioStream(void) {}
void stopVideoStream(void) {}
int stopControlStream(void) {
    controlStops++;
    lastControlStopRecovery = isConnectionRecoveryStopRequested();
    controlJoined = true;
    return 0;
}
void destroyMicrophoneStream(void) { assert(controlJoined); microphoneCloses++; }
int LiSendMouseMoveEvent(short x, short y) { (void)x; (void)y; return 0; }
void PltSleepMs(int milliseconds) { (void)milliseconds; }
int PltCreateThread(const char* name, ThreadEntry entry, void* context, PLT_THREAD* thread) {
    (void)name; (void)entry; (void)context; (void)thread; assert(false); return -1;
}
void PltDetachThread(PLT_THREAD* thread) { (void)thread; }

static int startValid(void) {
    SERVER_INFORMATION info = {0};
    STREAM_CONFIGURATION config = {0};
    CONNECTION_LISTENER_CALLBACKS listener = {0};
    DECODER_RENDERER_CALLBACKS video = {0};
    AUDIO_RENDERER_CALLBACKS audio = {0};
    info.address = "192.0.2.1";
    info.serverInfoAppVersion = "7.1.431.-1";
    info.serverCodecModeSupport = 1;
    config.width = 1280; config.height = 720; config.fps = 60;
    config.packetSize = 1024;
    config.streamingRemotely = STREAM_CFG_LOCAL;
    config.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
    config.supportedVideoFormats = VIDEO_FORMAT_H264;
    config.enableMic = true;
    listener.stageStarting = stageStarting;
    listener.stageComplete = stageComplete;
    listener.stageFailed = stageFailed;
    listener.connectionStarted = connected;
    listener.connectionTerminated = terminated;
    return LiStartConnection(&info, &config, &listener, &video, &audio, NULL, 0, NULL, 0);
}

int main(void) {
    LiPrepareConnectionStart();
    assert(!ConnectionInterrupted && !isConnectionRecoveryStopRequested());
    LiInterruptConnectionForReconnect();
    // Exact race: ownership was prepared/published, but native entry has not happened.
    assert(LiStartConnection(NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, 0) == -1);
    assert(ConnectionInterrupted && isConnectionRecoveryStopRequested());
    assert(initialized == 0 && stage == STAGE_NONE);
    puts("PASS: actual LiStartConnection preserves prepared pre-entry cancellation without initializing resources");

    LiPrepareConnectionStart();
    assert(startValid() == 0);
    assert(!ConnectionInterrupted && !isConnectionRecoveryStopRequested() && started == 1);
    LiStopConnection();
    assert(!lastControlStopRecovery && microphoneCloses == 1 && stage == STAGE_NONE);
    puts("PASS: next actual successful prepared generation resets fast mode; normal close stays graceful");

    LiPrepareConnectionStart();
    cancelDuringVideoStart = true;
    assert(startValid() == -1);
    assert(isConnectionRecoveryStopRequested() && lastControlStopRecovery);
    assert(stage == STAGE_NONE && microphoneCloses == 1);
    cancelDuringVideoStart = false;
    puts("PASS: in-flight start failure automatically cleans its initialized control stage in recovery mode");

    // Standalone native callers do not use Java's optional preparation hook.
    assert(startValid() == 0);
    assert(!isConnectionRecoveryStopRequested() && !ConnectionInterrupted);
    LiStopConnection();
    assert(!lastControlStopRecovery && microphoneCloses == 2 && stage == STAGE_NONE);
    puts("PASS: actual legacy unprepared start resets fast mode and closes microphone only after control join");

    LiPrepareConnectionStart();
    assert(startValid() == 0);
    LiStopConnectionForReconnect();
    assert(lastControlStopRecovery && microphoneCloses == 3 && stage == STAGE_NONE);
    int stopsBefore = controlStops;
    LiStopConnectionForReconnect();
    assert(controlStops == stopsBefore && microphoneCloses == 3);
    puts("PASS: actual fast close is idempotent and retains control-before-microphone disposal order");
    return 0;
}
