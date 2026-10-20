package com.limelight;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowService;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, shadows = ShadowMoonBridge.class)
public class StreamSessionServiceTest {
    private Application application;

    @Before public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    @Test public void manifestDeclaresPrivateConnectedDeviceServiceAndItsPrerequisites() throws Exception {
        PackageManager manager = application.getPackageManager();
        ServiceInfo service = manager.getServiceInfo(new ComponentName(application, StreamSessionService.class), 0);
        assertFalse(service.exported);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.getForegroundServiceType());
        }
        PackageInfo info = manager.getPackageInfo(application.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> permissions = Arrays.asList(info.requestedPermissions);
        assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE));
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"));
        assertTrue(permissions.contains(Manifest.permission.CHANGE_NETWORK_STATE));
        assertFalse(permissions.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"));
    }

    @Test public void notificationReturnsToTheSameGameAndDisconnectUsesAPrivateServiceIntent() throws Exception {
        Intent original = new Intent().setClassName("external.package", "external.Activity")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("original_launch", "kept");
        String token = StreamSessionService.start(application, original);
        Intent start = nextService();
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1));
        Notification notification = Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(notification);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        notification.contentIntent.send();
        Intent returned = Shadows.shadowOf(application).getNextStartedActivity();
        assertEquals(new ComponentName(application, Game.class), returned.getComponent());
        assertEquals(StreamSessionService.ACTION_RETURN_TO_SESSION, returned.getAction());
        assertEquals(token, returned.getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        assertEquals("kept", returned.getStringExtra("original_launch"));
        assertEquals(0, returned.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        notification.actions[0].actionIntent.send();
        Intent disconnect = nextService();
        assertEquals(new ComponentName(application, StreamSessionService.class), disconnect.getComponent());
        service.onStartCommand(disconnect, 0, 2);
        service.onStartCommand(disconnect, 0, 3);
        List<Intent> requests = broadcasts(StreamSessionService.ACTION_DISCONNECT_REQUESTED);
        assertEquals(1, requests.size());
        assertEquals(application.getPackageName(), requests.get(0).getPackage());
        assertEquals(token, requests.get(0).getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf()); // Owner ACK does the actual teardown.
        controller.destroy();
    }

    @Test public void oldOwnerStopCannotStopTheNewSession() {
        Context oldOwner = new ContextWrapper(application);
        Context newOwner = new ContextWrapper(application);
        String oldToken = StreamSessionService.start(oldOwner, new Intent());
        Intent first = nextService();
        String newToken = StreamSessionService.start(newOwner, new Intent());
        Intent second = nextService();
        assertNotEquals(oldToken, newToken);
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        service.onStartCommand(first, 0, 1);
        service.onStartCommand(second, 0, 2);
        StreamSessionService.stop(oldOwner);
        service.onStartCommand(nextService(), 0, 3);
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        StreamSessionService.stop(newOwner, newToken);
        service.onStartCommand(nextService(), 0, 4);
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        controller.destroy();
    }

    @Test public void backgroundUpdatesRequireTheCurrentToken() {
        String token = StreamSessionService.start(application, new Intent());
        Intent start = nextService();
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        service.onStartCommand(start, 0, 1);
        StreamSessionService.setBackground(application, "old-session", true);
        service.onStartCommand(nextService(), 0, 2);
        assertEquals(application.getString(R.string.stream_session_active),
                Shadows.shadowOf(service).getLastForegroundNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
        StreamSessionService.setBackground(application, token, true);
        service.onStartCommand(nextService(), 0, 3);
        android.app.NotificationManager manager = (android.app.NotificationManager)
                application.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification current = Shadows.shadowOf(manager).getAllNotifications().get(0);
        assertEquals(application.getString(R.string.stream_session_background),
                current.extras.getCharSequence(Notification.EXTRA_TEXT));
        controller.destroy();
    }

    @Test @Config(shadows = {ShadowMoonBridge.class, PendingStartShadowService.class})
    public void pendingBackgroundAfterStopRetriesAtLatestStartAndCannotRepublishNotification() {
        String token = StreamSessionService.start(application, new Intent());
        Intent start = nextService();
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        PendingStartShadowService shadow = Shadow.extract(service);
        service.onStartCommand(start, 0, 1);
        StreamSessionService.stop(application, token);
        Intent stop = nextService();
        StreamSessionService.setBackground(application, token, true);
        Intent lateBackground = nextService();
        shadow.latestStartId = 3;
        service.onStartCommand(stop, 0, 2);
        assertFalse(shadow.isStoppedBySelf()); // Android already accepted the pending id=3.
        assertTrue(shadow.isForegroundStopped());
        assertTrue(notifications().isEmpty());
        service.onStartCommand(lateBackground, 0, 3);
        assertTrue(shadow.isStoppedBySelf());
        assertEquals(3, shadow.getStopSelfResultId());
        assertTrue(notifications().isEmpty());
        controller.destroy();
    }

    @Test public void reconnectingStatusRetainsReturnAndDisconnectActionsAndRestoresVisibilityStatus() throws Exception {
        String token = StreamSessionService.start(application, new Intent().putExtra("original_launch", "kept"));
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        service.onStartCommand(nextService(), 0, 1);
        StreamSessionService.setReconnecting(application, token, true);
        service.onStartCommand(nextService(), 0, 2);
        Notification reconnecting = notifications().get(0);
        assertEquals(application.getString(R.string.stream_session_reconnecting),
                reconnecting.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue((reconnecting.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        reconnecting.contentIntent.send();
        Intent returned = Shadows.shadowOf(application).getNextStartedActivity();
        assertEquals(new ComponentName(application, Game.class), returned.getComponent());
        assertEquals(StreamSessionService.ACTION_RETURN_TO_SESSION, returned.getAction());
        assertEquals(token, returned.getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        assertEquals("kept", returned.getStringExtra("original_launch"));
        StreamSessionService.setBackground(application, token, true);
        service.onStartCommand(nextService(), 0, 3);
        assertEquals(application.getString(R.string.stream_session_reconnecting),
                notifications().get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        StreamSessionService.setReconnecting(application, token, false);
        service.onStartCommand(nextService(), 0, 4);
        assertEquals(application.getString(R.string.stream_session_background),
                notifications().get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        StreamSessionService.setBackground(application, token, false);
        service.onStartCommand(nextService(), 0, 5);
        assertEquals(application.getString(R.string.stream_session_active),
                notifications().get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        reconnecting.actions[0].actionIntent.send();
        service.onStartCommand(nextService(), 0, 6);
        assertEquals(1, broadcasts(StreamSessionService.ACTION_DISCONNECT_REQUESTED).size());
        assertEquals(token, broadcasts(StreamSessionService.ACTION_DISCONNECT_REQUESTED).get(0)
                .getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        controller.destroy();
    }

    @Test public void reconnectingUpdatesRequireCurrentOwnerAndNewStartClearsOldStatus() {
        String oldToken = StreamSessionService.start(application, new Intent());
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        service.onStartCommand(nextService(), 0, 1);
        StreamSessionService.setReconnecting(application, oldToken, true);
        service.onStartCommand(nextService(), 0, 2);
        String newToken = StreamSessionService.start(new ContextWrapper(application), new Intent());
        service.onStartCommand(nextService(), 0, 3);
        assertEquals(application.getString(R.string.stream_session_active),
                Shadows.shadowOf(service).getLastForegroundNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
        StreamSessionService.setReconnecting(application, oldToken, true);
        service.onStartCommand(nextService(), 0, 4);
        assertEquals(application.getString(R.string.stream_session_active),
                notifications().get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        StreamSessionService.setReconnecting(application, newToken, true);
        service.onStartCommand(nextService(), 0, 5);
        StreamSessionService.setReconnecting(application, oldToken, false);
        service.onStartCommand(nextService(), 0, 6);
        assertEquals(application.getString(R.string.stream_session_reconnecting),
                notifications().get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        controller.destroy();
    }

    @Test @Config(shadows = {ShadowMoonBridge.class, PendingStartShadowService.class})
    public void queuedReconnectingAfterStopCannotRepublishOrReviveTheClosedSession() {
        String token = StreamSessionService.start(application, new Intent());
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        PendingStartShadowService shadow = Shadow.extract(service);
        service.onStartCommand(nextService(), 0, 1);
        StreamSessionService.stop(application, token);
        Intent stop = nextService();
        StreamSessionService.setReconnecting(application, token, true);
        Intent queuedReconnecting = nextService();
        shadow.latestStartId = 3;
        service.onStartCommand(stop, 0, 2);
        assertFalse(shadow.isStoppedBySelf());
        assertTrue(shadow.isForegroundStopped());
        assertTrue(notifications().isEmpty());
        service.onStartCommand(queuedReconnecting, 0, 3);
        assertTrue(shadow.isStoppedBySelf());
        assertEquals(3, shadow.getStopSelfResultId());
        assertTrue(notifications().isEmpty());
        controller.destroy();
    }

    @Test public void reconnectingUpdateWithoutAnActiveSessionDoesNotPromoteForeground() {
        StreamSessionService.setReconnecting(application, "dead-session", true);
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(nextService(), 0, 1));
        assertNull(Shadows.shadowOf(service).getLastForegroundNotification());
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        assertTrue(notifications().isEmpty());
        controller.destroy();
    }

    @Test public void nullReconnectTokenDoesNotIssueAServiceCommand() {
        StreamSessionService.setReconnecting(application, null, true);
        assertNull(nextService());
    }

    @Test @Config(shadows = {ShadowMoonBridge.class, PendingStartShadowService.class})
    public void newStartReopensClosedServiceAndStaleOldCommandsCannotCloseNewSession() throws Exception {
        String oldToken = StreamSessionService.start(application, new Intent());
        Intent first = nextService();
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        PendingStartShadowService shadow = Shadow.extract(service);
        service.onStartCommand(first, 0, 1);
        StreamSessionService.stop(application, oldToken);
        Intent oldStop = nextService();
        StreamSessionService.setBackground(application, oldToken, true);
        Intent oldBackground = nextService();
        String newToken = StreamSessionService.start(new ContextWrapper(application), new Intent());
        Intent newStart = nextService();
        shadow.latestStartId = 6;
        service.onStartCommand(oldStop, 0, 2);
        service.onStartCommand(oldBackground, 0, 3);
        assertTrue(notifications().isEmpty());
        service.onStartCommand(newStart, 0, 4);
        service.onStartCommand(oldBackground, 0, 5);
        service.onStartCommand(oldStop, 0, 6);
        assertFalse(shadow.isStoppedBySelf());
        assertFalse(shadow.isForegroundStopped());
        assertEquals(1, notifications().size());
        shadow.getLastForegroundNotification().contentIntent.send();
        assertEquals(newToken, Shadows.shadowOf(application).getNextStartedActivity()
                .getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        StreamSessionService.stop(application, newToken);
        shadow.latestStartId = 7;
        service.onStartCommand(nextService(), 0, 7);
        assertTrue(shadow.isStoppedBySelf());
        controller.destroy();
    }

    @Implements(Service.class)
    public static class PendingStartShadowService extends ShadowService {
        int latestStartId;

        @Implementation
        @Override protected boolean stopSelfResult(int startId) {
            if (startId < latestStartId) return false;
            return super.stopSelfResult(startId);
        }
    }

    @Test public void nullRestartDoesNotRecreateASessionOrExposeABinder() {
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        assertNull(service.onBind(new Intent()));
        assertNull(Shadows.shadowOf(service).getLastForegroundNotification());
        controller.destroy();
    }

    @Test @Config(sdk = 34)
    public void notificationPermissionDenialDoesNotSkipForegroundPromotion() {
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertNotNull(StreamSessionService.start(application, new Intent()));
        ServiceController<StreamSessionService> controller = Robolectric.buildService(StreamSessionService.class).create();
        StreamSessionService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(nextService(), 0, 1));
        assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
        controller.destroy();
    }

    public static class DeniedService extends StreamSessionService {
        @Override void promoteToForeground(Notification notification) { throw new SecurityException("denied"); }
    }

    @Test public void promotionFailureNotifiesOnlyTheSessionOwnerAndStops() {
        String token = StreamSessionService.start(application, new Intent());
        ServiceController<DeniedService> controller = Robolectric.buildService(DeniedService.class).create();
        DeniedService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(nextService(), 0, 1));
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        List<Intent> failures = broadcasts(StreamSessionService.ACTION_SERVICE_FAILED);
        assertEquals(1, failures.size());
        assertEquals(application.getPackageName(), failures.get(0).getPackage());
        assertEquals(token, failures.get(0).getStringExtra(StreamSessionService.EXTRA_SESSION_TOKEN));
        controller.destroy();
    }

    private Intent nextService() { return Shadows.shadowOf(application).getNextStartedService(); }
    private List<Notification> notifications() {
        android.app.NotificationManager manager = (android.app.NotificationManager)
                application.getSystemService(Context.NOTIFICATION_SERVICE);
        return Shadows.shadowOf(manager).getAllNotifications();
    }
    private List<Intent> broadcasts(String action) {
        return Shadows.shadowOf(application).getBroadcastIntents().stream()
                .filter(intent -> action.equals(intent.getAction())).collect(Collectors.toList());
    }
}
