package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java, streaming MP4/M4B/M4A chapter parser using {@link FileChannel} (zero full-file RAM buffering).
 * Supports both:
 * <ul>
 *   <li>QuickTime chapter text tracks ({@code moov -> trak -> tref -> chap} + timed {@code text}/{@code tx3g} track)</li>
 *   <li>Nero chapter atoms ({@code moov -> udta -> chpl}, version 0 and version 1)</li>
 * </ul>
 * When both chapter tables exist in a file, intelligently selects and merges the richest chapter titles.
 */
public final class Mp4ChapterParser {

    private static final String TAG = "Mp4ChapterParser";
    private static final int MAX_CHAPTER_SAMPLE_BYTES = 16 * 1024;

    private Mp4ChapterParser() {
    }

    public static final class ParsedChapter {
        public final long startMs;
        public final long durationMs;
        @NonNull
        public final String title;

        public ParsedChapter(long startMs, long durationMs, @NonNull String title) {
            this.startMs = Math.max(0L, startMs);
            this.durationMs = Math.max(0L, durationMs);
            this.title = title;
        }
    }

    private static final class BoxHeader {
        final String type;
        final long startOffset;
        final long contentOffset;
        final long endOffset;

        BoxHeader(String type, long startOffset, long contentOffset, long endOffset) {
            this.type = type;
            this.startOffset = startOffset;
            this.contentOffset = contentOffset;
            this.endOffset = endOffset;
        }
    }

    private static final class SttsEntry {
        final long count;
        final long delta;

        SttsEntry(long count, long delta) {
            this.count = count;
            this.delta = delta;
        }
    }

    private static final class StscEntry {
        final long firstChunk;
        final long samplesPerChunk;

        StscEntry(long firstChunk, long samplesPerChunk) {
            this.firstChunk = firstChunk;
            this.samplesPerChunk = samplesPerChunk;
        }
    }

    private static final class TrackInfo {
        int trackId = -1;
        String handlerType = "";
        long timescale = 0L;
        final List<Integer> chapReferencedTrackIds = new ArrayList<>();
        final List<SttsEntry> stts = new ArrayList<>();
        final List<StscEntry> stsc = new ArrayList<>();
        final List<Long> chunkOffsets = new ArrayList<>();
        int uniformSampleSize = 0;
        int sampleCount = 0;
        final List<Integer> sampleSizes = new ArrayList<>();
    }

