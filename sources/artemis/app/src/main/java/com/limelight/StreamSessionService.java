package com.limelight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Keeps a user-started PC session eligible to run while its screen is hidden. */
public class StreamSessionService extends Service {
    public static final String ACTION_DISCONNECT_REQUESTED =
            "com.limelight.stream.DISCONNECT_REQUESTED";
    public static final String ACTION_SERVICE_FAILED = "com.limelight.stream.SERVICE_FAILED";
    public static final String ACTION_RETURN_TO_SESSION = "com.limelight.stream.RETURN_TO_SESSION";
    public static final String EXTRA_SESSION_TOKEN = "stream_session_token";

    private static final String ACTION_START = "com.limelight.stream.START";
    private static final String ACTION_STOP = "com.limelight.stream.STOP";
    private static final String ACTION_BACKGROUND = "com.limelight.stream.BACKGROUND";
    private static final String ACTION_RECONNECTING = "com.limelight.stream.RECONNECTING";
    private static final String ACTION_DISCONNECT = "com.limelight.stream.DISCONNECT";
    private static final String EXTRA_RETURN_INTENT = "stream_return_intent";
    private static final String EXTRA_BACKGROUND = "stream_background";
    private static final String EXTRA_RECONNECTING = "stream_reconnecting";
    private static final String CHANNEL_ID = "stream_session";
    private static final int NOTIFICATION_ID = 0x53534e;
    private static final Map<Context, String> OWNERS = new WeakHashMap<>();

    private String sessionToken;
    private Intent returnIntent;
    private boolean background;
    private boolean reconnecting;
    private boolean disconnectRequested;
    private boolean sessionClosed;

    /** Call while the user is starting the stream and the Activity is visible. */
    public static String start(Context context, Intent returnIntent) {
        String token = UUID.randomUUID().toString();
        Intent command = new Intent(context, StreamSessionService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_TOKEN, token)
                .putExtra(EXTRA_RETURN_INTENT, createReturnIntent(context, returnIntent, token));
        try {
            ContextCompat.startForegroundService(context, command);
            synchronized (OWNERS) {
                OWNERS.put(context, token);
            }
            return token;
        } catch (RuntimeException e) {
            LimeLog.warning("Unable to start session foreground service: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Convenience overload: stops only the session started by this Context. */
    public static void stop(Context context) {
        String token;
        synchronized (OWNERS) {
            token = OWNERS.remove(context);
        }
        stop(context, token);
    }

    /** Old Activity callbacks must use their own token, never a global stopService(). */
    public static void stop(Context context, String token) {
        if (token == null) return;
        synchronized (OWNERS) {
            if (token.equals(OWNERS.get(context))) OWNERS.remove(context);
        }
        sendCommand(context, ACTION_STOP, token, false);
    }

    public static void setBackground(Context context, String token, boolean background) {
        if (token != null) sendCommand(context, ACTION_BACKGROUND, token, background);
    }

    /** Changes only the active owner's notification, without opening a new foreground session. */
    public static void setReconnecting(Context context, String token, boolean reconnecting) {
        if (token != null) sendCommand(context, ACTION_RECONNECTING, token, reconnecting);
    }

    private static void sendCommand(Context context, String action, String token, boolean value) {
        try {
            // Never restart a killed session as a foreground service from a background callback.
            Intent command = new Intent(context, StreamSessionService.class).setAction(action)
                    .putExtra(EXTRA_SESSION_TOKEN, token);
            if (ACTION_BACKGROUND.equals(action)) command.putExtra(EXTRA_BACKGROUND, value);
            if (ACTION_RECONNECTING.equals(action)) command.putExtra(EXTRA_RECONNECTING, value);
            context.startService(command);
        } catch (RuntimeException e) {
            LimeLog.warning("Session service command unavailable: " + e.getClass().getSimpleName());
        }
    }

    static Intent createReturnIntent(Context context, Intent original, String token) {
        Intent result = original == null ? new Intent() : new Intent(original);
        // Preserve the launch extras, but never use a caller-selected external component.
        result.setSelector(null);
        result.setClipData(null);
        return result.setClass(context, Game.class).setPackage(context.getPackageName())
                .setAction(ACTION_RETURN_TO_SESSION)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION_TOKEN, token);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.stream_session_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            closeSession(startId);
            return START_NOT_STICKY;
        }
        String token = intent.getStringExtra(EXTRA_SESSION_TOKEN);
        if (ACTION_START.equals(intent.getAction()) && token != null) {
            Intent requestedReturn = intent.getParcelableExtra(EXTRA_RETURN_INTENT);
            sessionToken = token;
            returnIntent = createReturnIntent(this, requestedReturn, token);
            background = reconnecting = disconnectRequested = sessionClosed = false;
            try {
                promoteToForeground(buildNotification());
            } catch (RuntimeException e) {
                LimeLog.warning("Session foreground promotion failed: " + e.getClass().getSimpleName());
                sendSessionEvent(ACTION_SERVICE_FAILED);
                closeSession(startId);
            }
        } else if (sessionClosed || sessionToken == null) {
            // stopSelfResult() can fail while a later start command is queued. Consume every
            // stale update as a stop retry until a genuine ACTION_START opens a new session.
            closeSession(startId);
        } else if (sessionToken != null && sessionToken.equals(token)) {
            if (ACTION_STOP.equals(intent.getAction())) {
                closeSession(startId);
            } else if (ACTION_DISCONNECT.equals(intent.getAction())) {
                if (!disconnectRequested) {
                    disconnectRequested = true;
                    sendSessionEvent(ACTION_DISCONNECT_REQUESTED);
                }
            } else if (ACTION_BACKGROUND.equals(intent.getAction())) {
                background = intent.getBooleanExtra(EXTRA_BACKGROUND, false);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIFICATION_ID, buildNotification());
            } else if (ACTION_RECONNECTING.equals(intent.getAction())) {
                reconnecting = intent.getBooleanExtra(EXTRA_RECONNECTING, false);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIFICATION_ID, buildNotification());
            }
        }
        return START_NOT_STICKY;
    }

    private void closeSession(int startId) {
        sessionClosed = true;
        disconnectRequested = true;
        // Remove the notification now, even if stopSelfResult must wait for newer commands.
        stopForeground(true);
        stopSelfResult(startId);
    }

    void promoteToForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent back = new Intent(returnIntent).setData(Uri.parse("artemis-session://return/" + sessionToken));
        PendingIntent content = PendingIntent.getActivity(this, 0, back,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Intent disconnect = new Intent(this, StreamSessionService.class).setAction(ACTION_DISCONNECT)
                .setData(Uri.parse("artemis-session://disconnect/" + sessionToken))
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken);
        PendingIntent stop = PendingIntent.getService(this, 0, disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_computer)
                .setContentTitle(getString(R.string.stream_session_title))
                .setContentText(getString(reconnecting ? R.string.stream_session_reconnecting :
                        background ? R.string.stream_session_background : R.string.stream_session_active))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.stream_session_disconnect), stop)
                .build();
    }

    private void sendSessionEvent(String action) {
        sendBroadcast(new Intent(action).setPackage(getPackageName())
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken));
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (sessionToken != null && !disconnectRequested) sendSessionEvent(ACTION_DISCONNECT_REQUESTED);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopForeground(true);
        synchronized (OWNERS) {
            OWNERS.values().removeAll(java.util.Collections.singleton(sessionToken));
        }
        sessionToken = null;
        returnIntent = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
