package com.logininc.ereader.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.persistence.SettingsDao;

/**
 * Bridges Media3 {@link Player} / MediaSession / AVRCP Bluetooth headset controls to
 * EReader's resume-rewind, notification scrubbing, and configurable Bluetooth skip rules.
 */
@OptIn(markerClass = UnstableApi.class)
class AudiobookForwardingPlayer extends ForwardingPlayer {

    private final PlaybackSessionManager manager;
    private final long seekBackMs;
    private final long seekForwardMs;

    AudiobookForwardingPlayer(
            @NonNull ExoPlayer wrappedPlayer,
            @NonNull PlaybackSessionManager manager,
            long seekBackMs,
            long seekForwardMs
    ) {
        super(wrappedPlayer);
        this.manager = manager;
        this.seekBackMs = seekBackMs;
        this.seekForwardMs = seekForwardMs;
    }

    @Override
    public void play() {
        Context ctx = manager.getAppContextOrNull();
        if (ctx != null) {
            manager.resume(ctx);
        } else {
            super.play();
        }
    }

    @Override
    public void pause() {
        Context ctx = manager.getAppContextOrNull();
        if (ctx != null) {
            manager.pause(ctx);
        } else {
            super.pause();
        }
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        Context ctx = manager.getAppContextOrNull();
        if (ctx == null) {
            super.setPlayWhenReady(playWhenReady);
            return;
        }
        if (playWhenReady) {
            manager.resume(ctx);
        } else {
            manager.pause(ctx);
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (manager.hasPreparedAudio()) {
            manager.seekToMs(positionMs);
        } else {
            super.seekTo(positionMs);
        }
    }

    @Override
    public void seekTo(int mediaItemIndex, long positionMs) {
        if (mediaItemIndex == 0 && manager.hasPreparedAudio()) {
            manager.seekToMs(positionMs);
        } else {
            super.seekTo(mediaItemIndex, positionMs);
        }
    }

    @Override
    public long getSeekBackIncrement() {
        return seekBackMs;
    }

    @Override
    public long getSeekForwardIncrement() {
        return seekForwardMs;
    }

    @Override
    public void seekBack() {
        manager.seekByMs(-seekBackMs);
    }

    @Override
    public void seekForward() {
        manager.seekByMs(seekForwardMs);
    }

    @Override
    public boolean hasNextMediaItem() {
        return manager.getActiveTitle() instanceof AudioBook;
    }

    @Override
    public boolean hasPreviousMediaItem() {
        return manager.getActiveTitle() instanceof AudioBook;
    }

    @Override
    public void seekToNext() {
        handleBluetoothSkip(1);
    }

    @Override
    public void seekToNextMediaItem() {
        handleBluetoothSkip(1);
    }

    @Override
    public void seekToPrevious() {
        handleBluetoothSkip(-1);
    }

    @Override
    public void seekToPreviousMediaItem() {
        handleBluetoothSkip(-1);
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return getAvailableCommands().contains(command);
    }

    @NonNull
    @Override
    public Player.Commands getAvailableCommands() {
        return super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build();
    }

    private void handleBluetoothSkip(int direction) {
        Context ctx = manager.getAppContextOrNull();
        if (ctx == null) {
            return;
        }
        String mode = new SettingsDao(ctx).getBluetoothSkipMode();
        if (SettingsDao.BLUETOOTH_SKIP_CHAPTER.equals(mode)) {
            manager.shiftChapter(ctx, direction);
        } else {
            manager.seekByMs(direction > 0 ? seekForwardMs : -seekBackMs);
        }
    }
}
