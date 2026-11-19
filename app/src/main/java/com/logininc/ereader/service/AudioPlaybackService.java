package com.logininc.ereader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.logininc.ereader.R;
import com.logininc.ereader.screen.AudioPlayerActivity;
import com.logininc.ereader.util.NavigationFlowHelper;

/**
 * Foreground service keeping audiobook playback alive when the user leaves the player screen.
 */
public class AudioPlaybackService extends Service {

    public static final String CHANNEL_ID = "ereader_audio_playback";
    public static final int NOTIFICATION_ID = 4101;
    public static final String ACTION_START = "com.logininc.ereader.action.START";
    public static final String ACTION_STOP = "com.logininc.ereader.action.STOP";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SUBTITLE = "extra_subtitle";
    public static final String EXTRA_TITLE_ID = "extra_title_id";

    public static void start(@ContextRequired Context context, int titleId, String title, String subtitle) {
        Intent intent = new Intent(context, AudioPlaybackService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE_ID, titleId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SUBTITLE, subtitle);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Foreground service restriction fallback if app is not eligible.
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, AudioPlaybackService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PlaybackSessionManager.getInstance().stopAndClearSession(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureChannel();
        int titleId = intent != null ? intent.getIntExtra(EXTRA_TITLE_ID, -1) : -1;
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : getString(R.string.app_name);
        String subtitle = intent != null ? intent.getStringExtra(EXTRA_SUBTITLE) : "";

        Intent openIntent = new Intent(this, AudioPlayerActivity.class);
        openIntent.putExtra(NavigationFlowHelper.EXTRA_TITLE_ID, titleId);
        openIntent.putExtra(NavigationFlowHelper.EXTRA_AUTO_PLAY, false);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headphones)
                .setContentTitle(title != null ? title : getString(R.string.app_name))
                .setContentText(subtitle != null ? subtitle : "")
                .setContentIntent(contentPi)
                .addAction(R.drawable.ic_pause, getString(R.string.action_stop_session), stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_audio_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PlaybackSessionManager.getInstance().persistCurrentState();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        PlaybackSessionManager.getInstance().persistCurrentState();
        super.onDestroy();
    }

    private @interface ContextRequired {
    }
}
