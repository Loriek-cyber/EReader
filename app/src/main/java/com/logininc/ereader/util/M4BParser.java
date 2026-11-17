package com.logininc.ereader.util;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.inspector.MetadataRetriever;
import androidx.media3.extractor.metadata.flac.PictureFrame;
import androidx.media3.extractor.metadata.id3.ApicFrame;
import androidx.media3.extractor.metadata.id3.ChapterFrame;
import androidx.media3.extractor.metadata.id3.Id3Frame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Parses M4B/audiobook metadata and chapters using AndroidX Media3 {@link MetadataRetriever}
 * with {@link MediaMetadataRetriever} fallback for duration, tags, and embedded artwork.
 */
@UnstableApi
public class M4BParser {

    private static final String TAG = "M4BParser";

    /**
     * Parsed chapter entry extracted from an M4B/audiobook file.
     */
    public static final class M4bChapter {
        public final int index;
        @NonNull
        public final String title;
        public final long startMs;
        public final long durationMs;

        public M4bChapter(int index, @NonNull String title, long startMs, long durationMs) {
            this.index = index;
            this.title = title;
            this.startMs = Math.max(0L, startMs);
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    /**
     * Parsed audiobook metadata and chapter list returned by {@link M4BParser}.
     */
    public static final class M4bMetadata {
        @NonNull
        public final String title;
        @NonNull
        public final String author;
        @NonNull
        public final String narrator;
        public final long totalDurationMs;
        @Nullable
        public final byte[] coverArtData;
        @NonNull
        public final List<M4bChapter> chapters;

        public M4bMetadata(
                @NonNull String title,
                @NonNull String author,
                @NonNull String narrator,
                long totalDurationMs,
                @Nullable byte[] coverArtData,
                @NonNull List<M4bChapter> chapters
        ) {
            this.title = title;
            this.author = author;
            this.narrator = narrator;
            this.totalDurationMs = Math.max(0L, totalDurationMs);
            this.coverArtData = coverArtData;
            this.chapters = Collections.unmodifiableList(chapters);
        }
    }

    public static boolean isM4bOrAudioMime(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.startsWith("audio/")
                || "application/mp4".equals(lower)
                || "application/x-m4b".equals(lower);
    }

    @NonNull
    public ListenableFuture<M4bMetadata> parseM4bMetadata(@NonNull Context context, @NonNull Uri fileUri) {
        Context appContext = context.getApplicationContext();
        MediaItem mediaItem = MediaItem.fromUri(fileUri);

        // NOT in try-with-resources: it would close before the async result arrives
        MetadataRetriever retriever = new MetadataRetriever.Builder(appContext, mediaItem).build();
        ListenableFuture<TrackGroupArray> future = retriever.retrieveTrackGroups();

        return Futures.transform(future, trackGroups -> {
            try {
                return extractMetadata(appContext, fileUri, trackGroups);
            } finally {
                retriever.close();
            }
        }, MoreExecutors.directExecutor());
    }

    @NonNull
    private M4bMetadata extractMetadata(
            @NonNull Context context,
            @NonNull Uri fileUri,
            @Nullable TrackGroupArray trackGroups
    ) {
        String title = "";
        String author = "";
        String narrator = "";
        long totalDurationMs = 0L;
        byte[] coverArt = null;
        List<M4bChapter> chapters = new ArrayList<>();

        if (trackGroups != null) {
            for (int i = 0; i < trackGroups.length; i++) {
                TrackGroup group = trackGroups.get(i);
                for (int k = 0; k < group.length; k++) {
                    Format format = group.getFormat(k);
                    Metadata metadata = format.metadata;
                    if (metadata == null) {
                        continue;
                    }
                    for (int j = 0; j < metadata.length(); j++) {
                        Metadata.Entry entry = metadata.get(j);
                        if (entry instanceof ChapterFrame) {
                            ChapterFrame cf = (ChapterFrame) entry;
                            String chTitle = extractChapterTitle(cf, chapters.size() + 1);
                            long startMs = Math.max(0L, cf.startTimeMs);
                            long endMs = Math.max(startMs, cf.endTimeMs);
                            chapters.add(new M4bChapter(
                                    chapters.size() + 1,
                                    chTitle,
                                    startMs,
                                    endMs - startMs
                            ));
                        } else if (entry instanceof TextInformationFrame) {
                            TextInformationFrame tif = (TextInformationFrame) entry;
                            String value = tif.values.isEmpty() ? "" : tif.values.get(0);
                            if ("TIT2".equals(tif.id) && title.isEmpty()) {
                                title = value;
                            } else if (("TPE1".equals(tif.id) || "TPE2".equals(tif.id)) && author.isEmpty()) {
                                author = value;
                            } else if ("TCOM".equals(tif.id) && narrator.isEmpty()) {
                                narrator = value;
                            }
                        } else if (entry instanceof MdtaMetadataEntry) {
                            MdtaMetadataEntry mdta = (MdtaMetadataEntry) entry;
                            String key = mdta.key.toLowerCase(Locale.ROOT);
                            if (mdta.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING) {
                                String val = new String(mdta.value, StandardCharsets.UTF_8).trim();
                                if (key.contains("title") && title.isEmpty()) {
                                    title = val;
                                } else if ((key.contains("artist") || key.contains("author")) && author.isEmpty()) {
                                    author = val;
                                }
                            }
                        } else if (entry instanceof ApicFrame && coverArt == null) {
                            coverArt = ((ApicFrame) entry).pictureData;
                        } else if (entry instanceof PictureFrame && coverArt == null) {
                            coverArt = ((PictureFrame) entry).pictureData;
                        }
                    }
                }
            }
        }

        // Supplement with Android's MediaMetadataRetriever for container duration, tags, and cover art.
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, fileUri);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                try {
                    totalDurationMs = Long.parseLong(durStr);
                } catch (NumberFormatException ignored) {
                }
            }
            if (title.isEmpty()) {
                String mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (mmrTitle == null || mmrTitle.trim().isEmpty()) {
                    mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                }
                if (mmrTitle != null) {
                    title = mmrTitle.trim();
                }
            }
            if (author.isEmpty()) {
                String mmrAuthor = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (mmrAuthor == null || mmrAuthor.trim().isEmpty()) {
                    mmrAuthor = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
                }
                if (mmrAuthor == null || mmrAuthor.trim().isEmpty()) {
                    mmrAuthor = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                }
                if (mmrAuthor != null) {
                    author = mmrAuthor.trim();
                }
            }
            if (coverArt == null) {
                coverArt = mmr.getEmbeddedPicture();
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaMetadataRetriever fallback failed for " + fileUri, e);
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }

