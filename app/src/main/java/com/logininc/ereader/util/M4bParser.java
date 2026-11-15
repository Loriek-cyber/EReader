package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java ISO BMFF (MP4 / M4B) parser for extracting audiobook metadata, embedded artwork,
 * total duration, Nero chapters ({@code chpl}), and QuickTime chapter tracks ({@code tref/chap}).
 * Skips bulk media payload boxes ({@code mdat}) via seekable reads so large M4B files parse rapidly.
 */
public final class M4bParser {

    public interface DataSource {
        long size() throws IOException;

        int readAt(long position, @NonNull byte[] buffer, int offset, int length) throws IOException;
    }

    public static final class ByteArrayDataSource implements DataSource {
        private final byte[] data;

        public ByteArrayDataSource(@NonNull byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int readAt(long position, @NonNull byte[] buffer, int offset, int length) {
            if (position < 0 || position >= data.length || length <= 0) {
                return 0;
            }
            int avail = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, avail);
            return avail;
        }
    }

    public static final class FileChannelDataSource implements DataSource {
        private final FileChannel channel;
        private final long fileSize;

        public FileChannelDataSource(@NonNull FileChannel channel) throws IOException {
            this.channel = channel;
            this.fileSize = channel.size();
        }

        @Override
        public long size() {
            return fileSize;
        }

        @Override
        public int readAt(long position, @NonNull byte[] buffer, int offset, int length) throws IOException {
            if (position < 0 || position >= fileSize || length <= 0) {
                return 0;
            }
            ByteBuffer bb = ByteBuffer.wrap(buffer, offset, length);
            int total = 0;
            while (total < length && position + total < fileSize) {
                int read = channel.read(bb, position + total);
                if (read <= 0) {
                    break;
                }
                total += read;
            }
            return total;
        }
    }

    public static final class M4bChapter {
        @NonNull
        public final String title;
        public final long startMs;
        public final long durationMs;

