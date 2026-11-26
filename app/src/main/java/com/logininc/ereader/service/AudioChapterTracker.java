package com.logininc.ereader.service;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.util.ChapterTreeHelper;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Tracks leaf audio chapter boundaries, metadata snapshots for Media3 notifications,
 * and position-to-chapter synchronization.
 */
class AudioChapterTracker {

    static final class SyncOutcome {
        final boolean chapterChanged;
        final boolean pauseAtBoundary;
        @Nullable
        final Chapter nextBoundaryChapter;
        final int targetChapterIndex;
        final int targetChapterPct;
        final boolean targetChapterCompleted;

        SyncOutcome(
                boolean chapterChanged,
                boolean pauseAtBoundary,
                @Nullable Chapter nextBoundaryChapter,
                int targetChapterIndex,
                int targetChapterPct,
                boolean targetChapterCompleted
        ) {
            this.chapterChanged = chapterChanged;
            this.pauseAtBoundary = pauseAtBoundary;
            this.nextBoundaryChapter = nextBoundaryChapter;
            this.targetChapterIndex = targetChapterIndex;
            this.targetChapterPct = targetChapterPct;
            this.targetChapterCompleted = targetChapterCompleted;
        }
    }

    private int cachedChaptersTitleId = -1;
    private int lastMetadataChapterIndex = -1;
    @NonNull
    private List<Chapter> cachedAudioChapters = Collections.emptyList();

    void invalidate(int titleId) {
        if (cachedChaptersTitleId == titleId) {
            cachedChaptersTitleId = -1;
            cachedAudioChapters = Collections.emptyList();
        }
    }

    void resetMetadataTrack() {
        lastMetadataChapterIndex = -1;
    }

    boolean shouldRefreshMetadata(int chapterIndex, boolean force) {
        if (!force && chapterIndex == lastMetadataChapterIndex) {
            return false;
        }
        lastMetadataChapterIndex = chapterIndex;
        return true;
    }

    @NonNull
    List<Chapter> getLeafChapters(@Nullable Context context, int titleId) {
        if (context == null) {
            return Collections.emptyList();
        }
        if (cachedChaptersTitleId != titleId || cachedAudioChapters.isEmpty()) {
            List<Chapter> flat = new ChapterDao(context).getChapterSummariesForTitle(titleId);
            List<Chapter> roots = ChapterDao.buildTreeFromFlat(flat);
            List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
            cachedAudioChapters = leaves.isEmpty() ? flat : leaves;
            cachedChaptersTitleId = titleId;
        }
        return cachedAudioChapters;
    }

    long resolveCurrentChapterStartMs(@Nullable Context context, @NonNull AudioBook audioBook) {
        for (Chapter ch : getLeafChapters(context, audioBook.getId())) {
            if (ch.getChapterIndex() == audioBook.getCurrentChapter()) {
                return Math.max(0L, ch.getAudioStartMs());
            }
        }
        return 0L;
    }

    @Nullable
    Chapter findChapterAtPosition(@Nullable Context context, int titleId, long positionMs) {
        for (Chapter ch : getLeafChapters(context, titleId)) {
            long start = ch.getAudioStartMs();
            long end = start + ch.getAudioDurationMs();
            if (positionMs >= start && (ch.getAudioDurationMs() <= 0 || positionMs < end)) {
                return ch;
            }
        }
        return null;
    }

    boolean syncCurrentChapterOnSeek(@Nullable Context context, @NonNull AudioBook audioBook, long positionMs) {
        Chapter ch = findChapterAtPosition(context, audioBook.getId(), positionMs);
        if (ch == null || ch.getChapterIndex() == audioBook.getCurrentChapter()) {
            return false;
        }
        audioBook.setCurrentChapter(ch.getChapterIndex());
        audioBook.setCurrentChapterCompleted(false);
        return true;
    }

    long resolveInitialSeekTarget(
            @NonNull Context context,
            @NonNull AudioBook audioBook,
            int chapterIndex,
            boolean startPlaying,
            boolean skipNextResumeRewind
    ) {
        long targetMs = audioBook.getCurrentPositionMs();
        long chapterFloorMs = 0L;
        Chapter chapter = new ChapterDao(context).getChapter(audioBook.getId(), chapterIndex);
        if (chapter != null) {
            long chStart = chapter.getAudioStartMs();
            long chEnd = chStart + chapter.getAudioDurationMs();
            chapterFloorMs = chStart;
            boolean outOfChapter = targetMs < chStart || (chapter.getAudioDurationMs() > 0 && targetMs >= chEnd);
            if (skipNextResumeRewind || targetMs <= 0L) {
                if (outOfChapter) {
                    targetMs = chStart;
                }
            } else if (outOfChapter) {
                syncCurrentChapterOnSeek(context, audioBook, targetMs);
                Chapter synced = new ChapterDao(context).getChapter(audioBook.getId(), audioBook.getCurrentChapter());
                if (synced != null) {
                    chapterFloorMs = synced.getAudioStartMs();
                }
            }
        }
        if (startPlaying && targetMs > chapterFloorMs && !skipNextResumeRewind) {
            int rewindSec = new SettingsDao(context).getAudioResumeRewindSec();
            if (rewindSec > 0) {
                targetMs = PlaybackSessionManager.applyResumeRewindMs(targetMs, rewindSec, chapterFloorMs);
            }
        }
        long knownTotal = audioBook.getTotalDurationMs();
        if (knownTotal > 0 && targetMs >= knownTotal) {
            targetMs = Math.max(0L, knownTotal - 1000L);
        }
        return Math.max(0L, targetMs);
    }