        chapters.sort(Comparator.comparingLong(c -> c.startMs));
        List<M4bChapter> normalized = new ArrayList<>(chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            M4bChapter ch = chapters.get(i);
            long dur = ch.durationMs;
            if (dur <= 0L && i + 1 < chapters.size()) {
                dur = Math.max(0L, chapters.get(i + 1).startMs - ch.startMs);
            } else if (dur <= 0L && totalDurationMs > ch.startMs) {
                dur = totalDurationMs - ch.startMs;
            }
            normalized.add(new M4bChapter(i + 1, ch.title, ch.startMs, dur));
        }

        if (totalDurationMs <= 0L && !normalized.isEmpty()) {
            M4bChapter last = normalized.get(normalized.size() - 1);
            totalDurationMs = last.startMs + last.durationMs;
        }

        return new M4bMetadata(title, author, narrator, totalDurationMs, coverArt, normalized);
    }

    @NonNull
    private static String extractChapterTitle(@NonNull ChapterFrame cf, int fallbackIndex) {
        for (int i = 0; i < cf.getSubFrameCount(); i++) {
            Id3Frame sub = cf.getSubFrame(i);
            if (sub instanceof TextInformationFrame) {
                TextInformationFrame tif = (TextInformationFrame) sub;
                if ("TIT2".equals(tif.id) && !tif.values.isEmpty()) {
                    String text = tif.values.get(0);
                    if (!text.trim().isEmpty()) {
                        return text.trim();
                    }
                }
            }
        }
        return "Chapter " + fallbackIndex;
    }
}
