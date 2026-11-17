package com.logininc.ereader.service;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the active reading/audio session in the background.
 * Once started, a session stays alive across screens (shown in the Mini-Player bar)
 * until the user opens a different title or explicitly stops/blocks it from the mini-player or full player.
 */
public class PlaybackSessionManager {

    public interface SessionListener {
        void onSessionChanged(@Nullable Title activeTitle, boolean isPlaying, long positionMs, long durationMs);

        default void onChapterCompleted(@NonNull Title title, int completedChapterIndex) {
        }
    }

    private static volatile PlaybackSessionManager instance;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private Title activeTitle;
    @Nullable
    private MediaPlayer mediaPlayer;
    @Nullable
    private Context appContext;
    private boolean playing = false;
    private float playbackSpeed = 1.0f;
    private boolean tickerRunning = false;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (activeTitle == null) {
                tickerRunning = false;
                return;
            }
            if (activeTitle instanceof AudioBook && mediaPlayer != null && playing) {
                AudioBook audioBook = (AudioBook) activeTitle;
                try {
                    long currentMs = mediaPlayer.getCurrentPosition();
                    long totalMs = Math.max(mediaPlayer.getDuration(), audioBook.getTotalDurationMs());
                    audioBook.setCurrentPositionMs(currentMs);
                    if (totalMs > 0) {
                        audioBook.setTotalDurationMs(totalMs);
                        audioBook.setProgressPercent((int) ((currentMs * 100L) / totalMs));
                    }
                    syncAudioChapterProgress(audioBook, currentMs);
                } catch (IllegalStateException ignored) {
                }
            }
            notifyListeners();
            if (playing) {
                mainHandler.postDelayed(this, 1000L);
            } else {
                tickerRunning = false;
            }
        }
    };

    private PlaybackSessionManager() {
    }

    public static PlaybackSessionManager getInstance() {
        if (instance == null) {
            synchronized (PlaybackSessionManager.class) {
                if (instance == null) {
                    instance = new PlaybackSessionManager();
                }
            }
        }
        return instance;
    }

    public void addListener(@NonNull SessionListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull SessionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Restores the persisted active session from SQLite on cold app start if one was left running.
     */
    @Nullable
    public Title restoreIfNeeded(@NonNull Context context) {
        appContext = context.getApplicationContext();
        if (activeTitle != null) {
            return activeTitle;
        }
        SettingsDao settingsDao = new SettingsDao(appContext);
        int savedId = settingsDao.getActiveSessionTitleId();
        if (savedId <= 0) {
            return null;
        }
        Title loaded = new TitleDao(appContext).getById(savedId);
        if (loaded == null) {
            settingsDao.setActiveSessionTitleId(-1);
            settingsDao.setActiveSessionPlaying(false);
            return null;
        }
        activeTitle = loaded;
        playbackSpeed = settingsDao.getDefaultAudioSpeed();
        playing = false; // Do not auto-blare audio on cold restart until resumed
        return activeTitle;
    }

    /**
     * Pure rule helper for tests: returns true when switching from {@code currentTitleId}
     * to {@code newTitleId} represents opening a new title.
     */
    public static boolean isNewTitleSwitch(int currentTitleId, int newTitleId) {
        return currentTitleId > 0 && currentTitleId != newTitleId;
    }

    public void startOrSwitchSession(@NonNull Context context, @NonNull Title title, int chapterIndex, boolean startPlaying) {
        appContext = context.getApplicationContext();
        SettingsDao settingsDao = new SettingsDao(appContext);
        TitleDao titleDao = new TitleDao(appContext);

        boolean switchingTitle = activeTitle != null && isNewTitleSwitch(activeTitle.getId(), title.getId());
        if (switchingTitle) {
            persistCurrentState();
            releaseMediaPlayer();
        }

        boolean switchingChapter = !switchingTitle
                && activeTitle != null
                && activeTitle.getCurrentChapter() != Math.max(1, chapterIndex);

        title.setCurrentChapter(Math.max(1, chapterIndex));
        title.setCurrentChapterCompleted(false);
        title.setStatus(Title.STATUS_IN_PROGRESS);
        title.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(title);

        boolean sameAudioAlreadyPrepared = !switchingTitle
                && activeTitle instanceof AudioBook
                && title instanceof AudioBook
                && mediaPlayer != null;

        activeTitle = title;
        playbackSpeed = settingsDao.getDefaultAudioSpeed();
        settingsDao.setActiveSessionTitleId(title.getId());

        if (title instanceof AudioBook) {
            AudioBook audioBook = (AudioBook) title;
            if (!sameAudioAlreadyPrepared) {
                prepareAndPlayAudio(appContext, audioBook, chapterIndex, startPlaying);
            } else {
                if (switchingChapter) {
                    Chapter ch = new ChapterDao(appContext).getChapter(audioBook.getId(), Math.max(1, chapterIndex));
                    if (ch != null) {
                        long chStart = ch.getAudioStartMs();
                        long chEnd = chStart + ch.getAudioDurationMs();
                        long pos = audioBook.getCurrentPositionMs();
                        if (pos < chStart || (ch.getAudioDurationMs() > 0 && pos >= chEnd)) {
                            pos = chStart;
                        }
                        seekToMs(pos);
                    }
                }
                if (startPlaying && !playing) {
                    resume(appContext);
                } else {
                    notifyListeners();
                }
            }
        } else {
            // Active eBook session stays active in background/mini-player until stopped or replaced
            releaseMediaPlayer();
            playing = true;
            settingsDao.setActiveSessionPlaying(true);
            SleepTimerController.getInstance().onSessionActive(appContext);
            startTickerIfNeeded();
            notifyListeners();
        }
    }

    private void prepareAndPlayAudio(
            @NonNull Context context,
            @NonNull AudioBook audioBook,
            int chapterIndex,
            boolean startPlaying
    ) {
        releaseMediaPlayer();
        mediaPlayer = new MediaPlayer();
        try {
            boolean dataSourceSet = false;
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(audioBook.getContentUri(), "r")) {
                if (pfd != null) {
                    mediaPlayer.setDataSource(pfd.getFileDescriptor());
                    dataSourceSet = true;
                }
            } catch (IOException | IllegalStateException | SecurityException ignored) {
            }
            if (!dataSourceSet) {
                mediaPlayer.setDataSource(context, audioBook.getContentUri());
            }
            mediaPlayer.prepare();
            long durationMs = mediaPlayer.getDuration();
            if (durationMs > 0) {
                audioBook.setTotalDurationMs(durationMs);
                new TitleDao(context).update(audioBook);
            }

            long seekTargetMs = audioBook.getCurrentPositionMs();
            Chapter chapter = new ChapterDao(context).getChapter(audioBook.getId(), chapterIndex);
            if (chapter != null) {
                long chStart = chapter.getAudioStartMs();
                long chEnd = chStart + chapter.getAudioDurationMs();
                if (seekTargetMs < chStart || (chapter.getAudioDurationMs() > 0 && seekTargetMs >= chEnd)) {
                    seekTargetMs = chStart;
                }
            }
            if (seekTargetMs > 0 && seekTargetMs < durationMs) {
                mediaPlayer.seekTo((int) seekTargetMs);
                audioBook.setCurrentPositionMs(seekTargetMs);
            }
            applySpeedToPlayer(playbackSpeed);

            mediaPlayer.setOnCompletionListener(mp -> handleAudioTrackCompleted());

            if (startPlaying) {
                mediaPlayer.start();
                playing = true;
                new SettingsDao(context).setActiveSessionPlaying(true);
                AudioPlaybackService.start(
                        context,
                        audioBook.getId(),
                        audioBook.getTitle(),
                        audioBook.getAuthor()
                );
                SleepTimerController.getInstance().onSessionActive(context);
                startTickerIfNeeded();
            } else {
                playing = false;
                new SettingsDao(context).setActiveSessionPlaying(false);
            }
        } catch (IOException | IllegalStateException | SecurityException e) {
            releaseMediaPlayer();
            playing = false;
        }
        notifyListeners();
    }

    private void handleAudioTrackCompleted() {
        if (activeTitle == null || appContext == null) {
            return;
        }
        int completedChapter = activeTitle.getCurrentChapter();
        activeTitle.setCurrentChapterCompleted(true);
        activeTitle.setStatus(Title.STATUS_COMPLETED);
        activeTitle.setProgressPercent(100);
        new ChapterDao(appContext).updateChapterProgress(activeTitle.getId(), completedChapter, 100, true);
        new TitleDao(appContext).update(activeTitle);
        playing = false;
        new SettingsDao(appContext).setActiveSessionPlaying(false);
        AudioPlaybackService.stop(appContext);
        notifyListeners();
        for (SessionListener listener : listeners) {
            listener.onChapterCompleted(activeTitle, completedChapter);
        }
    }

    private void syncAudioChapterProgress(@NonNull AudioBook audioBook, long currentMs) {
        if (appContext == null) {
            return;
        }
        ChapterDao chapterDao = new ChapterDao(appContext);
        List<Chapter> chapters = chapterDao.getChaptersForTitle(audioBook.getId());
        if (chapters.isEmpty()) {
            return;
        }
        int activeIdx = audioBook.getCurrentChapter();
        Chapter activeCh = chapterDao.getChapter(audioBook.getId(), activeIdx);
        if (activeCh != null && activeCh.getAudioDurationMs() > 0) {
            long chEnd = activeCh.getAudioStartMs() + activeCh.getAudioDurationMs();
            if (currentMs >= chEnd && activeIdx < chapters.size()) {
                chapterDao.updateChapterProgress(audioBook.getId(), activeIdx, 100, true);
                audioBook.setCurrentChapterCompleted(true);
                if (audioBook.getTotalDurationMs() > 0) {
                    audioBook.setProgressPercent((int) ((chEnd * 100L) / audioBook.getTotalDurationMs()));
                }
                audioBook.setCurrentPositionMs(chEnd);
                new TitleDao(appContext).update(audioBook);

                playing = false;
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                        }
                        mediaPlayer.seekTo((int) chEnd);
                    } catch (IllegalStateException ignored) {
                    }
                }
                new SettingsDao(appContext).setActiveSessionPlaying(false);
                AudioPlaybackService.stop(appContext);
                SleepTimerController.getInstance().onSessionPaused();
                notifyListeners();
                for (SessionListener listener : listeners) {
                    listener.onChapterCompleted(audioBook, activeIdx);
                }
                return;
            }
        }

        Chapter current = activeCh;
        if (current == null) {
            for (Chapter ch : chapters) {
                long start = ch.getAudioStartMs();
                long end = start + ch.getAudioDurationMs();
                if (currentMs >= start && (ch.getAudioDurationMs() <= 0 || currentMs < end)) {
                    current = ch;
                    break;
                }
            }
        }
        if (current != null) {
            if (current.getChapterIndex() != audioBook.getCurrentChapter()) {
                audioBook.setCurrentChapter(current.getChapterIndex());
                audioBook.setCurrentChapterCompleted(current.isCompleted());
            }
            long relMs = Math.max(0L, currentMs - current.getAudioStartMs());
            int chPct = current.getAudioDurationMs() > 0
                    ? (int) Math.min(100L, (relMs * 100L) / current.getAudioDurationMs())
                    : audioBook.getProgressPercent();
            boolean keepCompleted = current.isCompleted();
            chapterDao.updateChapterProgress(
                    audioBook.getId(),
                    current.getChapterIndex(),
                    keepCompleted ? 100 : chPct,
                    keepCompleted
            );
        }
        new TitleDao(appContext).update(audioBook);
    }

    public void pause(@NonNull Context context) {
        appContext = context.getApplicationContext();
        playing = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
            }
        }
        persistCurrentState();
        new SettingsDao(appContext).setActiveSessionPlaying(false);
        AudioPlaybackService.stop(appContext);
        SleepTimerController.getInstance().onSessionPaused();
        notifyListeners();
    }

    public void resume(@NonNull Context context) {
        appContext = context.getApplicationContext();
        if (activeTitle == null) {
            restoreIfNeeded(appContext);
        }
        if (activeTitle == null) {
            return;
        }
        if (activeTitle instanceof AudioBook) {
            AudioBook audioBook = (AudioBook) activeTitle;
            if (mediaPlayer == null) {
                prepareAndPlayAudio(appContext, audioBook, audioBook.getCurrentChapter(), true);
                return;
            }
            try {
                mediaPlayer.start();
                playing = true;
                AudioPlaybackService.start(
                        appContext,
                        audioBook.getId(),
                        audioBook.getTitle(),
                        audioBook.getAuthor()
                );
            } catch (IllegalStateException e) {
                prepareAndPlayAudio(appContext, audioBook, audioBook.getCurrentChapter(), true);
                return;
            }
        } else {
            playing = true;
        }
        new SettingsDao(appContext).setActiveSessionPlaying(true);
        SleepTimerController.getInstance().onSessionActive(appContext);
        startTickerIfNeeded();
        notifyListeners();
    }

    public void togglePlayPause(@NonNull Context context) {
        if (playing) {
            pause(context);
        } else {
            resume(context);
        }
    }

    /**
     * Completely stops/blocks the current reader or player session and removes it from the mini-player.
     */
    public void stopAndClearSession(@NonNull Context context) {
        appContext = context.getApplicationContext();
        persistCurrentState();
        releaseMediaPlayer();
        playing = false;
        activeTitle = null;
        SettingsDao settingsDao = new SettingsDao(appContext);
        settingsDao.setActiveSessionTitleId(-1);
        settingsDao.setActiveSessionPlaying(false);
        AudioPlaybackService.stop(appContext);
        SleepTimerController.getInstance().onSessionStopped();
        notifyListeners();
    }

    public void seekByMs(long deltaMs) {
        if (!(activeTitle instanceof AudioBook)) {
            return;
        }
        AudioBook audioBook = (AudioBook) activeTitle;
        long total = audioBook.getTotalDurationMs();
        long current = getCurrentPositionMs();
        long target = Math.max(0L, total > 0 ? Math.min(total, current + deltaMs) : current + deltaMs);
        seekToMs(target);
    }

    public void seekToMs(long positionMs) {
        if (!(activeTitle instanceof AudioBook)) {
            return;
        }
        AudioBook audioBook = (AudioBook) activeTitle;
        long clamped = Math.max(0L, positionMs);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo((int) clamped);
            } catch (IllegalStateException ignored) {
            }
        }
        audioBook.setCurrentPositionMs(clamped);
        if (audioBook.getTotalDurationMs() > 0) {
            audioBook.setProgressPercent((int) ((clamped * 100L) / audioBook.getTotalDurationMs()));
        }
        syncCurrentChapterOnSeek(audioBook, clamped);
        persistCurrentState();
        notifyListeners();
    }

    private void syncCurrentChapterOnSeek(@NonNull AudioBook audioBook, long positionMs) {
        if (appContext == null) {
            return;
        }
        List<Chapter> chapters = new ChapterDao(appContext).getChaptersForTitle(audioBook.getId());
        for (Chapter ch : chapters) {
            long start = ch.getAudioStartMs();
            long end = start + ch.getAudioDurationMs();
            if (positionMs >= start && (ch.getAudioDurationMs() <= 0 || positionMs < end)) {
                if (ch.getChapterIndex() != audioBook.getCurrentChapter()) {
                    audioBook.setCurrentChapter(ch.getChapterIndex());
                    audioBook.setCurrentChapterCompleted(false);
                }
                break;
            }
        }
    }

    public void jumpToChapter(@NonNull Context context, int chapterIndex) {
        if (activeTitle == null) {
            return;
        }
        appContext = context.getApplicationContext();
        int clamped = Math.max(1, Math.min(activeTitle.getLenChapters(), chapterIndex));
        activeTitle.setCurrentChapter(clamped);
        activeTitle.setCurrentChapterCompleted(false);
        activeTitle.setStatus(Title.STATUS_IN_PROGRESS);
        Chapter ch = new ChapterDao(appContext).getChapter(activeTitle.getId(), clamped);
        if (activeTitle instanceof AudioBook && ch != null) {
            long startMs = Math.max(0L, ch.getAudioStartMs());
            AudioBook audioBook = (AudioBook) activeTitle;
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.seekTo((int) startMs);
                } catch (IllegalStateException ignored) {
                }
            }
            audioBook.setCurrentPositionMs(startMs);
            if (audioBook.getTotalDurationMs() > 0) {
                audioBook.setProgressPercent((int) ((startMs * 100L) / audioBook.getTotalDurationMs()));
            }
        }
        new TitleDao(appContext).update(activeTitle);
        notifyListeners();
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = speed;
        if (appContext != null) {
            new SettingsDao(appContext).setDefaultAudioSpeed(speed);
        }
        applySpeedToPlayer(speed);
        notifyListeners();
    }

    private void applySpeedToPlayer(float speed) {
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
            }
        }
    }

    public void persistCurrentState() {
        if (activeTitle == null || appContext == null) {
            return;
        }
        if (activeTitle instanceof AudioBook && mediaPlayer != null) {
            try {
                ((AudioBook) activeTitle).setCurrentPositionMs(mediaPlayer.getCurrentPosition());
            } catch (IllegalStateException ignored) {
            }
        }
        new TitleDao(appContext).update(activeTitle);
    }

    @Nullable
    public Title getActiveTitle() {
        return activeTitle;
    }

    public boolean isPlaying() {
        return playing;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public long getCurrentPositionMs() {
        if (activeTitle instanceof AudioBook) {
            if (mediaPlayer != null) {
                try {
                    return mediaPlayer.getCurrentPosition();
                } catch (IllegalStateException ignored) {
                }
            }
            return ((AudioBook) activeTitle).getCurrentPositionMs();
        }
        return 0L;
    }

    public long getTotalDurationMs() {
        if (activeTitle instanceof AudioBook) {
            if (mediaPlayer != null) {
                try {
                    long d = mediaPlayer.getDuration();
                    if (d > 0) {
                        return d;
                    }
                } catch (IllegalStateException ignored) {
                }
            }
            return ((AudioBook) activeTitle).getTotalDurationMs();
        }
        return 0L;
    }

    private void startTickerIfNeeded() {
        if (!tickerRunning) {
            tickerRunning = true;
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.postDelayed(progressTicker, 1000L);
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void notifyListeners() {
        long pos = getCurrentPositionMs();
        long dur = getTotalDurationMs();
        for (SessionListener listener : listeners) {
            listener.onSessionChanged(activeTitle, playing, pos, dur);
        }
    }
}