    @NonNull
    SyncOutcome syncProgress(
            @NonNull Context context,
            @NonNull Executor dbExecutor,
            @NonNull AudioBook audioBook,
            long currentMs
    ) {
        List<Chapter> chapters = getLeafChapters(context, audioBook.getId());
        if (chapters.isEmpty()) {
            return new SyncOutcome(false, false, null, -1, 0, false);
        }
        int activeIdx = audioBook.getCurrentChapter();
        int activeListPos = -1;
        Chapter activeCh = null;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getChapterIndex() == activeIdx) {
                activeCh = chapters.get(i);
                activeListPos = i;
                break;
            }
        }

        boolean chapterChanged = false;
        Chapter current = activeCh;
        if (activeCh != null && activeCh.getAudioDurationMs() > 0 && activeListPos >= 0) {
            long chEnd = activeCh.getAudioStartMs() + activeCh.getAudioDurationMs();
            if (currentMs >= chEnd && activeListPos < chapters.size() - 1) {
                current = advancePastFinishedChapter(context, dbExecutor, audioBook, chapters, activeListPos, currentMs);
                chapterChanged = true;
                if (!new SettingsDao(context).isAudioAutoAdvanceChapter()) {
                    return new SyncOutcome(true, true, current, current.getChapterIndex(), 0, false);
                }
            }
        }

        if (current == null) {
            current = findChapterAtPosition(context, audioBook.getId(), currentMs);
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
        return new SyncOutcome(chapterChanged, false, null, targetChapterIdx, targetChapterPct, targetChapterCompleted);
    }

    @NonNull
    MediaItem buildMediaItem(@Nullable Context context, @NonNull AudioBook audioBook) {
        String chapterTitle = resolveChapterTitle(context, audioBook);
        String displayTitle = chapterTitle != null ? chapterTitle : audioBook.getTitle();
        String artist = audioBook.getAuthor().isEmpty()
                ? audioBook.getTitle()
                : audioBook.getTitle() + " • " + audioBook.getAuthor();

        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(artist)
                .setAlbumTitle(audioBook.getTitle())
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK);
        Uri artworkUri = resolveValidArtworkUri(audioBook.getCoverUri());
        if (artworkUri != null) {
            meta.setArtworkUri(artworkUri);
        }
        return new MediaItem.Builder()
                .setMediaId("audiobook-" + audioBook.getId())
                .setUri(audioBook.getContentUri())
                .setMediaMetadata(meta.build())
                .build();
    }

    @NonNull
    private Chapter advancePastFinishedChapter(
            @NonNull Context context,
            @NonNull Executor dbExecutor,
            @NonNull AudioBook audioBook,
            @NonNull List<Chapter> chapters,
            int activeListPos,
            long currentMs
    ) {
        markChapterCompleteAsync(context, dbExecutor, audioBook.getId(), chapters.get(activeListPos));
        Chapter nextChapter = chapters.get(activeListPos + 1);
        for (int i = activeListPos + 1; i < chapters.size(); i++) {
            Chapter candidate = chapters.get(i);
            long end = candidate.getAudioStartMs() + candidate.getAudioDurationMs();
            if (candidate.getAudioDurationMs() > 0 && currentMs >= end && i < chapters.size() - 1) {
                markChapterCompleteAsync(context, dbExecutor, audioBook.getId(), candidate);
            } else {
                nextChapter = candidate;
                break;
            }
        }
        audioBook.setCurrentChapter(nextChapter.getChapterIndex());
        audioBook.setCurrentChapterCompleted(false);
        return nextChapter;
    }

    private void markChapterCompleteAsync(
            @NonNull Context context,
            @NonNull Executor dbExecutor,
            int titleId,
            @NonNull Chapter chapter
    ) {
        chapter.setProgressPercent(100);
        chapter.setCompleted(true);
        int idx = chapter.getChapterIndex();
        dbExecutor.execute(() -> new ChapterDao(context).updateChapterProgress(titleId, idx, 100, true));
    }

    @Nullable
    private String resolveChapterTitle(@Nullable Context context, @NonNull AudioBook audioBook) {
        int currentIdx = audioBook.getCurrentChapter();
        for (Chapter ch : getLeafChapters(context, audioBook.getId())) {
            if (ch.getChapterIndex() == currentIdx && !ch.getChapterTitle().isEmpty()) {
                return ch.getChapterTitle();
            }
        }
        return null;
    }

    @Nullable
    private static Uri resolveValidArtworkUri(@Nullable Uri uri) {
        if (uri == null) {
            return null;
        }
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return (path != null && new File(path).exists()) ? uri : null;
        }
        return uri;
    }
}
