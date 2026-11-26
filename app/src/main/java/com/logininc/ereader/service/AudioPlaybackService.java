package com.logininc.ereader.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.StringRes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.logininc.ereader.R;
import com.logininc.ereader.screen.AudioPlayerActivity;
import com.logininc.ereader.util.NavigationFlowHelper;

import java.util.Arrays;
import java.util.List;

/**
 * Media3 {@link MediaSessionService} providing lockscreen/notification media controls,
 * seekbar, custom skip/chapter actions, and AVRCP Bluetooth headset command handling.
 */
@OptIn(markerClass = UnstableApi.class)
public class AudioPlaybackService extends MediaSessionService {

    public static final String CHANNEL_ID = "ereader_audio_playback";
    public static final int NOTIFICATION_ID = 4101;

    private static final String ACTION_REWIND_15 = "com.logininc.ereader.action.REWIND_15";
    private static final String ACTION_FORWARD_30 = "com.logininc.ereader.action.FORWARD_30";
    private static final String ACTION_PREV_CHAPTER = "com.logininc.ereader.action.PREV_CHAPTER";
    private static final String ACTION_NEXT_CHAPTER = "com.logininc.ereader.action.NEXT_CHAPTER";

    @Nullable
    private MediaSession mediaSession;
    private List<CommandButton> customButtons;

    @Override
    public void onCreate() {
        super.onCreate();
        DefaultMediaNotificationProvider notificationProvider =
                new DefaultMediaNotificationProvider.Builder(this)
                        .setChannelId(CHANNEL_ID)
                        .setChannelName(R.string.notification_audio_channel)
                        .setNotificationId(NOTIFICATION_ID)
                        .build();
        notificationProvider.setSmallIcon(R.drawable.ic_headphones);
        setMediaNotificationProvider(notificationProvider);

        customButtons = Arrays.asList(
                buildButton(ACTION_REWIND_15, R.drawable.ic_replay_15, R.string.notification_rewind_15, CommandButton.SLOT_BACK),
                buildButton(ACTION_FORWARD_30, R.drawable.ic_forward_30, R.string.notification_forward_30, CommandButton.SLOT_FORWARD),
                buildButton(ACTION_PREV_CHAPTER, R.drawable.ic_skip_previous, R.string.notification_prev_chapter, CommandButton.SLOT_BACK_SECONDARY),
                buildButton(ACTION_NEXT_CHAPTER, R.drawable.ic_skip_next, R.string.notification_next_chapter, CommandButton.SLOT_FORWARD_SECONDARY)
        );

        PlaybackSessionManager manager = PlaybackSessionManager.getInstance();
        Player sessionPlayer = manager.getOrCreateSessionPlayer(this);
        com.logininc.ereader.model.Title active = manager.getActiveTitle();
        int initialTitleId = active != null ? active.getId() : -1;

        mediaSession = new MediaSession.Builder(this, sessionPlayer)
                .setSessionActivity(buildContentIntent(initialTitleId))
                .setMediaButtonPreferences(customButtons)
                .setCallback(new SessionCallback())
                .build();
        manager.attachService(this);
    }

    public void updateSessionActivity(int titleId) {
        if (mediaSession != null) {
            mediaSession.setSessionActivity(buildContentIntent(titleId));
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        PlaybackSessionManager.getInstance().persistCurrentState();
        Player player = mediaSession != null ? mediaSession.getPlayer() : null;
        if (player == null || !player.getPlayWhenReady()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        PlaybackSessionManager manager = PlaybackSessionManager.getInstance();
        manager.persistCurrentState();
        manager.detachService(this);
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @NonNull
    private CommandButton buildButton(
            @NonNull String action,
            @DrawableRes int iconResId,
            @StringRes int labelResId,
            int slot
    ) {
        return new CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(iconResId)
                .setDisplayName(getString(labelResId))
                .setSessionCommand(new SessionCommand(action, Bundle.EMPTY))
                .setSlots(slot)
                .build();
    }

    @NonNull
    private PendingIntent buildContentIntent(int titleId) {
        Intent openIntent = new Intent(this, AudioPlayerActivity.class);
        if (titleId > 0) {
            openIntent.putExtra(NavigationFlowHelper.EXTRA_TITLE_ID, titleId);
        }
        openIntent.putExtra(NavigationFlowHelper.EXTRA_AUTO_PLAY, false);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private class SessionCallback implements MediaSession.Callback {

        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller
        ) {
            SessionCommands.Builder commandsBuilder =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon();
            for (CommandButton button : customButtons) {
                if (button.sessionCommand != null) {
                    commandsBuilder.add(button.sessionCommand);
                }
            }
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commandsBuilder.build())
                    .setMediaButtonPreferences(customButtons)
                    .build();
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand customCommand,
                @NonNull Bundle args
        ) {
            PlaybackSessionManager manager = PlaybackSessionManager.getInstance();
            switch (customCommand.customAction) {
                case ACTION_REWIND_15:
                    manager.seekByMs(-15_000L);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                case ACTION_FORWARD_30:
                    manager.seekByMs(30_000L);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                case ACTION_PREV_CHAPTER:
                    manager.shiftChapter(AudioPlaybackService.this, -1);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                case ACTION_NEXT_CHAPTER:
                    manager.shiftChapter(AudioPlaybackService.this, 1);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                default:
                    return MediaSession.Callback.super.onCustomCommand(session, controller, customCommand, args);
            }
        }
    }
}
