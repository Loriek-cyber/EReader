package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Imports and refreshes audiobook files (M4B/M4A/MP3/etc.) using {@link M4BParser},
 * building hierarchical chapter trees and optionally applying Audnexus metadata.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AudioImporter {

    private static final String TAG = "AudioImporter";

    private AudioImporter() {
    }

    @Nullable
    public static AudioBook importAudiobook(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        M4BParser.M4bMetadata metadata = extractMetadataQuietly(context, uri);

        String titleText = (metadata != null && !metadata.title.isEmpty())
                ? metadata.title
                : FileScanner.stripExtension(displayName);
        String author = metadata != null ? metadata.author : "";
        String narrator = metadata != null ? metadata.narrator : "";
        long totalDurationMs = metadata != null ? metadata.totalDurationMs : 0L;

        Uri coverUri = (metadata != null && metadata.coverArtData != null)
                ? FileScanner.saveCoverBytes(context, "m4b_" + Math.abs(uri.toString().hashCode()), metadata.coverArtData)
                : null;

        List<Chapter> rootChapters;
        int totalChapterNodes;
        if (metadata != null && !metadata.chapters.isEmpty()) {
            rootChapters = buildAudiobookChapterTree(0, metadata.chapters, 0L);
            totalChapterNodes = metadata.chapters.size();
        } else {
            rootChapters = new ArrayList<>();
            rootChapters.add(new Chapter(
                    0, 0, 0, 1, 0, 0,
                    titleText, "", "", "", FileScanner.formatDurationLabel(totalDurationMs),
                    "", "", 0L, totalDurationMs, 0, false
            ));
            totalChapterNodes = 1;
        }

        AudioBook audioBook = new AudioBook(0, titleText, totalChapterNodes, uri);
        audioBook.setAuthor(author);
        audioBook.setNarrator(narrator);
        audioBook.setTotalDurationMs(totalDurationMs);
        audioBook.setCoverUri(coverUri);

        long id = titleDao.insert(audioBook);
        if (id == -1) {
            return null;
        }
        new ChapterDao(context).replaceChapterTree((int) id, rootChapters);

        SettingsDao settingsDao = new SettingsDao(context);
        if (SettingsDao.METADATA_SOURCE_AUDNEXUS.equals(settingsDao.getAudioMetadataSource())) {
            String query = audioBook.getAuthor().isEmpty()
                    ? audioBook.getTitle()
                    : audioBook.getTitle() + " " + audioBook.getAuthor();
            String region = "en".equalsIgnoreCase(settingsDao.getLanguageTag()) ? "us" : "it";
            List<AudnexusClient.SearchCandidate> candidates = AudnexusClient.search(query, region);
            if (!candidates.isEmpty()) {
                AudnexusClient.fetchAndApply(context, audioBook, candidates.get(0).asin, region);
            }
        }
        return audioBook;
    }

    @NonNull
    public static MetadataJsonParser.ApplyResult reloadAudiobookEmbeddedMetadata(
            @NonNull Context context,
            @NonNull AudioBook audioBook
    ) {
        if (audioBook.getContentUri() == null || Uri.EMPTY.equals(audioBook.getContentUri())) {
            return new MetadataJsonParser.ApplyResult(false, 0);
        }
        M4BParser.M4bMetadata metadata = extractMetadataQuietly(context.getApplicationContext(), audioBook.getContentUri());
        if (metadata == null) {
            return new MetadataJsonParser.ApplyResult(false, 0);
        }

        if (!metadata.title.isEmpty()) {
            audioBook.setTitle(metadata.title);
        }
        if (!metadata.author.isEmpty()) {
            audioBook.setAuthor(metadata.author);
        }
        if (!metadata.narrator.isEmpty()) {
            audioBook.setNarrator(metadata.narrator);
        }
        if (metadata.totalDurationMs > 0L) {
            audioBook.setTotalDurationMs(metadata.totalDurationMs);
        }
        if (metadata.coverArtData != null && metadata.coverArtData.length > 0) {
            Uri coverUri = FileScanner.saveCoverBytes(
                    context, "m4b_" + Math.abs(audioBook.getContentUri().toString().hashCode()), metadata.coverArtData);
            if (coverUri != null) {
                audioBook.setCoverUri(coverUri);
            }
        }

        int chapterCount = 0;
        if (!metadata.chapters.isEmpty()) {
            long savedPosMs = audioBook.getCurrentPositionMs();
            List<Chapter> rootChapters = buildAudiobookChapterTree(audioBook.getId(), metadata.chapters, savedPosMs);
            chapterCount = metadata.chapters.size();
            audioBook.setLenChapters(chapterCount);
            audioBook.setCurrentChapter(findMatchingLeafIndex(rootChapters, savedPosMs));
            new ChapterDao(context).replaceChapterTree(audioBook.getId(), rootChapters);
        }

        new TitleDao(context).update(audioBook);
        PlaybackSessionManager.getInstance().invalidateChapterCache(audioBook.getId());
        return new MetadataJsonParser.ApplyResult(true, chapterCount);
    }

    @NonNull
    public static List<Chapter> buildAudiobookChapterTree(
            int titleId,
            @NonNull List<M4BParser.M4bChapter> flatChapters,
            long savedPositionMs
    ) {
        List<Chapter> roots = new ArrayList<>();
        List<Chapter> stack = new ArrayList<>();

        for (M4BParser.M4bChapter ch : flatChapters) {
            long chEnd = ch.startMs + ch.durationMs;
            boolean completed = savedPositionMs > 0L && ch.durationMs > 0L && savedPositionMs >= chEnd;
            int pct = completed ? 100
                    : (savedPositionMs > 0L && savedPositionMs >= ch.startMs && ch.durationMs > 0L && savedPositionMs < chEnd)
                            ? (int) Math.min(99L, Math.max(1L, Math.round(((savedPositionMs - ch.startMs) * 100.0) / ch.durationMs)))
                            : 0;

            int level = Math.max(0, ch.level);
            while (stack.size() > level) {
                stack.remove(stack.size() - 1);
            }
            int effectiveLevel = stack.size();
            int sortOrder = stack.isEmpty() ? roots.size() : stack.get(stack.size() - 1).getChildren().size();

            Chapter node = new Chapter(
                    0, titleId, 0, ch.index, effectiveLevel, sortOrder,
                    ch.title, "", "", "", FileScanner.formatDurationLabel(ch.durationMs),
                    "", "", ch.startMs, ch.durationMs, pct, completed
            );
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.get(stack.size() - 1).addChild(node);
            }
            stack.add(node);
        }

        for (Chapter root : roots) {
            rollupContainerAudioDuration(root);
        }
        return roots;
    }

    public static boolean refreshAudiobookChaptersIfNeeded(@NonNull Context context, @NonNull AudioBook audioBook) {
        if (audioBook.getLenChapters() > 1 || audioBook.getContentUri() == null || Uri.EMPTY.equals(audioBook.getContentUri())) {
            return false;
        }
        M4BParser.M4bMetadata metadata = extractMetadataQuietly(context.getApplicationContext(), audioBook.getContentUri());
        if (metadata == null || metadata.chapters.size() <= 1) {
            return false;
        }

        long savedPosMs = audioBook.getCurrentPositionMs();
        List<Chapter> rootChapters = buildAudiobookChapterTree(audioBook.getId(), metadata.chapters, savedPosMs);
        audioBook.setLenChapters(metadata.chapters.size());
        audioBook.setCurrentChapter(findMatchingLeafIndex(rootChapters, savedPosMs));
        if (metadata.totalDurationMs > 0L && audioBook.getTotalDurationMs() <= 0L) {
            audioBook.setTotalDurationMs(metadata.totalDurationMs);
        }
        new ChapterDao(context).replaceChapterTree(audioBook.getId(), rootChapters);
        new TitleDao(context).update(audioBook);
        return true;
    }

    @Nullable
    private static M4BParser.M4bMetadata extractMetadataQuietly(@NonNull Context context, @NonNull Uri uri) {
        try {
            return new M4BParser().parseM4bMetadata(context, uri).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "M4B metadata extraction failed for " + uri, e);
            return null;
        }
    }

    private static int findMatchingLeafIndex(@NonNull List<Chapter> roots, long savedPosMs) {
        List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
        int matched = leaves.isEmpty() ? 1 : leaves.get(0).getChapterIndex();
        for (Chapter leaf : leaves) {
            long start = leaf.getAudioStartMs();
            long end = start + leaf.getAudioDurationMs();
            if (savedPosMs >= start && (leaf.getAudioDurationMs() <= 0L || savedPosMs < end)) {
                return leaf.getChapterIndex();
            }
        }
        return matched;
    }

    private static long rollupContainerAudioDuration(@NonNull Chapter node) {
        if (node.getChildren().isEmpty()) {
            return node.getAudioStartMs() + node.getAudioDurationMs();
        }
        long maxEnd = node.getAudioStartMs() + node.getAudioDurationMs();
        for (Chapter child : node.getChildren()) {
            maxEnd = Math.max(maxEnd, rollupContainerAudioDuration(child));
        }
        long totalDur = Math.max(node.getAudioDurationMs(), maxEnd - node.getAudioStartMs());
        node.setAudioDurationMs(totalDur);
        node.setPageOrDurationLabel(FileScanner.formatDurationLabel(totalDur));
        return maxEnd;
    }
}