        public M4bChapter(@NonNull String title, long startMs, long durationMs) {
            this.title = title;
            this.startMs = Math.max(0L, startMs);
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    public static final class M4bMetadata {
        @NonNull
        public String title = "";
        @NonNull
        public String album = "";
        @NonNull
        public String author = "";
        @NonNull
        public String narrator = "";
        public long durationMs = 0L;
        @Nullable
        public byte[] coverBytes = null;
        @NonNull
        public final List<M4bChapter> chapters = new ArrayList<>();
    }

    private static final int BOX_MOOV = 0x6D6F6F76; // moov
    private static final int BOX_MVHD = 0x6D766864; // mvhd
    private static final int BOX_UDTA = 0x75647461; // udta
    private static final int BOX_META = 0x6D657461; // meta
    private static final int BOX_ILST = 0x696C7374; // ilst
    private static final int BOX_HDLR = 0x68646C72; // hdlr
    private static final int BOX_CHPL = 0x6368706C; // chpl
    private static final int BOX_DATA = 0x64617461; // data
    private static final int BOX_TRAK = 0x7472616B; // trak
    private static final int BOX_TKHD = 0x746B6864; // tkhd
    private static final int BOX_TREF = 0x74726566; // tref
    private static final int BOX_CHAP = 0x63686170; // chap
    private static final int BOX_MDIA = 0x6D646961; // mdia
    private static final int BOX_MDHD = 0x6D646864; // mdhd
    private static final int BOX_MINF = 0x6D696E66; // minf
    private static final int BOX_STBL = 0x7374626C; // stbl
    private static final int BOX_STTS = 0x73747473; // stts
    private static final int BOX_STSC = 0x73747363; // stsc
    private static final int BOX_STSZ = 0x7374737A; // stsz
    private static final int BOX_STCO = 0x7374636F; // stco
    private static final int BOX_CO64 = 0x636F3634; // co64

    private static final int ATOM_NAM = 0xA96E616D; // ©nam
    private static final int ATOM_ALB = 0xA9616C62; // ©alb
    private static final int ATOM_ART = 0xA9415254; // ©ART
    private static final int ATOM_AART = 0x61415254; // aART
    private static final int ATOM_WRT = 0xA9777274; // ©wrt
    private static final int ATOM_NRT = 0xA96E7274; // ©nrt
    private static final int ATOM_COVR = 0x636F7672; // covr

    private static final int HDLR_TEXT = 0x74657874; // text
    private static final int HDLR_SBTL = 0x7362746C; // sbtl

    private M4bParser() {
    }

    public static boolean isM4bOrAudioMime(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("audio/")
                || "video/mp4".equals(lower)
                || "application/mp4".equals(lower);
    }

    @NonNull
    public static M4bMetadata parse(@NonNull Context context, @NonNull Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                    return parse(new FileChannelDataSource(fis.getChannel()));
                }
            }
        } catch (IOException | SecurityException | IllegalArgumentException ignored) {
        }
        return new M4bMetadata();
    }

    @NonNull
    public static M4bMetadata parse(@NonNull DataSource source) throws IOException {
        M4bMetadata result = new M4bMetadata();
        long fileSize = source.size();
        if (fileSize <= 8) {
            return result;
        }

        List<TrakInfo> traks = new ArrayList<>();
        List<Integer> chapRefTrackIds = new ArrayList<>();
        List<M4bChapter> neroChapters = new ArrayList<>();

        long pos = 0L;
        while (pos + 8 <= fileSize) {
            BoxHeader box = readBoxHeader(source, pos, fileSize);
            if (box == null) {
                break;
            }
            if (box.type == BOX_MOOV) {
                parseMoov(source, box.payloadStart, box.end, result, traks, chapRefTrackIds, neroChapters);
                break;
            }
            pos = box.end;
        }

        List<M4bChapter> qtChapters = resolveQuickTimeChapters(source, traks, chapRefTrackIds, result.durationMs);
        List<M4bChapter> chosen = qtChapters.size() > neroChapters.size() ? qtChapters : neroChapters;
        if (chosen.isEmpty() && !qtChapters.isEmpty()) {
            chosen = qtChapters;
        }
        finalizeChapterDurations(chosen, result.durationMs);
        result.chapters.addAll(chosen);
        return result;
    }

    private static void parseMoov(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull M4bMetadata metadata,
            @NonNull List<TrakInfo> traks,
            @NonNull List<Integer> chapRefTrackIds,
            @NonNull List<M4bChapter> neroChapters
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_MVHD) {
                metadata.durationMs = parseMvhdDurationMs(source, box.payloadStart, box.end);
            } else if (box.type == BOX_UDTA) {
                parseUdta(source, box.payloadStart, box.end, metadata, neroChapters);
            } else if (box.type == BOX_META) {
                parseMeta(source, box.payloadStart, box.end, metadata);
            } else if (box.type == BOX_TRAK) {
                TrakInfo trak = parseTrak(source, box.payloadStart, box.end);
                traks.add(trak);
                chapRefTrackIds.addAll(trak.chapTrackRefs);
            }
            pos = box.end;
        }
    }

    private static long parseMvhdDurationMs(@NonNull DataSource source, long start, long end) throws IOException {
        if (start + 4 > end) {
            return 0L;
        }
        int version = readU8(source, start);
        long timescale = 0L;
        long duration = 0L;
        if (version == 0 && start + 20 <= end) {
            timescale = readU32(source, start + 12);
            duration = readU32(source, start + 16);
        } else if (version == 1 && start + 32 <= end) {
            timescale = readU32(source, start + 20);
            duration = readI64(source, start + 24);
        }
        if (timescale <= 0 || duration <= 0) {
            return 0L;
        }
        return (duration * 1000L) / timescale;
    }

    private static void parseUdta(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull M4bMetadata metadata,
            @NonNull List<M4bChapter> neroChapters
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_META) {
                parseMeta(source, box.payloadStart, box.end, metadata);
            } else if (box.type == BOX_CHPL) {
                parseChpl(source, box.payloadStart, box.end, neroChapters);
            }
            pos = box.end;
        }
    }

    private static void parseMeta(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull M4bMetadata metadata
    ) throws IOException {
        long childStart = start;
        if (start + 12 <= end) {
            int peekTypeNoFlags = readI32(source, start + 4);
            int peekTypeWithFlags = readI32(source, start + 8);
            if (peekTypeWithFlags == BOX_HDLR || peekTypeWithFlags == BOX_ILST) {
                childStart = start + 4;
            } else if (peekTypeNoFlags != BOX_HDLR && peekTypeNoFlags != BOX_ILST) {
                childStart = start + 4;
            }
        }
        long pos = childStart;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_ILST) {
                parseIlst(source, box.payloadStart, box.end, metadata);
            }
            pos = box.end;
        }
    }

    private static void parseIlst(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull M4bMetadata metadata
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader itemBox = readBoxHeader(source, pos, end);
            if (itemBox == null) {
                break;
            }
            long innerPos = itemBox.payloadStart;
            while (innerPos + 8 <= itemBox.end) {
                BoxHeader dataBox = readBoxHeader(source, innerPos, itemBox.end);
                if (dataBox == null) {
                    break;
                }
                if (dataBox.type == BOX_DATA && dataBox.payloadStart + 8 <= dataBox.end) {
                    int dataType = readI32(source, dataBox.payloadStart);
                    long valueStart = dataBox.payloadStart + 8;
                    int valueLen = (int) Math.min(2_000_000L, dataBox.end - valueStart);
                    if (valueLen > 0) {
                        byte[] raw = readBytes(source, valueStart, valueLen);
                        if (itemBox.type == ATOM_COVR && (dataType == 13 || dataType == 14)) {
                            if (metadata.coverBytes == null) {
                                metadata.coverBytes = raw;
                            }
                        } else if (dataType == 1 || dataType == 2) {
                            String text = new String(
                                    raw,
                                    dataType == 2 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8
                            ).trim();
                            if (!text.isEmpty()) {
                                applyIlstTextAtom(itemBox.type, text, metadata);
                            }
                        }
                    }
                }
                innerPos = dataBox.end;
            }
            pos = itemBox.end;
        }
    }

    private static void applyIlstTextAtom(int atomType, @NonNull String text, @NonNull M4bMetadata metadata) {
        if (atomType == ATOM_NAM && metadata.title.isEmpty()) {
            metadata.title = text;
        } else if (atomType == ATOM_ALB && metadata.album.isEmpty()) {
            metadata.album = text;
        } else if (atomType == ATOM_ART || atomType == ATOM_AART) {
            if (metadata.author.isEmpty() || atomType == ATOM_ART) {
                metadata.author = text;
            }
        } else if (atomType == ATOM_NRT || atomType == ATOM_WRT) {
            if (metadata.narrator.isEmpty() || atomType == ATOM_NRT) {
                metadata.narrator = text;
            }
        }
    }

    private static void parseChpl(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull List<M4bChapter> out
    ) throws IOException {
        if (start + 5 > end) {
            return;
        }
        int version = readU8(source, start);
        long pos = start + 4; // version (1) + flags (3)
        if (version >= 1) {
            if (pos + 5 > end) {
                return;
            }
            pos += 4; // reserved uint32 in version 1 chpl
        }
        int count = readU8(source, pos);
        pos += 1;
        for (int i = 0; i < count && pos + 9 <= end; i++) {
            long start100ns = readI64(source, pos);
            int titleLen = readU8(source, pos + 8);
            pos += 9;
            if (pos + titleLen > end) {
                break;
            }
            byte[] titleBytes = readBytes(source, pos, titleLen);
            pos += titleLen;
            String title = new String(titleBytes, StandardCharsets.UTF_8).trim();
            if (title.isEmpty()) {
                title = "Capitolo " + (out.size() + 1);
            }
            long startMs = Math.max(0L, start100ns / 10_000L);
            out.add(new M4bChapter(title, startMs, 0L));
        }
    }

    @NonNull
    private static TrakInfo parseTrak(@NonNull DataSource source, long start, long end) throws IOException {
        TrakInfo info = new TrakInfo();
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_TKHD) {
                info.trackId = parseTkhdTrackId(source, box.payloadStart, box.end);
            } else if (box.type == BOX_TREF) {
                parseTref(source, box.payloadStart, box.end, info.chapTrackRefs);
            } else if (box.type == BOX_MDIA) {
                parseMdia(source, box.payloadStart, box.end, info);
            }
            pos = box.end;
        }
        return info;
    }

    private static int parseTkhdTrackId(@NonNull DataSource source, long start, long end) throws IOException {
        if (start + 24 > end) {
            return -1;
        }
        int version = readU8(source, start);
        long idOffset = (version == 1) ? start + 20 : start + 12;
        if (idOffset + 4 > end) {
            return -1;
        }
        return readI32(source, idOffset);
    }

    private static void parseTref(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull List<Integer> chapTrackRefs
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_CHAP) {
                for (long p = box.payloadStart; p + 4 <= box.end; p += 4) {
                    chapTrackRefs.add(readI32(source, p));
                }
            }
            pos = box.end;
        }
    }

    private static void parseMdia(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull TrakInfo info
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_MDHD) {
                info.timescale = parseMdhdTimescale(source, box.payloadStart, box.end);
            } else if (box.type == BOX_HDLR) {
                if (box.payloadStart + 12 <= box.end) {
                    info.handlerType = readI32(source, box.payloadStart + 8);
                }
            } else if (box.type == BOX_MINF) {
                parseMinf(source, box.payloadStart, box.end, info);
            }
            pos = box.end;
        }
    }

    private static long parseMdhdTimescale(@NonNull DataSource source, long start, long end) throws IOException {
        if (start + 20 > end) {
            return 1000L;
        }
        int version = readU8(source, start);
        long offset = (version == 1) ? start + 20 : start + 12;
        if (offset + 4 > end) {
            return 1000L;
        }
        long ts = readU32(source, offset);
        return ts > 0 ? ts : 1000L;
    }

    private static void parseMinf(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull TrakInfo info
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_STBL) {
                parseStbl(source, box.payloadStart, box.end, info);
            }
            pos = box.end;
        }
    }

    private static void parseStbl(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull TrakInfo info
    ) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            BoxHeader box = readBoxHeader(source, pos, end);
            if (box == null) {
                break;
            }
            if (box.type == BOX_STTS) {
                parseStts(source, box.payloadStart, box.end, info);
            } else if (box.type == BOX_STSC) {
                parseStsc(source, box.payloadStart, box.end, info);
            } else if (box.type == BOX_STSZ) {
                parseStsz(source, box.payloadStart, box.end, info);
            } else if (box.type == BOX_STCO) {
                parseStco(source, box.payloadStart, box.end, info, false);
            } else if (box.type == BOX_CO64) {
                parseStco(source, box.payloadStart, box.end, info, true);
            }
            pos = box.end;
        }
    }

    private static void parseStts(@NonNull DataSource source, long start, long end, @NonNull TrakInfo info) throws IOException {
        if (start + 8 > end) {
            return;
        }
        int entryCount = (int) Math.min(10_000L, readU32(source, start + 4));
        long p = start + 8;
        for (int i = 0; i < entryCount && p + 8 <= end; i++, p += 8) {
            int count = (int) Math.min(10_000L, readU32(source, p));
            long delta = readU32(source, p + 4);
            for (int c = 0; c < count; c++) {
                info.sampleDeltas.add(delta);
            }
        }
    }

    private static void parseStsc(@NonNull DataSource source, long start, long end, @NonNull TrakInfo info) throws IOException {
        if (start + 8 > end) {
            return;
        }
        int entryCount = (int) Math.min(10_000L, readU32(source, start + 4));
        long p = start + 8;
        for (int i = 0; i < entryCount && p + 12 <= end; i++, p += 12) {
            int firstChunk = (int) readU32(source, p);
            int samplesPerChunk = (int) readU32(source, p + 4);
            info.stscEntries.add(new StscEntry(firstChunk, samplesPerChunk));
        }
    }

    private static void parseStsz(@NonNull DataSource source, long start, long end, @NonNull TrakInfo info) throws IOException {
        if (start + 12 > end) {
            return;
        }
        int uniformSize = (int) readU32(source, start + 4);
        int sampleCount = (int) Math.min(10_000L, readU32(source, start + 8));
        if (uniformSize > 0) {
            info.sampleSizes = new int[sampleCount];
            Arrays.fill(info.sampleSizes, uniformSize);
        } else {
            info.sampleSizes = new int[sampleCount];
            long p = start + 12;
            for (int i = 0; i < sampleCount && p + 4 <= end; i++, p += 4) {
                info.sampleSizes[i] = (int) readU32(source, p);
            }
        }
    }

    private static void parseStco(
            @NonNull DataSource source,
            long start,
            long end,
            @NonNull TrakInfo info,
            boolean is64Bit
    ) throws IOException {
        if (start + 8 > end) {
            return;
        }
        int count = (int) Math.min(10_000L, readU32(source, start + 4));
        info.chunkOffsets = new long[count];
        long p = start + 8;
        int stride = is64Bit ? 8 : 4;
        for (int i = 0; i < count && p + stride <= end; i++, p += stride) {
            info.chunkOffsets[i] = is64Bit ? readI64(source, p) : readU32(source, p);
        }
    }

    @NonNull
    private static List<M4bChapter> resolveQuickTimeChapters(
            @NonNull DataSource source,
            @NonNull List<TrakInfo> traks,
            @NonNull List<Integer> chapRefTrackIds,
            long totalDurationMs
    ) throws IOException {
        List<TrakInfo> candidates = new ArrayList<>();
        for (TrakInfo t : traks) {
            if (chapRefTrackIds.contains(t.trackId)) {
                candidates.add(t);
            }
        }
        for (TrakInfo t : traks) {
            if ((t.handlerType == HDLR_TEXT || t.handlerType == HDLR_SBTL) && !candidates.contains(t)) {
                candidates.add(t);
            }
        }
        for (TrakInfo candidate : candidates) {
            List<M4bChapter> parsed = extractChaptersFromTrack(source, candidate, totalDurationMs);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return new ArrayList<>();
    }

    @NonNull
    private static List<M4bChapter> extractChaptersFromTrack(
            @NonNull DataSource source,
            @NonNull TrakInfo trak,
            long totalDurationMs
    ) throws IOException {
        List<M4bChapter> out = new ArrayList<>();
        if (trak.sampleSizes.length == 0 || trak.chunkOffsets.length == 0) {
            return out;
        }
        long[] sampleOffsets = computeSampleOffsets(trak);
        long fileSize = source.size();
        long currentTick = 0L;
        long timescale = Math.max(1L, trak.timescale);

        for (int i = 0; i < trak.sampleSizes.length; i++) {
            int size = trak.sampleSizes[i];
            long offset = sampleOffsets[i];
            long deltaTicks = i < trak.sampleDeltas.size() ? trak.sampleDeltas.get(i) : 0L;
            long startMs = (currentTick * 1000L) / timescale;
            long durMs = (deltaTicks * 1000L) / timescale;
            currentTick += deltaTicks;

            String title = "";
            if (size >= 2 && offset >= 0 && offset + size <= fileSize) {
                byte[] sampleBytes = readBytes(source, offset, Math.min(size, 4096));
                if (sampleBytes.length >= 2) {
                    int strLen = ((sampleBytes[0] & 0xFF) << 8) | (sampleBytes[1] & 0xFF);
                    if (strLen > 0 && 2 + strLen <= sampleBytes.length) {
                        if (strLen >= 2 && (sampleBytes[2] & 0xFF) == 0xFE && (sampleBytes[3] & 0xFF) == 0xFF) {
                            title = new String(sampleBytes, 4, strLen - 2, StandardCharsets.UTF_16BE).trim();
                        } else {
                            title = new String(sampleBytes, 2, strLen, StandardCharsets.UTF_8)
                                    .replace("\uFEFF", "")
                                    .trim();
                        }
                    }
                }
            }
            if (title.isEmpty()) {
                title = "Capitolo " + (i + 1);
            }
            out.add(new M4bChapter(title, startMs, durMs));
        }
        finalizeChapterDurations(out, totalDurationMs);
        return out;
    }

    @NonNull
    private static long[] computeSampleOffsets(@NonNull TrakInfo trak) {
        int sampleCount = trak.sampleSizes.length;
        long[] offsets = new long[sampleCount];
        int sampleIdx = 0;
        for (int chunkIdx = 0; chunkIdx < trak.chunkOffsets.length && sampleIdx < sampleCount; chunkIdx++) {
            int chunkNumberOneBased = chunkIdx + 1;
            int samplesInChunk = findSamplesPerChunk(trak.stscEntries, chunkNumberOneBased);
            long cursor = trak.chunkOffsets[chunkIdx];
            for (int k = 0; k < samplesInChunk && sampleIdx < sampleCount; k++) {
                offsets[sampleIdx] = cursor;
                cursor += trak.sampleSizes[sampleIdx];
                sampleIdx++;
            }
        }
        return offsets;
    }

    private static int findSamplesPerChunk(@NonNull List<StscEntry> entries, int chunkOneBased) {
        int result = 1;
        for (StscEntry entry : entries) {
            if (chunkOneBased >= entry.firstChunk) {
                result = entry.samplesPerChunk;
            } else {
                break;
            }
        }
        return Math.max(1, result);
    }

    private static void finalizeChapterDurations(@NonNull List<M4bChapter> chapters, long totalDurationMs) {
        for (int i = 0; i < chapters.size(); i++) {
            M4bChapter ch = chapters.get(i);
            long dur = ch.durationMs;
            if (dur <= 0L) {
                if (i + 1 < chapters.size()) {
                    dur = Math.max(0L, chapters.get(i + 1).startMs - ch.startMs);
                } else if (totalDurationMs > ch.startMs) {
                    dur = totalDurationMs - ch.startMs;
                }
                chapters.set(i, new M4bChapter(ch.title, ch.startMs, dur));
            }
        }
    }

    @Nullable
    private static BoxHeader readBoxHeader(@NonNull DataSource source, long pos, long limit) throws IOException {
        if (pos + 8 > limit) {
            return null;
        }
        byte[] hdr = readBytes(source, pos, 8);
        long size = ((hdr[0] & 0xFFL) << 24)
                | ((hdr[1] & 0xFFL) << 16)
                | ((hdr[2] & 0xFFL) << 8)
                | (hdr[3] & 0xFFL);
        int type = ((hdr[4] & 0xFF) << 24)
                | ((hdr[5] & 0xFF) << 16)
                | ((hdr[6] & 0xFF) << 8)
                | (hdr[7] & 0xFF);
        long headerSize = 8L;
        if (size == 1L) {
            if (pos + 16 > limit) {
                return null;
            }
            size = readI64(source, pos + 8);
            headerSize = 16L;
        } else if (size == 0L) {
            size = limit - pos;
        }
        if (size < headerSize || pos + size > limit) {
            return null;
        }
        return new BoxHeader(type, pos + headerSize, pos + size);
    }

    private static int readU8(@NonNull DataSource source, long pos) throws IOException {
        byte[] b = readBytes(source, pos, 1);
        return b[0] & 0xFF;
    }

    private static int readI32(@NonNull DataSource source, long pos) throws IOException {
        byte[] b = readBytes(source, pos, 4);
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    private static long readU32(@NonNull DataSource source, long pos) throws IOException {
        return readI32(source, pos) & 0xFFFFFFFFL;
    }

    private static long readI64(@NonNull DataSource source, long pos) throws IOException {
        byte[] b = readBytes(source, pos, 8);
        return ((b[0] & 0xFFL) << 56)
                | ((b[1] & 0xFFL) << 48)
                | ((b[2] & 0xFFL) << 40)
                | ((b[3] & 0xFFL) << 32)
                | ((b[4] & 0xFFL) << 24)
                | ((b[5] & 0xFFL) << 16)
                | ((b[6] & 0xFFL) << 8)
                | (b[7] & 0xFFL);
    }

    @NonNull
    private static byte[] readBytes(@NonNull DataSource source, long pos, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = source.readAt(pos, buf, 0, length);
        if (read < length) {
            return Arrays.copyOf(buf, Math.max(0, read));
        }
        return buf;
    }

    private static final class BoxHeader {
        final int type;
        final long payloadStart;
        final long end;

        BoxHeader(int type, long payloadStart, long end) {
            this.type = type;
            this.payloadStart = payloadStart;
            this.end = end;
        }
    }

    private static final class StscEntry {
        final int firstChunk;
        final int samplesPerChunk;

        StscEntry(int firstChunk, int samplesPerChunk) {
            this.firstChunk = firstChunk;
            this.samplesPerChunk = samplesPerChunk;
        }
    }

    private static final class TrakInfo {
        int trackId = -1;
        int handlerType = 0;
        long timescale = 1000L;
        final List<Integer> chapTrackRefs = new ArrayList<>();
        final List<Long> sampleDeltas = new ArrayList<>();
        final List<StscEntry> stscEntries = new ArrayList<>();
        int[] sampleSizes = new int[0];
        long[] chunkOffsets = new long[0];
    }
}
