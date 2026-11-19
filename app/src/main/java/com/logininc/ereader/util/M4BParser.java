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
import androidx.media3.extractor.metadata.vorbis.VorbisComment;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses M4B/audiobook metadata and chapters using AndroidX Media3 {@link MetadataRetriever}
 * with {@link MediaMetadataRetriever} fallback for duration, tags, and embedded artwork.
 */
@UnstableApi
public class M4BParser {

    private static final String TAG = "M4BParser";
    private static final Pattern VORBIS_CHAPTER_TIME = Pattern.compile("^CHAPTER(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VORBIS_CHAPTER_NAME = Pattern.compile("^CHAPTER(\\d+)NAME$", Pattern.CASE_INSENSITIVE);

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

        ListenableFuture<TrackGroupArray> safeFuture = Futures.catching(
                future,
                Throwable.class,
                err -> {
                    Log.w(TAG, "MetadataRetriever failed for " + fileUri + ", falling back to native parsers", err);
                    return null;
                },
                MoreExecutors.directExecutor()
        );

        return Futures.transform(safeFuture, trackGroups -> {
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
        List<Mp4ChapterParser.ParsedChapter> id3Chapters = new ArrayList<>();
        Map<String, String> vorbisComments = new HashMap<>();

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
                            String chTitle = extractChapterTitle(cf, id3Chapters.size() + 1);
                            long startMs = cf.startTimeMs >= 0 ? cf.startTimeMs : 0L;
                            long endMs = cf.endTimeMs > startMs ? cf.endTimeMs : startMs;
                            id3Chapters.add(new Mp4ChapterParser.ParsedChapter(
                                    startMs,
                                    endMs - startMs,
                                    chTitle
                            ));
                        } else if (entry instanceof VorbisComment) {
                            VorbisComment vc = (VorbisComment) entry;
                            if (vc.key != null && vc.value != null) {
                                vorbisComments.put(vc.key.trim(), vc.value.trim());
                                String upperKey = vc.key.trim().toUpperCase(Locale.ROOT);
                                if ("TITLE".equals(upperKey) && title.isEmpty()) {
                                    title = vc.value.trim();
                                } else if ("ARTIST".equals(upperKey) && author.isEmpty()) {
                                    author = vc.value.trim();
                                } else if ("COMPOSER".equals(upperKey) && narrator.isEmpty()) {
                                    narrator = vc.value.trim();
                                }
                            }
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

        // Parse native MP4/M4B/M4A chapters (QuickTime chap text-track + Nero chpl atom)
        List<Mp4ChapterParser.ParsedChapter> mp4Chapters = Mp4ChapterParser.parseChapters(context, fileUri);
        // Parse OGG/Opus/FLAC Vorbis Comment chapters (CHAPTERxxx / CHAPTERxxxNAME)
        List<Mp4ChapterParser.ParsedChapter> vorbisChapters = parseVorbisChapters(vorbisComments);

        List<Mp4ChapterParser.ParsedChapter> bestRaw = Mp4ChapterParser.mergeBestChapters(mp4Chapters, id3Chapters);
        bestRaw = Mp4ChapterParser.mergeBestChapters(bestRaw, vorbisChapters);

        List<M4bChapter> chapters = new ArrayList<>(bestRaw.size());
        for (int i = 0; i < bestRaw.size(); i++) {
            Mp4ChapterParser.ParsedChapter ch = bestRaw.get(i);
            String cleanTitle = !ch.title.trim().isEmpty() ? ch.title.trim() : ("Chapter " + (i + 1));
            chapters.add(new M4bChapter(i + 1, cleanTitle, ch.startMs, ch.durationMs));
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
    public static List<Mp4ChapterParser.ParsedChapter> parseVorbisChapters(@NonNull Map<String, String> comments) {
        if (comments.isEmpty()) {
            return Collections.emptyList();
        }
        TreeMap<Integer, Long> startTimes = new TreeMap<>();
        Map<Integer, String> names = new HashMap<>();

        for (Map.Entry<String, String> entry : comments.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            Matcher timeMatcher = VORBIS_CHAPTER_TIME.matcher(key);
            if (timeMatcher.matches()) {
                try {
                    int num = Integer.parseInt(timeMatcher.group(1));
                    long ms = parseTimestampMs(value);
                    if (ms >= 0L) {
                        startTimes.put(num, ms);
                    }
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            Matcher nameMatcher = VORBIS_CHAPTER_NAME.matcher(key);
            if (nameMatcher.matches()) {
                try {
                    int num = Integer.parseInt(nameMatcher.group(1));
                    if (!value.trim().isEmpty()) {
                        names.put(num, value.trim());
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (startTimes.isEmpty()) {
            return Collections.emptyList();
        }
        List<Mp4ChapterParser.ParsedChapter> result = new ArrayList<>(startTimes.size());
        for (Map.Entry<Integer, Long> entry : startTimes.entrySet()) {
            String name = names.getOrDefault(entry.getKey(), "");
            result.add(new Mp4ChapterParser.ParsedChapter(entry.getValue(), 0L, name != null ? name : ""));
        }
        result.sort(Comparator.comparingLong(c -> c.startMs));
        return result;
    }

    /**
     * Parses Vorbis/Ogg chapter timestamp formats like {@code HH:MM:SS.mmm} or {@code MM:SS.mmm}.
     */
    public static long parseTimestampMs(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return -1L;
        }
        String[] parts = raw.trim().split(":");
        try {
            if (parts.length == 3) {
                long hours = Long.parseLong(parts[0]);
                long mins = Long.parseLong(parts[1]);
                double secs = Double.parseDouble(parts[2]);
                return Math.max(0L, hours * 3_600_000L + mins * 60_000L + Math.round(secs * 1000.0));
            }
            if (parts.length == 2) {
                long mins = Long.parseLong(parts[0]);
                double secs = Double.parseDouble(parts[1]);
                return Math.max(0L, mins * 60_000L + Math.round(secs * 1000.0));
            }
        } catch (NumberFormatException ignored) {
        }
        return -1L;
    }

    @NonNull
    private static String extractChapterTitle(@NonNull ChapterFrame cf, int fallbackIndex) {
        String fallbackText = null;
        for (int i = 0; i < cf.getSubFrameCount(); i++) {
            Id3Frame sub = cf.getSubFrame(i);
            if (sub instanceof TextInformationFrame) {
                TextInformationFrame tif = (TextInformationFrame) sub;
                if (!tif.values.isEmpty()) {
                    String text = tif.values.get(0).trim();
                    if (!text.isEmpty()) {
                        if ("TIT2".equals(tif.id)) {
                            return text;
                        }
                        if (fallbackText == null) {
                            fallbackText = text;
                        }
                    }
                }
            }
        }
        if (fallbackText != null) {
            return fallbackText;
        }
        return "Chapter " + fallbackIndex;
    }
}
