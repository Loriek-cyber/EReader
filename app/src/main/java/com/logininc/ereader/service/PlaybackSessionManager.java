package com.logininc.ereader.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.util.ChapterTreeHelper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates Media3 {@link ExoPlayer} playback, MediaSession/Notification updates,
 * chapter transitions, AudioFocus/Bluetooth disconnect behavior, and SQLite persistence.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackSessionManager {

    public interface SessionListener {
        void onSessionChanged(@Nullable Title activeTitle, boolean isPlaying, long positionMs, long durationMs);

        default void onChapterCompleted(@NonNull Title title, int completedChapterIndex) {
        }
    }

    private static final long PERIODIC_PERSIST_INTERVAL_MS = 10_000L;
    private static final long SEEK_BACK_MS = 15_000L;
    private static final long SEEK_FORWARD_MS = 30_000L;

    private static volatile PlaybackSessionManager instance;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final AudioChapterTracker chapterTracker = new AudioChapterTracker();

    @Nullable
    private Title activeTitle;
    @Nullable
    private ExoPlayer exoPlayer;
    @Nullable
    private ForwardingPlayer sessionPlayer;
    @Nullable
    private AudioPlaybackService boundService;
    @Nullable
    private ListenableFuture<MediaController> controllerFuture;
    @Nullable
    private BluetoothReconnectWatcher bluetoothWatcher;
    @Nullable
    private Context appContext;

    private boolean audioPrepared = false;
    private boolean playing = false;
    private boolean skipNextResumeRewind = false;
    private float playbackSpeed = 1.0f;
    private boolean tickerRunning = false;
    private long lastPeriodicPersistElapsedMs = 0L;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (!(activeTitle instanceof AudioBook)) {
                return;
            }
            if (playbackState == Player.STATE_READY && exoPlayer != null) {
                audioPrepared = true;
                long durationMs = exoPlayer.getDuration();
                AudioBook ab = (AudioBook) activeTitle;
                if (durationMs > 0) {
                    ab.setTotalDurationMs(durationMs);
                    ab.setProgressPercent(computeProgressPercent(getCurrentPositionMs(), durationMs));
                }
                notifyListeners();
            } else if (playbackState == Player.STATE_ENDED && audioPrepared) {
                handleAudioTrackCompleted();
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (!(activeTitle instanceof AudioBook)) {
                return;
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                onPausedByPlayer(true);
            } else if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                onPausedByPlayer(false);
            } else if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                playing = true;
                if (appContext != null) {
                    new SettingsDao(appContext).setActiveSessionPlaying(true);
                    SleepTimerController.getInstance().onSessionActive(appContext);
                }
                startTickerIfNeeded();
                notifyListeners();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (!(activeTitle instanceof AudioBook)) {
                return;
            }
            playing = isPlaying;
            if (isPlaying) {
                startTickerIfNeeded();
            }
            notifyListeners();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            audioPrepared = false;
            playing = false;
            notifyListeners();
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (activeTitle == null) {
                tickerRunning = false;
                return;
            }
            if (activeTitle instanceof AudioBook && exoPlayer != null && audioPrepared && playing) {
                AudioBook audioBook = (AudioBook) activeTitle;
                long currentMs = Math.max(0L, exoPlayer.getCurrentPosition());
                long totalMs = Math.max(exoPlayer.getDuration(), audioBook.getTotalDurationMs());
                audioBook.setCurrentPositionMs(currentMs);
                if (totalMs > 0) {
                    audioBook.setTotalDurationMs(totalMs);
                    audioBook.setProgressPercent(computeProgressPercent(currentMs, totalMs));
                }
                syncAudioChapterProgress(audioBook, currentMs);
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

    @Nullable
    Context getAppContextOrNull() {
        return appContext;
    }

    public void addListener(@NonNull SessionListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull SessionListener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public synchronized ForwardingPlayer getOrCreateSessionPlayer(@NonNull Context context) {
        initAppContext(context);
        ExoPlayer player = ensureExoPlayer(appContext);
        if (sessionPlayer == null) {
            sessionPlayer = new AudiobookForwardingPlayer(player, this, SEEK_BACK_MS, SEEK_FORWARD_MS);
        }
        return sessionPlayer;
    }

    public void attachService(@Nullable AudioPlaybackService service) {
        this.boundService = service;
        if (service != null && activeTitle instanceof AudioBook) {
            service.updateSessionActivity(activeTitle.getId());
        }
    }

    public void detachService(@NonNull AudioPlaybackService service) {
        if (this.boundService == service) {
            this.boundService = null;
        }
    }

    @Nullable
    public Title restoreIfNeeded(@NonNull Context context) {
        initAppContext(context);
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
        playing = false;
        return activeTitle;
    }

    public static boolean isNewTitleSwitch(int currentTitleId, int newTitleId) {
        return currentTitleId > 0 && currentTitleId != newTitleId;
    }

    public static int computeProgressPercent(long positionMs, long durationMs) {
        if (durationMs <= 0L || positionMs <= 0L) {
            return 0;
        }
        if (positionMs >= durationMs) {
            return 100;
        }
        int rounded = (int) Math.round((positionMs * 100.0) / durationMs);
        return Math.max(1, Math.min(99, rounded));
    }

    public static long applyResumeRewindMs(long currentPositionMs, int rewindSeconds) {
        return applyResumeRewindMs(currentPositionMs, rewindSeconds, 0L);
    }

    public static long applyResumeRewindMs(long currentPositionMs, int rewindSeconds, long minPositionMs) {
        long floor = Math.max(0L, minPositionMs);
        if (currentPositionMs <= floor || rewindSeconds <= 0) {
            return Math.max(floor, currentPositionMs);
        }
        return Math.max(floor, currentPositionMs - (rewindSeconds * 1000L));
    }

    public void skipNextResumeRewind() {
        skipNextResumeRewind = true;
    }

    public void invalidateChapterCache(int titleId) {
        chapterTracker.invalidate(titleId);
        if (!(activeTitle instanceof AudioBook) || activeTitle.getId() != titleId || appContext == null) {
            return;
        }
        Title refreshed = new TitleDao(appContext).getById(titleId);
        if (refreshed instanceof AudioBook) {
            activeTitle = refreshed;
        }
        mainHandler.post(() -> {
            if (activeTitle instanceof AudioBook && activeTitle.getId() == titleId) {
                refreshMediaItemMetadata((AudioBook) activeTitle, true);
            }
            notifyListeners();
        });
    }

    public void startOrSwitchSession(@NonNull Context context, @NonNull Title title, int chapterIndex, boolean startPlaying) {
        initAppContext(context);
        SettingsDao settingsDao = new SettingsDao(appContext);
        TitleDao titleDao = new TitleDao(appContext);

        boolean switchingTitle = activeTitle != null && isNewTitleSwitch(activeTitle.getId(), title.getId());
        if (switchingTitle) {
            persistCurrentState();
            stopAudioEngine(false);
        }

        boolean switchingChapter = !switchingTitle
                && activeTitle != null
                && activeTitle.getCurrentChapter() != Math.max(1, chapterIndex);
        boolean sameAudioReady = !switchingTitle
                && activeTitle instanceof AudioBook
                && title instanceof AudioBook
                && exoPlayer != null
                && audioPrepared;

        if (sameAudioReady && !switchingChapter) {
            AudioBook audioBook = (AudioBook) title;
            long livePos = getCurrentPositionMs();
            audioBook.setCurrentPositionMs(livePos);
            long totalMs = Math.max(exoPlayer.getDuration(), audioBook.getTotalDurationMs());
            if (totalMs > 0) {
                audioBook.setTotalDurationMs(totalMs);
                audioBook.setProgressPercent(computeProgressPercent(livePos, totalMs));
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

        if (!(title instanceof AudioBook)) {
            stopAudioEngine(true);
            playing = true;
            settingsDao.setActiveSessionPlaying(true);
            SleepTimerController.getInstance().onSessionActive(appContext);
            startTickerIfNeeded();
            notifyListeners();
            return;
        }

        AudioBook audioBook = (AudioBook) title;
        if (boundService != null) {
            boundService.updateSessionActivity(audioBook.getId());
        }
        if (!sameAudioReady) {
            prepareAndPlayAudio(appContext, audioBook, chapterIndex, startPlaying);
            return;
        }
        if (switchingChapter) {
            skipNextResumeRewind = true;
            seekToChapterStartIfOutOfRange(audioBook, chapterIndex);
            refreshMediaItemMetadata(audioBook, true);
        }
        if (startPlaying && !playing) {
            resume(appContext);
        } else {
            skipNextResumeRewind = false;
            notifyListeners();
        }
    }

    public void pause(@NonNull Context context) {
        initAppContext(context);
        playing = false;
        if (bluetoothWatcher != null) {
            bluetoothWatcher.clearPendingResume();
        }
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
        persistCurrentState();
        new SettingsDao(appContext).setActiveSessionPlaying(false);
        SleepTimerController.getInstance().onSessionPaused();
        notifyListeners();
    }

    public void pauseEbookReadingSession(@NonNull Context context) {
        if (activeTitle instanceof Book && playing) {
            initAppContext(context);
            playing = false;
            new SettingsDao(appContext).setActiveSessionPlaying(false);
            SleepTimerController.getInstance().onSessionPaused();
            notifyListeners();
        }
    }

    public void resume(@NonNull Context context) {
        initAppContext(context);
        if (activeTitle == null) {
            restoreIfNeeded(appContext);
        }
        if (activeTitle == null) {
            return;
        }
        if (!(activeTitle instanceof AudioBook)) {
            playing = true;
            new SettingsDao(appContext).setActiveSessionPlaying(true);
            SleepTimerController.getInstance().onSessionActive(appContext);
            startTickerIfNeeded();
            notifyListeners();
            return;
        }

        AudioBook audioBook = (AudioBook) activeTitle;
        if (exoPlayer == null || !audioPrepared) {
            prepareAndPlayAudio(appContext, audioBook, audioBook.getCurrentChapter(), true);
            return;
        }
        boolean applyRewind = !skipNextResumeRewind;
        skipNextResumeRewind = false;
        int rewindSec = applyRewind ? new SettingsDao(appContext).getAudioResumeRewindSec() : 0;
        long currentPos = getCurrentPositionMs();
        long chapterFloorMs = chapterTracker.resolveCurrentChapterStartMs(appContext, audioBook);
        if (rewindSec > 0 && currentPos > chapterFloorMs) {
            long targetMs = applyResumeRewindMs(currentPos, rewindSec, chapterFloorMs);
            audioBook.setCurrentPositionMs(targetMs);
            if (audioBook.getTotalDurationMs() > 0L) {
                audioBook.setProgressPercent(computeProgressPercent(targetMs, audioBook.getTotalDurationMs()));
            }
            exoPlayer.seekTo(targetMs);
        }
        if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
            exoPlayer.seekTo(Math.max(0L, audioBook.getCurrentPositionMs()));
        }
        beginAudioPlayback(appContext, audioBook);
        notifyListeners();
    }

    public void togglePlayPause(@NonNull Context context) {
        if (playing) {
            pause(context);
        } else {
            resume(context);
        }
    }

    public void stopAndClearSession(@NonNull Context context) {
        initAppContext(context);
        persistCurrentState();
        stopAudioEngine(true);
        playing = false;
        activeTitle = null;
        SettingsDao settingsDao = new SettingsDao(appContext);
        settingsDao.setActiveSessionTitleId(-1);
        settingsDao.setActiveSessionPlaying(false);
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
        long total = audioBook.getTotalDurationMs();
        long clamped = Math.max(0L, total > 0L ? Math.min(total, positionMs) : positionMs);
        if (exoPlayer != null && audioPrepared) {
            exoPlayer.seekTo(clamped);
        }
        audioBook.setCurrentPositionMs(clamped);
        if (total > 0L) {
            audioBook.setProgressPercent(computeProgressPercent(clamped, total));
        }
        if (chapterTracker.syncCurrentChapterOnSeek(appContext, audioBook, clamped)) {
            refreshMediaItemMetadata(audioBook, false);
        }
        persistCurrentState();
        notifyListeners();
    }

    public void shiftChapter(@NonNull Context context, int delta) {
        if (activeTitle == null) {
            return;
        }
        initAppContext(context);
        int current = activeTitle.getCurrentChapter();
        List<Chapter> flat = new ChapterDao(appContext).getChapterSummariesForTitle(activeTitle.getId());
        List<Chapter> roots = ChapterDao.buildTreeFromFlat(flat);
        int target = delta > 0
                ? ChapterTreeHelper.findNextLeafIndex(roots, current)
                : ChapterTreeHelper.findPreviousLeafIndex(roots, current);
        if (target > 0 && target != current) {
            jumpToChapter(appContext, target);
        }
    }

    public void jumpToChapter(@NonNull Context context, int chapterIndex) {
        if (activeTitle == null) {
            return;
        }
        initAppContext(context);
        int clamped = Math.max(1, Math.min(activeTitle.getLenChapters(), chapterIndex));
        activeTitle.setCurrentChapter(clamped);
        activeTitle.setCurrentChapterCompleted(false);
        activeTitle.setStatus(Title.STATUS_IN_PROGRESS);
        ChapterDao chapterDao = new ChapterDao(appContext);
        Chapter ch = chapterDao.getChapter(activeTitle.getId(), clamped);
        if (activeTitle instanceof AudioBook && ch != null) {
            long startMs = Math.max(0L, ch.getAudioStartMs());
            AudioBook audioBook = (AudioBook) activeTitle;
            if (exoPlayer != null && audioPrepared) {
                exoPlayer.seekTo(startMs);
            }
            audioBook.setCurrentPositionMs(startMs);
            if (audioBook.getTotalDurationMs() > 0) {
                audioBook.setProgressPercent(computeProgressPercent(startMs, audioBook.getTotalDurationMs()));
            }
            if (!ch.isCompleted()) {
                chapterDao.updateChapterProgress(audioBook.getId(), clamped, 0, false);
            }
            refreshMediaItemMetadata(audioBook, true);
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
        if (exoPlayer != null) {
            exoPlayer.setPlaybackSpeed(speed);
        }
        notifyListeners();
    }

    public void syncActiveTitle(@NonNull Title updatedTitle) {
        if (activeTitle != null && activeTitle.getId() == updatedTitle.getId()) {
            activeTitle = updatedTitle;
            if (updatedTitle instanceof AudioBook) {
                refreshMediaItemMetadata((AudioBook) updatedTitle, true);
            }
            notifyListeners();
        }
    }

    public boolean hasPreparedAudio() {
        return activeTitle instanceof AudioBook && exoPlayer != null && audioPrepared;
    }

    public void persistCurrentState() {
        if (activeTitle == null || appContext == null) {
            return;
        }
        if (activeTitle instanceof AudioBook) {
            persistAudioPosition((AudioBook) activeTitle);
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
        if (!(activeTitle instanceof AudioBook)) {
            return 0L;
        }
        if (exoPlayer != null && audioPrepared) {
            return Math.max(0L, exoPlayer.getCurrentPosition());
        }
        return ((AudioBook) activeTitle).getCurrentPositionMs();
    }

    public long getTotalDurationMs() {
        if (!(activeTitle instanceof AudioBook)) {
            return 0L;
        }
        if (exoPlayer != null && audioPrepared) {
            long d = exoPlayer.getDuration();
            if (d > 0) {
                return d;
            }
        }
        return ((AudioBook) activeTitle).getTotalDurationMs();
    }

    private void prepareAndPlayAudio(
            @NonNull Context context,
            @NonNull AudioBook audioBook,
            int chapterIndex,
            boolean startPlaying
    ) {
        ExoPlayer player = ensureExoPlayer(context);
        audioPrepared = false;
        long initialPosMs = chapterTracker.resolveInitialSeekTarget(
                context, audioBook, chapterIndex, startPlaying, skipNextResumeRewind);
        skipNextResumeRewind = false;

        audioBook.setCurrentPositionMs(initialPosMs);
        if (audioBook.getTotalDurationMs() > 0) {
            audioBook.setProgressPercent(computeProgressPercent(initialPosMs, audioBook.getTotalDurationMs()));
        }
        new TitleDao(context).update(audioBook);

        chapterTracker.shouldRefreshMetadata(audioBook.getCurrentChapter(), true);
        MediaItem mediaItem = chapterTracker.buildMediaItem(appContext, audioBook);
        player.setPlaybackSpeed(playbackSpeed);
        player.setMediaItem(mediaItem, initialPosMs);
        player.prepare();
        audioPrepared = true;

        if (startPlaying) {
            beginAudioPlayback(context, audioBook);
        } else {
            player.setPlayWhenReady(false);
            playing = false;
            new SettingsDao(context).setActiveSessionPlaying(false);
        }
        notifyListeners();
    }

    private void beginAudioPlayback(@NonNull Context context, @NonNull AudioBook audioBook) {
        ExoPlayer player = ensureExoPlayer(context);
        if (bluetoothWatcher != null) {
            bluetoothWatcher.clearPendingResume();
        }
        ensureServiceConnected(context);
        player.setPlaybackSpeed(playbackSpeed);
        player.setPlayWhenReady(true);
        playing = true;
        lastPeriodicPersistElapsedMs = SystemClock.elapsedRealtime();
        new SettingsDao(context).setActiveSessionPlaying(true);
        SleepTimerController.getInstance().onSessionActive(context);
        startTickerIfNeeded();
    }

    private void onPausedByPlayer(boolean markNoisyDisconnect) {
        playing = false;
        if (markNoisyDisconnect && bluetoothWatcher != null) {
            bluetoothWatcher.markPausedByNoisyDisconnect();
        }
        persistCurrentState();
        if (appContext != null) {
            new SettingsDao(appContext).setActiveSessionPlaying(false);
        }
        SleepTimerController.getInstance().onSessionPaused();
        notifyListeners();
    }

    private void seekToChapterStartIfOutOfRange(@NonNull AudioBook audioBook, int chapterIndex) {
        if (appContext == null) {
            return;
        }
        Chapter ch = new ChapterDao(appContext).getChapter(audioBook.getId(), Math.max(1, chapterIndex));
        if (ch == null) {
            return;
        }
        long chStart = ch.getAudioStartMs();
        long chEnd = chStart + ch.getAudioDurationMs();
        long pos = audioBook.getCurrentPositionMs();
        if (pos < chStart || (ch.getAudioDurationMs() > 0 && pos >= chEnd)) {
            seekToMs(chStart);
        }
    }

    private void handleAudioTrackCompleted() {
        if (activeTitle == null || appContext == null) {
            return;
        }
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
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
        new SettingsDao(appContext).setActiveSessionPlaying(false);
        SleepTimerController.getInstance().onSessionPaused();
        notifyListeners();
        for (SessionListener listener : listeners) {
            listener.onChapterCompleted(activeTitle, completedChapter);
        }
    }

    private void syncAudioChapterProgress(@NonNull AudioBook audioBook, long currentMs) {
        if (appContext == null) {
            return;
        }
        AudioChapterTracker.SyncOutcome outcome =
                chapterTracker.syncProgress(appContext, dbExecutor, audioBook, currentMs);
        if (outcome.pauseAtBoundary && outcome.nextBoundaryChapter != null) {
            pauseAtChapterBoundary(audioBook, outcome.nextBoundaryChapter);
            return;
        }
        if (outcome.chapterChanged) {
            refreshMediaItemMetadata(audioBook, false);
        }
        audioBook.setLastOpenedAt(System.currentTimeMillis());
        maybePersistAudioStateThrottled(
                audioBook,
                outcome.targetChapterIndex,
                outcome.targetChapterPct,
                outcome.targetChapterCompleted,
                outcome.chapterChanged
        );
    }

    private void pauseAtChapterBoundary(@NonNull AudioBook audioBook, @NonNull Chapter nextChapter) {
        long nextStartMs = Math.max(0L, nextChapter.getAudioStartMs());
        audioBook.setCurrentPositionMs(nextStartMs);
        if (audioBook.getTotalDurationMs() > 0L) {
            audioBook.setProgressPercent(computeProgressPercent(nextStartMs, audioBook.getTotalDurationMs()));
        }
        audioBook.setLastOpenedAt(System.currentTimeMillis());
        skipNextResumeRewind = true;
        playing = false;
        if (bluetoothWatcher != null) {
            bluetoothWatcher.clearPendingResume();
        }
        if (exoPlayer != null && audioPrepared) {
            exoPlayer.setPlayWhenReady(false);
            exoPlayer.seekTo(nextStartMs);
        }
        refreshMediaItemMetadata(audioBook, false);
        if (appContext != null) {
            new SettingsDao(appContext).setActiveSessionPlaying(false);
        }
        SleepTimerController.getInstance().onSessionPaused();
        maybePersistAudioStateThrottled(audioBook, nextChapter.getChapterIndex(), 0, false, true);
        notifyListeners();
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
        Context ctx = appContext;
        int titleId = audioBook.getId();
        AudioBook snapshot = snapshotAudioBook(audioBook);

        dbExecutor.execute(() -> {
            if (chapterIndex > 0) {
                new ChapterDao(ctx).updateChapterProgress(titleId, chapterIndex, chapterPct, chapterCompleted);
            }
            new TitleDao(ctx).update(snapshot);
        });
    }

    private void persistAudioPosition(@NonNull AudioBook audioBook) {
        if (exoPlayer != null && audioPrepared) {
            long pos = Math.max(0L, exoPlayer.getCurrentPosition());
            audioBook.setCurrentPositionMs(pos);
            long total = Math.max(exoPlayer.getDuration(), audioBook.getTotalDurationMs());
            if (total > 0) {
                audioBook.setTotalDurationMs(total);
                audioBook.setProgressPercent(computeProgressPercent(pos, total));
            }
        } else if (audioBook.getTotalDurationMs() > 0L) {
            audioBook.setProgressPercent(computeProgressPercent(
                    audioBook.getCurrentPositionMs(), audioBook.getTotalDurationMs()));
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

    private void initAppContext(@NonNull Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (bluetoothWatcher == null) {
            bluetoothWatcher = new BluetoothReconnectWatcher(appContext, () -> {
                if (activeTitle instanceof AudioBook && !playing && appContext != null) {
                    resume(appContext);
                }
            });
            bluetoothWatcher.register();
        }
    }

    @NonNull
    private ExoPlayer ensureExoPlayer(@NonNull Context context) {
        if (exoPlayer == null) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build();
            exoPlayer = new ExoPlayer.Builder(context.getApplicationContext())
                    .setAudioAttributes(attributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .build();
            exoPlayer.addListener(playerListener);
        }
        return exoPlayer;
    }

    private void ensureServiceConnected(@NonNull Context context) {
        if (controllerFuture != null) {
            return;
        }
        SessionToken token = new SessionToken(context, new ComponentName(context, AudioPlaybackService.class));
        controllerFuture = new MediaController.Builder(context, token).buildAsync();
    }

    private void releaseServiceConnection(@NonNull Context context) {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        try {
            context.stopService(new Intent(context, AudioPlaybackService.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void stopAudioEngine(boolean stopService) {
        audioPrepared = false;
        chapterTracker.resetMetadataTrack();
        if (bluetoothWatcher != null) {
            bluetoothWatcher.clearPendingResume();
        }
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        if (stopService && appContext != null) {
            releaseServiceConnection(appContext);
        }
    }

    private void startTickerIfNeeded() {
        if (!tickerRunning) {
            tickerRunning = true;
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.postDelayed(progressTicker, 1000L);
        }
    }

    private void refreshMediaItemMetadata(@NonNull AudioBook audioBook, boolean force) {
        if (exoPlayer == null || exoPlayer.getMediaItemCount() == 0) {
            return;
        }
        if (!chapterTracker.shouldRefreshMetadata(audioBook.getCurrentChapter(), force)) {
            return;
        }
        exoPlayer.replaceMediaItem(0, chapterTracker.buildMediaItem(appContext, audioBook));
    }

    @NonNull
    private static AudioBook snapshotAudioBook(@NonNull AudioBook src) {
        AudioBook copy = new AudioBook(src.getId(), src.getTitle(), src.getLenChapters(), src.getContentUri());
        copy.setAuthor(src.getAuthor());
        copy.setNarrator(src.getNarrator());
        copy.setCurrentChapter(src.getCurrentChapter());
        copy.setCurrentChapterCompleted(src.isCurrentChapterCompleted());
        copy.setProgressPercent(src.getProgressPercent());
        copy.setCurrentPositionMs(src.getCurrentPositionMs());
        copy.setTotalDurationMs(src.getTotalDurationMs());
        copy.setCoverUri(src.getCoverUri());
        copy.setStatus(src.getStatus());
        copy.setLastOpenedAt(src.getLastOpenedAt());
        return copy;
    }

    private void notifyListeners() {
        long pos = getCurrentPositionMs();
        long dur = getTotalDurationMs();
        for (SessionListener listener : listeners) {
            listener.onSessionChanged(activeTitle, playing, pos, dur);
        }
    }
}
