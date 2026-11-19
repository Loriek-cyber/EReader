package com.logininc.ereader.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final long PERIODIC_PERSIST_INTERVAL_MS = 10_000L;

    private static volatile PlaybackSessionManager instance;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private Title activeTitle;
    @Nullable
    private MediaPlayer mediaPlayer;
    @Nullable
    private Context appContext;
    private boolean mediaPlayerPrepared = false;
    private boolean playing = false;
    private float playbackSpeed = 1.0f;
    private boolean tickerRunning = false;
    private long lastPeriodicPersistElapsedMs = 0L;
    private int cachedChaptersTitleId = -1;
    @NonNull
    private List<Chapter> cachedAudioChapters = Collections.emptyList();

    @Nullable
    private AudioFocusRequest audioFocusRequest;
    private boolean pausedByTransientFocusLoss = false;
    private boolean noisyReceiverRegistered = false;

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        if (!(activeTitle instanceof AudioBook) || appContext == null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByTransientFocusLoss = false;
                if (playing) {
                    pause(appContext);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) {
                    pausedByTransientFocusLoss = true;
                    pause(appContext);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null && mediaPlayerPrepared) {
                    try {
                        mediaPlayer.setVolume(0.25f, 0.25f);
                    } catch (IllegalStateException ignored) {
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null && mediaPlayerPrepared) {
                    try {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                    } catch (IllegalStateException ignored) {
                    }
                }
                if (pausedByTransientFocusLoss && !playing) {
                    pausedByTransientFocusLoss = false;
                    resume(appContext);
                }
                break;
            default:
                break;
        }
    };

    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && playing
                    && activeTitle instanceof AudioBook
                    && appContext != null) {
                pausedByTransientFocusLoss = false;
                pause(appContext);
            }
        }
    };

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

        boolean sameAudioAlreadyPrepared = !switchingTitle
                && activeTitle instanceof AudioBook
                && title instanceof AudioBook
                && mediaPlayer != null
                && mediaPlayerPrepared;

        if (sameAudioAlreadyPrepared && !switchingChapter) {
            try {
                long livePos = Math.max(0L, mediaPlayer.getCurrentPosition());
                AudioBook audioBook = (AudioBook) title;
                audioBook.setCurrentPositionMs(livePos);
                long totalMs = Math.max(mediaPlayer.getDuration(), audioBook.getTotalDurationMs());
                if (totalMs > 0) {
                    audioBook.setTotalDurationMs(totalMs);
                    audioBook.setProgressPercent((int) Math.min(100L, (livePos * 100L) / totalMs));
                }
            } catch (IllegalStateException ignored) {
            }
        }

        title.setCurrentChapter(Math.max(1, chapterIndex));
        title.setCurrentChapterCompleted(false);
        title.setStatus(Title.STATUS_IN_PROGRESS);
        title.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(title);

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
            mediaPlayerPrepared = true;
            long durationMs = mediaPlayer.getDuration();
            if (durationMs > 0) {
                audioBook.setTotalDurationMs(durationMs);
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
            if (durationMs > 0 && seekTargetMs >= durationMs) {
                seekTargetMs = Math.max(0L, durationMs - 1000L);
            }
            if (seekTargetMs > 0 && (durationMs <= 0 || seekTargetMs < durationMs)) {
                mediaPlayer.seekTo((int) seekTargetMs);
                audioBook.setCurrentPositionMs(seekTargetMs);
            }
            if (durationMs > 0) {
                audioBook.setProgressPercent((int) Math.min(100L, (audioBook.getCurrentPositionMs() * 100L) / durationMs));
            }
            new TitleDao(context).update(audioBook);

            mediaPlayer.setOnCompletionListener(mp -> handleAudioTrackCompleted());

            if (startPlaying) {
                requestAudioFocus(context);
                registerNoisyReceiver(context);
                mediaPlayer.start();
                applySpeedToPlayer(playbackSpeed);
                playing = true;
                pausedByTransientFocusLoss = false;
                lastPeriodicPersistElapsedMs = SystemClock.elapsedRealtime();
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
        mediaPlayerPrepared = false;
        int completedChapter = activeTitle.getCurrentChapter();
        if (activeTitle instanceof AudioBook) {
            AudioBook ab = (AudioBook) activeTitle;
            if (ab.getTotalDurationMs() > 0) {
                ab.setCurrentPositionMs(ab.getTotalDurationMs());
            }
        }
        activeTitle.setCurrentChapterCompleted(true);
        activeTitle.setStatus(Title.STATUS_COMPLETED);
        activeTitle.setProgressPercent(100);
        activeTitle.setLastOpenedAt(System.currentTimeMillis());
        new ChapterDao(appContext).updateChapterProgress(activeTitle.getId(), completedChapter, 100, true);
        new TitleDao(appContext).update(activeTitle);
        playing = false;
        new SettingsDao(appContext).setActiveSessionPlaying(false);
        AudioPlaybackService.stop(appContext);
        SleepTimerController.getInstance().onSessionPaused();
        notifyListeners();
        for (SessionListener listener : listeners) {
            listener.onChapterCompleted(activeTitle, completedChapter);
        }
    }

    @NonNull
    private List<Chapter> getCachedAudioChapters(int titleId) {
        if (appContext == null) {
            return Collections.emptyList();
        }
        if (cachedChaptersTitleId != titleId || cachedAudioChapters.isEmpty()) {
            cachedAudioChapters = new ChapterDao(appContext).getChapterSummariesForTitle(titleId);
            cachedChaptersTitleId = titleId;
        }
        return cachedAudioChapters;
    }

    private void syncAudioChapterProgress(@NonNull AudioBook audioBook, long currentMs) {
        if (appContext == null) {
            return;
        }
        List<Chapter> chapters = getCachedAudioChapters(audioBook.getId());
        if (chapters.isEmpty()) {
            maybePersistAudioStateThrottled(audioBook, -1, 0, false, false);
            return;
        }
        int activeIdx = audioBook.getCurrentChapter();
        Chapter activeCh = null;
        int activeListPos = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getChapterIndex() == activeIdx) {
                activeCh = chapters.get(i);
                activeListPos = i;
                break;
            }
        }

        boolean chapterChanged = false;
        Chapter current = activeCh;
        if (activeCh != null && activeCh.getAudioDurationMs() > 0) {
            long chEnd = activeCh.getAudioStartMs() + activeCh.getAudioDurationMs();
            if (currentMs >= chEnd && activeListPos >= 0 && activeListPos < chapters.size() - 1) {
                // Seamlessly advance directly to the next chapter without pausing or opening the index
                activeCh.setProgressPercent(100);
                activeCh.setCompleted(true);
                final Context ctx = appContext;
                final int titleId = audioBook.getId();
                final int finishedIdx = activeIdx;
                dbExecutor.execute(() -> new ChapterDao(ctx).updateChapterProgress(titleId, finishedIdx, 100, true));

                Chapter nextChapter = chapters.get(activeListPos + 1);
                for (int i = activeListPos + 1; i < chapters.size(); i++) {
                    Chapter candidate = chapters.get(i);
                    long start = candidate.getAudioStartMs();
                    long end = start + candidate.getAudioDurationMs();
                    if (candidate.getAudioDurationMs() > 0 && currentMs >= end && i < chapters.size() - 1) {
                        candidate.setProgressPercent(100);
                        candidate.setCompleted(true);
                        final int skippedIdx = candidate.getChapterIndex();
                        dbExecutor.execute(() -> new ChapterDao(ctx).updateChapterProgress(titleId, skippedIdx, 100, true));
                    } else {
                        nextChapter = candidate;
                        break;
                    }
                }
                audioBook.setCurrentChapter(nextChapter.getChapterIndex());
                audioBook.setCurrentChapterCompleted(false);
                current = nextChapter;
                chapterChanged = true;
            }
        }

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
        int targetChapterIdx = -1;
        int targetChapterPct = 0;
        boolean targetChapterCompleted = false;
        if (current != null) {
            if (current.getChapterIndex() != audioBook.getCurrentChapter()) {
                audioBook.setCurrentChapter(current.getChapterIndex());
                audioBook.setCurrentChapterCompleted(current.isCompleted());
                chapterChanged = true;
            }
            long relMs = Math.max(0L, currentMs - current.getAudioStartMs());
            int chPct = current.getAudioDurationMs() > 0
                    ? (int) Math.min(100L, (relMs * 100L) / current.getAudioDurationMs())
                    : audioBook.getProgressPercent();
            targetChapterCompleted = current.isCompleted();
            targetChapterPct = targetChapterCompleted ? 100 : chPct;
            targetChapterIdx = current.getChapterIndex();
            current.setProgressPercent(targetChapterPct);
        }
        audioBook.setLastOpenedAt(System.currentTimeMillis());
        maybePersistAudioStateThrottled(audioBook, targetChapterIdx, targetChapterPct, targetChapterCompleted, chapterChanged);
    }

    private void maybePersistAudioStateThrottled(
            @NonNull AudioBook audioBook,
            int chapterIndex,
            int chapterPct,
            boolean chapterCompleted,
            boolean forceImmediate
    ) {
        if (appContext == null) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        if (!forceImmediate && (nowElapsed - lastPeriodicPersistElapsedMs) < PERIODIC_PERSIST_INTERVAL_MS) {
            return;
        }
        lastPeriodicPersistElapsedMs = nowElapsed;
        final Context ctx = appContext;
        final int titleId = audioBook.getId();
        final AudioBook snapshot = new AudioBook(audioBook.getId(), audioBook.getTitle(), audioBook.getLenChapters(), audioBook.getContentUri());
        snapshot.setAuthor(audioBook.getAuthor());
        snapshot.setNarrator(audioBook.getNarrator());
        snapshot.setCurrentChapter(audioBook.getCurrentChapter());
        snapshot.setCurrentChapterCompleted(audioBook.isCurrentChapterCompleted());
        snapshot.setProgressPercent(audioBook.getProgressPercent());
        snapshot.setCurrentPositionMs(audioBook.getCurrentPositionMs());
        snapshot.setTotalDurationMs(audioBook.getTotalDurationMs());
        snapshot.setCoverUri(audioBook.getCoverUri());
        snapshot.setStatus(audioBook.getStatus());
        snapshot.setLastOpenedAt(audioBook.getLastOpenedAt());

        dbExecutor.execute(() -> {
            if (chapterIndex > 0) {
                new ChapterDao(ctx).updateChapterProgress(titleId, chapterIndex, chapterPct, chapterCompleted);
            }
            new TitleDao(ctx).update(snapshot);
        });
    }

    public void pause(@NonNull Context context) {
        appContext = context.getApplicationContext();
        playing = false;
        unregisterNoisyReceiver();
        if (!pausedByTransientFocusLoss) {
            abandonAudioFocus();
        }
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

    /**
     * Pauses an active eBook reading session when the reader leaves the foreground,
     * preventing the nocturnal sleep timer from firing when the user is no longer reading.
     */
    public void pauseEbookReadingSession(@NonNull Context context) {
        if (activeTitle instanceof Book && playing) {
            appContext = context.getApplicationContext();
            playing = false;
            new SettingsDao(appContext).setActiveSessionPlaying(false);
            SleepTimerController.getInstance().onSessionPaused();
            notifyListeners();
        }
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
            if (mediaPlayer == null || !mediaPlayerPrepared) {
                prepareAndPlayAudio(appContext, audioBook, audioBook.getCurrentChapter(), true);
                return;
            }
            try {
                requestAudioFocus(appContext);
                registerNoisyReceiver(appContext);
                mediaPlayer.start();
                applySpeedToPlayer(playbackSpeed);
                playing = true;
                pausedByTransientFocusLoss = false;
                lastPeriodicPersistElapsedMs = SystemClock.elapsedRealtime();
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
        List<Chapter> chapters = getCachedAudioChapters(audioBook.getId());
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
        ChapterDao chapterDao = new ChapterDao(appContext);
        Chapter ch = chapterDao.getChapter(activeTitle.getId(), clamped);
        if (activeTitle instanceof AudioBook && ch != null) {
            long startMs = Math.max(0L, ch.getAudioStartMs());
            AudioBook audioBook = (AudioBook) activeTitle;
            if (mediaPlayer != null && mediaPlayerPrepared) {
                try {
                    mediaPlayer.seekTo((int) startMs);
                } catch (IllegalStateException ignored) {
                }
            }
            audioBook.setCurrentPositionMs(startMs);
            if (audioBook.getTotalDurationMs() > 0) {
                audioBook.setProgressPercent((int) ((startMs * 100L) / audioBook.getTotalDurationMs()));
            }
            if (!ch.isCompleted()) {
                chapterDao.updateChapterProgress(audioBook.getId(), clamped, 0, false);
            }
        }
        activeTitle.setLastOpenedAt(System.currentTimeMillis());
        new TitleDao(appContext).update(activeTitle);
        notifyListeners();
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = speed;
        if (appContext != null) {
            new SettingsDao(appContext).setDefaultAudioSpeed(speed);
        }
        if (playing) {
            applySpeedToPlayer(speed);
        }
        notifyListeners();
    }

    private void applySpeedToPlayer(float speed) {
        if (mediaPlayer != null && mediaPlayerPrepared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
            }
        }
    }

    public void syncActiveTitle(@NonNull Title updatedTitle) {
        if (activeTitle != null && activeTitle.getId() == updatedTitle.getId()) {
            activeTitle = updatedTitle;
            notifyListeners();
        }
    }

    public boolean hasPreparedAudio() {
        return activeTitle instanceof AudioBook && mediaPlayer != null && mediaPlayerPrepared;
    }

    public void persistCurrentState() {
        if (activeTitle == null || appContext == null) {
            return;
        }
        if (activeTitle instanceof AudioBook) {
            AudioBook audioBook = (AudioBook) activeTitle;
            if (mediaPlayer != null && mediaPlayerPrepared) {
                try {
                    long pos = Math.max(0L, mediaPlayer.getCurrentPosition());
                    audioBook.setCurrentPositionMs(pos);
                    long total = Math.max(mediaPlayer.getDuration(), audioBook.getTotalDurationMs());
                    if (total > 0) {
                        audioBook.setTotalDurationMs(total);
                        audioBook.setProgressPercent((int) Math.min(100L, (pos * 100L) / total));
                    }
                } catch (IllegalStateException ignored) {
                }
            }
            ChapterDao chapterDao = new ChapterDao(appContext);
            Chapter ch = chapterDao.getChapter(audioBook.getId(), audioBook.getCurrentChapter());
            if (ch != null) {
                long relMs = Math.max(0L, audioBook.getCurrentPositionMs() - ch.getAudioStartMs());
                int chPct = ch.getAudioDurationMs() > 0
                        ? (int) Math.min(100L, (relMs * 100L) / ch.getAudioDurationMs())
                        : audioBook.getProgressPercent();
                boolean keepCompleted = ch.isCompleted();
                chapterDao.updateChapterProgress(
                        audioBook.getId(),
                        ch.getChapterIndex(),
                        keepCompleted ? 100 : chPct,
                        keepCompleted
                );
            }
        }
        activeTitle.setLastOpenedAt(System.currentTimeMillis());
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
            if (mediaPlayer != null && mediaPlayerPrepared) {
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
            if (mediaPlayer != null && mediaPlayerPrepared) {
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
        mediaPlayerPrepared = false;
        pausedByTransientFocusLoss = false;
        cachedChaptersTitleId = -1;
        cachedAudioChapters = Collections.emptyList();
        unregisterNoisyReceiver();
        abandonAudioFocus();
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

    private void requestAudioFocus(@NonNull Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }
    }

    private void abandonAudioFocus() {
        if (appContext == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private void registerNoisyReceiver(@NonNull Context context) {
        if (noisyReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(becomingNoisyReceiver, filter);
            }
            noisyReceiverRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered || appContext == null) {
            return;
        }
        try {
            appContext.unregisterReceiver(becomingNoisyReceiver);
        } catch (IllegalArgumentException ignored) {
        } finally {
            noisyReceiverRegistered = false;
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