    @NonNull
    public static List<ParsedChapter> parseChapters(@NonNull Context context, @NonNull Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return Collections.emptyList();
            }
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel channel = fis.getChannel()) {
                return parseChapters(channel);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "MP4 chapter parsing failed for " + uri, e);
            return Collections.emptyList();
        }
    }

    @NonNull
    public static List<ParsedChapter> parseChapters(@NonNull FileChannel channel) throws IOException {
        long fileSize = channel.size();
        if (fileSize < 16) {
            return Collections.emptyList();
        }

        BoxHeader moov = findChildBox(channel, 0L, fileSize, "moov");
        if (moov == null) {
            return Collections.emptyList();
        }

        List<ParsedChapter> neroChapters = Collections.emptyList();
        List<TrackInfo> tracks = new ArrayList<>();

        long pos = moov.contentOffset;
        while (pos + 8 <= moov.endOffset) {
            BoxHeader child = readBoxHeader(channel, pos, moov.endOffset);
            if (child == null) {
                break;
            }
            if ("udta".equals(child.type)) {
                BoxHeader chpl = findChildBox(channel, child.contentOffset, child.endOffset, "chpl");
                if (chpl != null) {
                    neroChapters = parseNeroChpl(channel, chpl);
                }
            } else if ("trak".equals(child.type)) {
                TrackInfo info = parseTrack(channel, child);
                if (info != null) {
                    tracks.add(info);
                }
            }
            pos = child.endOffset;
        }

        List<ParsedChapter> quickTimeChapters = extractQuickTimeChapters(channel, tracks, fileSize);
        return mergeBestChapters(quickTimeChapters, neroChapters);
    }

    @NonNull
    public static List<ParsedChapter> mergeBestChapters(
            @NonNull List<ParsedChapter> quickTimeChapters,
            @NonNull List<ParsedChapter> neroChapters
    ) {
        if (quickTimeChapters.isEmpty() && neroChapters.isEmpty()) {
            return Collections.emptyList();
        }
        if (quickTimeChapters.isEmpty()) {
            return neroChapters;
        }
        if (neroChapters.isEmpty()) {
            return quickTimeChapters;
        }

        int qtRealTitles = countRealTitles(quickTimeChapters);
        int neroRealTitles = countRealTitles(neroChapters);

        List<ParsedChapter> primary;
        List<ParsedChapter> secondary;
        if (quickTimeChapters.size() > neroChapters.size()) {
            primary = quickTimeChapters;
            secondary = neroChapters;
        } else if (neroChapters.size() > quickTimeChapters.size()) {
            primary = neroChapters;
            secondary = quickTimeChapters;
        } else if (neroRealTitles > qtRealTitles) {
            primary = neroChapters;
            secondary = quickTimeChapters;
        } else {
            primary = quickTimeChapters;
            secondary = neroChapters;
        }

        List<ParsedChapter> merged = new ArrayList<>(primary.size());
        for (int i = 0; i < primary.size(); i++) {
            ParsedChapter p = primary.get(i);
            String title = p.title;
            if (isGenericOrEmptyTitle(title)) {
                String better = findMatchingTitle(secondary, p.startMs, i);
                if (better != null && !isGenericOrEmptyTitle(better)) {
                    title = better;
                }
            }
            merged.add(new ParsedChapter(p.startMs, p.durationMs, title));
        }
        return merged;
    }

    @Nullable
    private static String findMatchingTitle(
            @NonNull List<ParsedChapter> candidates,
            long targetStartMs,
            int preferredIndex
    ) {
        if (preferredIndex >= 0 && preferredIndex < candidates.size()) {
            ParsedChapter sameIdx = candidates.get(preferredIndex);
            if (Math.abs(sameIdx.startMs - targetStartMs) <= 2_000L && !sameIdx.title.isEmpty()) {
                return sameIdx.title;
            }
        }
        for (ParsedChapter c : candidates) {
            if (Math.abs(c.startMs - targetStartMs) <= 2_000L && !c.title.isEmpty()) {
                return c.title;
            }
        }
        return null;
    }

    private static int countRealTitles(@NonNull List<ParsedChapter> chapters) {
        int count = 0;
        for (ParsedChapter ch : chapters) {
            if (!isGenericOrEmptyTitle(ch.title)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isGenericOrEmptyTitle(@Nullable String title) {
        if (title == null) {
            return true;
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.matches("^(chapter|capitolo|track|traccia|part|parte)?\\s*\\d+$");
    }

    @NonNull
    private static List<ParsedChapter> parseNeroChpl(
            @NonNull FileChannel channel,
            @NonNull BoxHeader chpl
    ) throws IOException {
        long payloadSize = chpl.endOffset - chpl.contentOffset;
        if (payloadSize < 5 || payloadSize > 4 * 1024 * 1024) {
            return Collections.emptyList();
        }
        byte[] data = readBytes(channel, chpl.contentOffset, (int) payloadSize);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int version = buf.get() & 0xFF;
        buf.position(buf.position() + 3); // flags

        int entryCount;
        if (version == 1) {
            if (buf.remaining() < 5) {
                return Collections.emptyList();
            }
            int maybeReservedOrCount = buf.getInt();
            if (maybeReservedOrCount == 0) {
                entryCount = buf.get() & 0xFF;
            } else if (maybeReservedOrCount > 0 && maybeReservedOrCount <= 5000
                    && buf.remaining() >= maybeReservedOrCount * 9) {
                entryCount = maybeReservedOrCount;
            } else {
                entryCount = buf.get() & 0xFF;
            }
        } else {
            if (buf.remaining() < 1) {
                return Collections.emptyList();
            }
            entryCount = buf.get() & 0xFF;
        }

        List<ParsedChapter> chapters = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            if (buf.remaining() < 9) {
                break;
            }
            long start100ns = buf.getLong();
            int titleLen = buf.get() & 0xFF;
            if (buf.remaining() < titleLen) {
                titleLen = buf.remaining();
            }
            byte[] titleBytes = new byte[titleLen];
            buf.get(titleBytes);
            String title = sanitizeTitle(new String(titleBytes, StandardCharsets.UTF_8));
            long startMs = Math.max(0L, start100ns / 10_000L);
            chapters.add(new ParsedChapter(startMs, 0L, title));
        }

        chapters.sort(Comparator.comparingLong(c -> c.startMs));
        return chapters;
    }

    @Nullable
    private static TrackInfo parseTrack(
            @NonNull FileChannel channel,
            @NonNull BoxHeader trak
    ) throws IOException {
        TrackInfo info = new TrackInfo();
        long pos = trak.contentOffset;
        while (pos + 8 <= trak.endOffset) {
            BoxHeader child = readBoxHeader(channel, pos, trak.endOffset);
            if (child == null) {
                break;
            }
            if ("tkhd".equals(child.type)) {
                parseTkhd(channel, child, info);
            } else if ("tref".equals(child.type)) {
                parseTref(channel, child, info);
            } else if ("mdia".equals(child.type)) {
                parseMdia(channel, child, info);
            }
            pos = child.endOffset;
        }
        return info;
    }

    private static void parseTkhd(
            @NonNull FileChannel channel,
            @NonNull BoxHeader tkhd,
            @NonNull TrackInfo info
    ) throws IOException {
        long len = tkhd.endOffset - tkhd.contentOffset;
        if (len < 24) {
            return;
        }
        byte[] data = readBytes(channel, tkhd.contentOffset, (int) Math.min(len, 32));
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int version = buf.get() & 0xFF;
        int trackIdOffset = (version == 1) ? 20 : 12;
        if (data.length >= trackIdOffset + 4) {
            info.trackId = buf.getInt(trackIdOffset);
        }
    }

    private static void parseTref(
            @NonNull FileChannel channel,
            @NonNull BoxHeader tref,
            @NonNull TrackInfo info
    ) throws IOException {
        long pos = tref.contentOffset;
        while (pos + 8 <= tref.endOffset) {
            BoxHeader child = readBoxHeader(channel, pos, tref.endOffset);
            if (child == null) {
                break;
            }
            if ("chap".equals(child.type)) {
                long len = child.endOffset - child.contentOffset;
                if (len >= 4 && len <= 4096) {
                    byte[] data = readBytes(channel, child.contentOffset, (int) len);
                    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    while (buf.remaining() >= 4) {
                        info.chapReferencedTrackIds.add(buf.getInt());
                    }
                }
            }
            pos = child.endOffset;
        }
    }

    private static void parseMdia(
            @NonNull FileChannel channel,
            @NonNull BoxHeader mdia,
            @NonNull TrackInfo info
    ) throws IOException {
        long pos = mdia.contentOffset;
        while (pos + 8 <= mdia.endOffset) {
            BoxHeader child = readBoxHeader(channel, pos, mdia.endOffset);
            if (child == null) {
                break;
            }
            if ("mdhd".equals(child.type)) {
                parseMdhd(channel, child, info);
            } else if ("hdlr".equals(child.type)) {
                parseHdlr(channel, child, info);
            } else if ("minf".equals(child.type)) {
                BoxHeader stbl = findChildBox(channel, child.contentOffset, child.endOffset, "stbl");
                if (stbl != null) {
                    parseStbl(channel, stbl, info);
                }
            }
            pos = child.endOffset;
        }
    }

    private static void parseMdhd(
            @NonNull FileChannel channel,
            @NonNull BoxHeader mdhd,
            @NonNull TrackInfo info
    ) throws IOException {
        long len = mdhd.endOffset - mdhd.contentOffset;
        if (len < 20) {
            return;
        }
        byte[] data = readBytes(channel, mdhd.contentOffset, (int) Math.min(len, 32));
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int version = buf.get() & 0xFF;
        int timescaleOffset = (version == 1) ? 20 : 12;
        if (data.length >= timescaleOffset + 4) {
            info.timescale = buf.getInt(timescaleOffset) & 0xFFFFFFFFL;
        }
    }

    private static void parseHdlr(
            @NonNull FileChannel channel,
            @NonNull BoxHeader hdlr,
            @NonNull TrackInfo info
    ) throws IOException {
        long len = hdlr.endOffset - hdlr.contentOffset;
        if (len < 12) {
            return;
        }
        byte[] data = readBytes(channel, hdlr.contentOffset, 12);
        info.handlerType = new String(data, 8, 4, StandardCharsets.US_ASCII);
    }

    private static void parseStbl(
            @NonNull FileChannel channel,
            @NonNull BoxHeader stbl,
            @NonNull TrackInfo info
    ) throws IOException {
        long pos = stbl.contentOffset;
        while (pos + 8 <= stbl.endOffset) {
            BoxHeader child = readBoxHeader(channel, pos, stbl.endOffset);
            if (child == null) {
                break;
            }
            long payloadLen = child.endOffset - child.contentOffset;
            if (payloadLen <= 0 || payloadLen > 8 * 1024 * 1024) {
                pos = child.endOffset;
                continue;
            }
            if ("stts".equals(child.type)) {
                byte[] data = readBytes(channel, child.contentOffset, (int) payloadLen);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                if (buf.remaining() >= 8) {
                    buf.position(4);
                    int count = buf.getInt();
                    for (int i = 0; i < count && buf.remaining() >= 8; i++) {
                        long sCount = buf.getInt() & 0xFFFFFFFFL;
                        long sDelta = buf.getInt() & 0xFFFFFFFFL;
                        info.stts.add(new SttsEntry(sCount, sDelta));
                    }
                }
            } else if ("stsc".equals(child.type)) {
                byte[] data = readBytes(channel, child.contentOffset, (int) payloadLen);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                if (buf.remaining() >= 8) {
                    buf.position(4);
                    int count = buf.getInt();
                    for (int i = 0; i < count && buf.remaining() >= 12; i++) {
                        long firstChunk = buf.getInt() & 0xFFFFFFFFL;
                        long samplesPerChunk = buf.getInt() & 0xFFFFFFFFL;
                        buf.getInt(); // sampleDescriptionIndex
                        info.stsc.add(new StscEntry(firstChunk, samplesPerChunk));
                    }
                }
            } else if ("stsz".equals(child.type)) {
                byte[] data = readBytes(channel, child.contentOffset, (int) payloadLen);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                if (buf.remaining() >= 12) {
                    buf.position(4);
                    info.uniformSampleSize = buf.getInt();
                    info.sampleCount = buf.getInt();
                    if (info.uniformSampleSize == 0) {
                        for (int i = 0; i < info.sampleCount && buf.remaining() >= 4; i++) {
                            info.sampleSizes.add(buf.getInt());
                        }
                    }
                }
            } else if ("stco".equals(child.type)) {
                byte[] data = readBytes(channel, child.contentOffset, (int) payloadLen);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                if (buf.remaining() >= 8) {
                    buf.position(4);
                    int count = buf.getInt();
                    for (int i = 0; i < count && buf.remaining() >= 4; i++) {
                        info.chunkOffsets.add(buf.getInt() & 0xFFFFFFFFL);
                    }
                }
            } else if ("co64".equals(child.type)) {
                byte[] data = readBytes(channel, child.contentOffset, (int) payloadLen);
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                if (buf.remaining() >= 8) {
                    buf.position(4);
                    int count = buf.getInt();
                    for (int i = 0; i < count && buf.remaining() >= 8; i++) {
                        info.chunkOffsets.add(buf.getLong());
                    }
                }
            }
            pos = child.endOffset;
        }
    }

    @NonNull
    private static List<ParsedChapter> extractQuickTimeChapters(
            @NonNull FileChannel channel,
            @NonNull List<TrackInfo> tracks,
            long fileSize
    ) throws IOException {
        Set<Integer> chapIds = new HashSet<>();
        Map<Integer, TrackInfo> byId = new HashMap<>();
        for (TrackInfo t : tracks) {
            if (t.trackId > 0) {
                byId.put(t.trackId, t);
            }
            chapIds.addAll(t.chapReferencedTrackIds);
        }

        TrackInfo textTrack = null;
        for (Integer id : chapIds) {
            TrackInfo candidate = byId.get(id);
            if (candidate != null && isTextHandler(candidate.handlerType) && candidate.sampleCount > 0) {
                textTrack = candidate;
                break;
            }
        }
        if (textTrack == null) {
            for (TrackInfo t : tracks) {
                if (isTextHandler(t.handlerType) && t.sampleCount > 0 && !t.chunkOffsets.isEmpty()) {
                    textTrack = t;
                    break;
                }
            }
        }
        if (textTrack == null || textTrack.timescale <= 0 || textTrack.sampleCount <= 0
                || textTrack.chunkOffsets.isEmpty() || textTrack.stsc.isEmpty()) {
            return Collections.emptyList();
        }

        // Compute per-sample timestamps & durations from stts
        List<Long> sampleStartMs = new ArrayList<>(textTrack.sampleCount);
        List<Long> sampleDurMs = new ArrayList<>(textTrack.sampleCount);
        long cumulativeTicks = 0L;
        for (SttsEntry entry : textTrack.stts) {
            for (long k = 0; k < entry.count && sampleStartMs.size() < textTrack.sampleCount; k++) {
                long startMs = (cumulativeTicks * 1000L) / textTrack.timescale;
                long durMs = (entry.delta * 1000L) / textTrack.timescale;
                sampleStartMs.add(startMs);
                sampleDurMs.add(durMs);
                cumulativeTicks += entry.delta;
            }
        }

        // Compute file byte offset & size for each sample from stsc + stco/co64 + stsz
        List<ParsedChapter> chapters = new ArrayList<>(textTrack.sampleCount);
        int sampleIdx = 0;
        int totalChunks = textTrack.chunkOffsets.size();
        for (int chunkZero = 0; chunkZero < totalChunks && sampleIdx < textTrack.sampleCount; chunkZero++) {
            long chunkOneBased = chunkZero + 1L;
            long samplesInChunk = findSamplesPerChunk(textTrack.stsc, chunkOneBased);
            long offsetInFile = textTrack.chunkOffsets.get(chunkZero);

            for (long s = 0; s < samplesInChunk && sampleIdx < textTrack.sampleCount; s++) {
                int sampleSize = textTrack.uniformSampleSize > 0
                        ? textTrack.uniformSampleSize
                        : (sampleIdx < textTrack.sampleSizes.size() ? textTrack.sampleSizes.get(sampleIdx) : 0);

                String title = "";
                if (sampleSize >= 2 && sampleSize <= MAX_CHAPTER_SAMPLE_BYTES
                        && offsetInFile >= 0 && offsetInFile + sampleSize <= fileSize) {
                    byte[] sampleBytes = readBytes(channel, offsetInFile, sampleSize);
                    title = decodeQuickTimeTextSample(sampleBytes);
                }

                long startMs = sampleIdx < sampleStartMs.size() ? sampleStartMs.get(sampleIdx) : 0L;
                long durMs = sampleIdx < sampleDurMs.size() ? sampleDurMs.get(sampleIdx) : 0L;
                chapters.add(new ParsedChapter(startMs, durMs, title));

                offsetInFile += Math.max(0, sampleSize);
                sampleIdx++;
            }
        }
        return chapters;
    }

    private static boolean isTextHandler(@Nullable String handler) {
        return "text".equals(handler) || "sbtl".equals(handler) || "tx3g".equals(handler);
    }

    private static long findSamplesPerChunk(@NonNull List<StscEntry> stsc, long chunkOneBased) {
        long result = 1L;
        for (StscEntry entry : stsc) {
            if (entry.firstChunk <= chunkOneBased) {
                result = entry.samplesPerChunk;
            } else {
                break;
            }
        }
        return Math.max(1L, result);
    }

    /**
     * Decodes a QuickTime {@code text}/{@code tx3g} sample payload:
     * 2-byte big-endian text length followed by UTF-8 or UTF-16 (BOM) text.
     */
    @NonNull
    public static String decodeQuickTimeTextSample(@NonNull byte[] sampleBytes) {
        if (sampleBytes.length < 2) {
            return "";
        }
        int textLen = ((sampleBytes[0] & 0xFF) << 8) | (sampleBytes[1] & 0xFF);
        int available = sampleBytes.length - 2;
        if (textLen <= 0 || textLen > available) {
            return "";
        }
        String decoded;
        if (textLen >= 2 && (sampleBytes[2] & 0xFF) == 0xFE && (sampleBytes[3] & 0xFF) == 0xFF) {
            decoded = new String(sampleBytes, 4, textLen - 2, StandardCharsets.UTF_16BE);
        } else if (textLen >= 2 && (sampleBytes[2] & 0xFF) == 0xFF && (sampleBytes[3] & 0xFF) == 0xFE) {
            decoded = new String(sampleBytes, 4, textLen - 2, StandardCharsets.UTF_16LE);
        } else {
            decoded = new String(sampleBytes, 2, textLen, StandardCharsets.UTF_8);
        }
        return sanitizeTitle(decoded);
    }

    @NonNull
    private static String sanitizeTitle(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\u0000", "").replace('\r', ' ').replace('\n', ' ').trim();
    }

    @Nullable
    private static BoxHeader findChildBox(
            @NonNull FileChannel channel,
            long start,
            long end,
            @NonNull String targetType
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(channel, pos, end);
            if (box == null) {
                return null;
            }
            if (targetType.equals(box.type)) {
                return box;
            }
            pos = box.endOffset;
        }
        return null;
    }

    @Nullable
    private static BoxHeader readBoxHeader(
            @NonNull FileChannel channel,
            long offset,
            long parentEnd
    ) throws IOException {
        if (offset + 8 > parentEnd) {
            return null;
        }
        byte[] hdr = readBytes(channel, offset, 8);
        ByteBuffer buf = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN);
        long size = buf.getInt() & 0xFFFFFFFFL;
        String type = new String(hdr, 4, 4, StandardCharsets.US_ASCII);
        long contentOffset = offset + 8;

        if (size == 1L) {
            if (offset + 16 > parentEnd) {
                return null;
            }
            byte[] ext = readBytes(channel, offset + 8, 8);
            size = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).getLong();
            contentOffset = offset + 16;
        } else if (size == 0L) {
            size = parentEnd - offset;
        }

        if (size < (contentOffset - offset)) {
            return null;
        }
        long endOffset = Math.min(parentEnd, offset + size);
        return new BoxHeader(type, offset, contentOffset, endOffset);
    }

    @NonNull
    private static byte[] readBytes(@NonNull FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        long pos = offset;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, pos);
            if (read <= 0) {
                break;
            }
            pos += read;
        }
        return buf.array();
    }
}
