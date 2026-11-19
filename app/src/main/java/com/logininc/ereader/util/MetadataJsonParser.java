package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads and applies structured {@code .json} metadata files to {@link AudioBook} and {@link Book}
 * titles, supporting both nested chapter hierarchies ({@code children}/{@code subchapters}/{@code sections})
 * and flat chapter lists (including Audiobookshelf/FFmpeg {@code metadata.json} format).
 */
public final class MetadataJsonParser {

    private static final String TAG = "MetadataJsonParser";
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private MetadataJsonParser() {
    }

    public static final class ParsedChapterNode {
        @NonNull
        public final String title;
        public final long startMs;
        public final long durationMs;
        public final long endMs;
        public final int startPage;
        public final int endPage;
        public final int level;
        @NonNull
        public final List<ParsedChapterNode> children;

        public ParsedChapterNode(
                @NonNull String title,
                long startMs,
                long durationMs,
                long endMs,
                int startPage,
                int endPage,
                int level,
                @NonNull List<ParsedChapterNode> children
        ) {
            this.title = title;
            this.startMs = Math.max(0L, startMs);
            this.durationMs = Math.max(0L, durationMs);
            this.endMs = endMs;
            this.startPage = Math.max(0, startPage);
            this.endPage = Math.max(0, endPage);
            this.level = Math.max(0, level);
            this.children = children;
        }
    }

    public static final class ParsedMetadata {
        @Nullable
        public final String title;
        @Nullable
        public final String author;
        @Nullable
        public final String narrator;
        public final long totalDurationMs;
        public final int totalPages;
        @NonNull
        public final List<ParsedChapterNode> chapters;

        public ParsedMetadata(
                @Nullable String title,
                @Nullable String author,
                @Nullable String narrator,
                long totalDurationMs,
                int totalPages,
                @NonNull List<ParsedChapterNode> chapters
        ) {
            this.title = title;
            this.author = author;
            this.narrator = narrator;
            this.totalDurationMs = totalDurationMs;
            this.totalPages = totalPages;
            this.chapters = chapters;
        }
    }

    public static final class ApplyResult {
        public final boolean success;
        public final int chapterCount;

        public ApplyResult(boolean success, int chapterCount) {
            this.success = success;
            this.chapterCount = chapterCount;
        }
    }

    @NonNull
    public static ApplyResult applyJsonUriToTitle(
            @NonNull Context context,
            @NonNull Title title,
            @NonNull Uri jsonUri
    ) {
        String rawJson = readTextFromUri(context, jsonUri);
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return new ApplyResult(false, 0);
        }
        ParsedMetadata parsed;
        try {
            parsed = parseJson(rawJson);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid metadata JSON: " + jsonUri, e);
            return new ApplyResult(false, 0);
        }
        return applyParsedMetadata(context, title, parsed);
    }

    @NonNull
    public static ApplyResult applyParsedMetadata(
            @NonNull Context context,
            @NonNull Title title,
            @NonNull ParsedMetadata metadata
    ) {
        if (metadata.title != null && !metadata.title.trim().isEmpty()) {
            title.setTitle(metadata.title.trim());
        }
        if (metadata.author != null && !metadata.author.trim().isEmpty()) {
            title.setAuthor(metadata.author.trim());
        }

        ChapterDao chapterDao = new ChapterDao(context);
        TitleDao titleDao = new TitleDao(context);

        if (title instanceof AudioBook) {
            AudioBook audioBook = (AudioBook) title;
            if (metadata.narrator != null && !metadata.narrator.trim().isEmpty()) {
                audioBook.setNarrator(metadata.narrator.trim());
            }
            if (metadata.totalDurationMs > 0L) {
                audioBook.setTotalDurationMs(metadata.totalDurationMs);
            }

            int totalNodes = 0;
            if (!metadata.chapters.isEmpty()) {
                List<Chapter> roots = buildAudioChapterTree(
                        audioBook.getId(),
                        metadata.chapters,
                        audioBook.getTotalDurationMs(),
                        audioBook.getCurrentPositionMs()
                );
                List<Chapter> flat = Chapter.flattenTree(roots);
                totalNodes = flat.size();
                if (totalNodes > 0) {
                    chapterDao.replaceChapterTree(audioBook.getId(), roots);
                    audioBook.setLenChapters(totalNodes);

                    // Keep saved playback position and sync currentChapter to matching leaf
                    List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
                    long savedPos = audioBook.getCurrentPositionMs();
                    int matchedChapter = leaves.isEmpty() ? 1 : leaves.get(0).getChapterIndex();
                    for (Chapter leaf : leaves) {
                        long start = leaf.getAudioStartMs();
                        long end = start + leaf.getAudioDurationMs();
                        if (savedPos >= start && (leaf.getAudioDurationMs() <= 0L || savedPos < end)) {
                            matchedChapter = leaf.getChapterIndex();
                            break;
                        }
                    }
                    audioBook.setCurrentChapter(matchedChapter);
                    if (audioBook.getTotalDurationMs() <= 0L) {
                        long inferredDur = 0L;
                        for (Chapter root : roots) {
                            inferredDur = Math.max(inferredDur, root.getAudioStartMs() + root.getAudioDurationMs());
                        }
                        if (inferredDur > 0L) {
                            audioBook.setTotalDurationMs(inferredDur);
                        }
                    }
                }
            }
            titleDao.update(audioBook);
            PlaybackSessionManager.getInstance().invalidateChapterCache(audioBook.getId());
            return new ApplyResult(true, totalNodes);
        }

        if (title instanceof Book) {
            Book book = (Book) title;
            if (metadata.totalPages > 0) {
                book.setTotalPages(metadata.totalPages);
            }
            int updatedCount = 0;
            if (!metadata.chapters.isEmpty()) {
                List<Chapter> existingFlat = chapterDao.getChaptersForTitle(book.getId());
                Map<Integer, Chapter> existingByIndex = new HashMap<>(existingFlat.size());
                for (Chapter ch : existingFlat) {
                    existingByIndex.put(ch.getChapterIndex(), ch);
                }
                List<Chapter> roots = buildEbookChapterTree(book.getId(), metadata.chapters, existingByIndex);
                List<Chapter> flat = Chapter.flattenTree(roots);
                updatedCount = flat.size();
                if (updatedCount > 0) {
                    chapterDao.replaceChapterTree(book.getId(), roots);
                    book.setLenChapters(updatedCount);
                    int resolved = ChapterTreeHelper.resolveNearestLeafIndex(roots, book.getCurrentChapter());
                    book.setCurrentChapter(resolved);
                }
            }
            titleDao.update(book);
            PlaybackSessionManager.getInstance().syncActiveTitle(book);
            return new ApplyResult(true, updatedCount);
        }

        titleDao.update(title);
        return new ApplyResult(true, 0);
    }

    /**
     * Parses a raw JSON string into a Map (for JSON objects).
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(@NonNull String rawJson) {
        Object parsed = new SimpleJsonParser(rawJson).parseValue();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Root JSON element must be an object");
        }
        return (Map<String, Object>) parsed;
    }

    /**
     * Parses a JSON metadata string into a {@link ParsedMetadata} object.
     *
     * @throws IllegalArgumentException if the JSON is malformed or not a JSON object.
     */
    @NonNull
    public static ParsedMetadata parseJson(@NonNull String rawJson) {
        Map<String, Object> root = parseJsonObject(rawJson);

        String title = getString(root, "title", "name");
        String author = getString(root, "author");
        if (author == null || author.isEmpty()) {
            author = joinStringList(root.get("authors"));
        }
        String narrator = getString(root, "narrator");
        if (narrator == null || narrator.isEmpty()) {
            narrator = joinStringList(root.get("narrators"));
        }

        long totalDurationMs = parseTimeFieldMs(root, "totalDurationMs", "durationMs", "total_duration_ms", "duration_ms");
        if (totalDurationMs < 0L) {
            totalDurationMs = parseSecondsOrTimeStringMs(root.get("duration"));
        }
        int totalPages = (int) getLong(root, -1L, "totalPages", "total_pages", "pages");

        List<ParsedChapterNode> chapters = parseChapterList(
                firstNonNull(root.get("chapters"), root.get("sections"), root.get("toc"))
        );

        return new ParsedMetadata(title, author, narrator, totalDurationMs, totalPages, chapters);
    }

    /**
     * Builds a hierarchical {@link Chapter} tree for an {@link AudioBook} from parsed JSON nodes,
     * supporting both explicit nested {@code children} and flat lists with {@code level}/indentation.
     */
    @NonNull
    public static List<Chapter> buildAudioChapterTree(
            int titleId,
            @NonNull List<ParsedChapterNode> parsedNodes,
            long totalDurationMs,
            long savedPositionMs
    ) {
        boolean hasExplicitNesting = hasAnyChildren(parsedNodes);
        if (!hasExplicitNesting) {
            List<M4BParser.M4bChapter> flat = new ArrayList<>(parsedNodes.size());
            for (int i = 0; i < parsedNodes.size(); i++) {
                ParsedChapterNode n = parsedNodes.get(i);
                long dur = resolveAudioDurationMs(n, i + 1 < parsedNodes.size() ? parsedNodes.get(i + 1).startMs : totalDurationMs);
                flat.add(new M4BParser.M4bChapter(i + 1, n.title, n.startMs, dur, n.level));
            }
            return FileScanner.buildAudiobookChapterTree(titleId, flat, savedPositionMs);
        }

        int[] indexCounter = {1};
        List<Chapter> roots = new ArrayList<>(parsedNodes.size());
        for (int i = 0; i < parsedNodes.size(); i++) {
            long nextSiblingStart = i + 1 < parsedNodes.size() ? parsedNodes.get(i + 1).startMs : totalDurationMs;
            roots.add(convertAudioNodeRecursive(
                    titleId, parsedNodes.get(i), 0, i, nextSiblingStart, savedPositionMs, indexCounter
            ));
        }
        return roots;
    }

    @NonNull
    private static Chapter convertAudioNodeRecursive(
            int titleId,
            @NonNull ParsedChapterNode node,
            int level,
            int sortOrder,
            long fallbackEndMs,
            long savedPositionMs,
            @NonNull int[] indexCounter
    ) {
        int chIndex = indexCounter[0]++;
        long durMs = resolveAudioDurationMs(node, fallbackEndMs);
        long endMs = node.startMs + durMs;
        boolean completed = savedPositionMs > 0L && durMs > 0L && savedPositionMs >= endMs;
        int pct = 0;
        if (completed) {
            pct = 100;
        } else if (savedPositionMs > 0L && savedPositionMs >= node.startMs && durMs > 0L && savedPositionMs < endMs) {
            pct = PlaybackSessionManager.computeProgressPercent(savedPositionMs - node.startMs, durMs);
        }

        Chapter chapter = new Chapter(
                0, titleId, 0, chIndex, level, sortOrder,
                node.title, "", "", "", FileScanner.formatDuration(durMs),
                "", "", node.startMs, durMs, pct, completed
        );

        long maxEnd = endMs;
        for (int i = 0; i < node.children.size(); i++) {
            long childFallbackEnd = (i + 1 < node.children.size())
                    ? node.children.get(i + 1).startMs
                    : fallbackEndMs;
            Chapter child = convertAudioNodeRecursive(
                    titleId, node.children.get(i), level + 1, i, childFallbackEnd, savedPositionMs, indexCounter
            );
            chapter.addChild(child);
            maxEnd = Math.max(maxEnd, child.getAudioStartMs() + child.getAudioDurationMs());
        }
        if (!chapter.getChildren().isEmpty() && maxEnd > chapter.getAudioStartMs()) {
            long span = maxEnd - chapter.getAudioStartMs();
            chapter.setAudioDurationMs(span);
            chapter.setPageOrDurationLabel(FileScanner.formatDuration(span));
        }
        return chapter;
    }

    @NonNull
    private static List<Chapter> buildEbookChapterTree(
            int titleId,
            @NonNull List<ParsedChapterNode> parsedNodes,
            @NonNull Map<Integer, Chapter> existingByIndex
    ) {
        int[] indexCounter = {1};
        List<Chapter> roots = new ArrayList<>(parsedNodes.size());
        for (int i = 0; i < parsedNodes.size(); i++) {
            roots.add(convertEbookNodeRecursive(titleId, parsedNodes.get(i), 0, i, existingByIndex, indexCounter));
        }
        return roots;
    }

    @NonNull
    private static Chapter convertEbookNodeRecursive(
            int titleId,
            @NonNull ParsedChapterNode node,
            int level,
            int sortOrder,
            @NonNull Map<Integer, Chapter> existingByIndex,
            @NonNull int[] indexCounter
    ) {
        int chIndex = indexCounter[0]++;
        Chapter prev = existingByIndex.get(chIndex);
        String contentText = (node.children.isEmpty() && prev != null) ? prev.getContentText() : "";
        String href = prev != null ? prev.getHref() : "";
        String frag = prev != null ? prev.getFragmentId() : "";
        int pct = prev != null ? prev.getProgressPercent() : 0;
        boolean done = prev != null && prev.isCompleted();

        long startPage = node.startPage > 0 ? node.startPage : (prev != null ? prev.getAudioStartMs() : 0L);
        long endPage = node.endPage > 0 ? node.endPage : (prev != null ? prev.getAudioDurationMs() : 0L);
        String label = startPage > 0
                ? (endPage > startPage ? ("p. " + startPage + "–" + endPage) : ("p. " + startPage))
                : (prev != null ? prev.getPageOrDurationLabel() : "");

        Chapter chapter = new Chapter(
                0, titleId, 0, chIndex, level, sortOrder,
                node.title, "", href, frag, label,
                contentText, "", startPage, endPage, pct, done
        );
        for (int i = 0; i < node.children.size(); i++) {
            chapter.addChild(convertEbookNodeRecursive(
                    titleId, node.children.get(i), level + 1, i, existingByIndex, indexCounter
            ));
        }
        return chapter;
    }

    private static long resolveAudioDurationMs(@NonNull ParsedChapterNode node, long fallbackEndMs) {
        if (node.durationMs > 0L) {
            return node.durationMs;
        }
        if (node.endMs > node.startMs) {
            return node.endMs - node.startMs;
        }
        if (fallbackEndMs > node.startMs) {
            return fallbackEndMs - node.startMs;
        }
        return 0L;
    }

    private static boolean hasAnyChildren(@NonNull List<ParsedChapterNode> nodes) {
        for (ParsedChapterNode n : nodes) {
            if (!n.children.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static List<ParsedChapterNode> parseChapterList(@Nullable Object raw) {
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        List<?> list = (List<?>) raw;
        List<ParsedChapterNode> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) item;

            String rawTitle = getString(obj, "title", "name", "chapterTitle", "chapter_title");
            int explicitLevel = (int) getLong(obj, -1L, "level", "depth");
            int level = explicitLevel >= 0 ? explicitLevel : Mp4ChapterParser.detectHierarchyLevel(rawTitle);
            String title = rawTitle != null && !rawTitle.trim().isEmpty()
                    ? rawTitle.trim()
                    : ("Chapter " + (i + 1));

            long startMs = parseTimeFieldMs(obj, "startMs", "start_ms", "audioStartMs", "audio_start_ms");
            if (startMs < 0L) {
                startMs = parseSecondsOrTimeStringMs(firstNonNull(obj.get("start"), obj.get("startTime"), obj.get("start_time")));
            }
            if (startMs < 0L) {
                startMs = 0L;
            }

            long endMs = parseTimeFieldMs(obj, "endMs", "end_ms");
            if (endMs < 0L) {
                endMs = parseSecondsOrTimeStringMs(firstNonNull(obj.get("end"), obj.get("endTime"), obj.get("end_time")));
            }

            long durationMs = parseTimeFieldMs(obj, "durationMs", "duration_ms", "audioDurationMs", "audio_duration_ms");
            if (durationMs < 0L) {
                durationMs = parseSecondsOrTimeStringMs(obj.get("duration"));
            }
            if (durationMs < 0L) {
                durationMs = 0L;
            }

            int startPage = (int) getLong(obj, 0L, "startPage", "start_page", "page");
            int endPage = (int) getLong(obj, 0L, "endPage", "end_page");

            List<ParsedChapterNode> children = parseChapterList(
                    firstNonNull(obj.get("children"), obj.get("subchapters"), obj.get("sections"))
            );
            out.add(new ParsedChapterNode(title, startMs, durationMs, endMs, startPage, endPage, level, children));
        }
        return out;
    }

    private static long parseTimeFieldMs(@NonNull Map<String, Object> obj, @NonNull String... keys) {
        for (String key : keys) {
            Object val = obj.get(key);
            if (val instanceof Number) {
                return Math.max(0L, ((Number) val).longValue());
            }
            if (val instanceof String) {
                String s = ((String) val).trim();
                if (!s.isEmpty()) {
                    try {
                        return Math.max(0L, Long.parseLong(s));
                    } catch (NumberFormatException ignored) {
                        long ts = M4BParser.parseTimestampMs(s);
                        if (ts >= 0L) {
                            return ts;
                        }
                    }
                }
            }
        }
        return -1L;
    }

    private static long parseSecondsOrTimeStringMs(@Nullable Object val) {
        if (val instanceof Number) {
            double seconds = ((Number) val).doubleValue();
            return seconds >= 0.0 ? Math.round(seconds * 1000.0) : -1L;
        }
        if (val instanceof String) {
            String s = ((String) val).trim();
            if (s.isEmpty()) {
                return -1L;
            }
            if (s.contains(":")) {
                return M4BParser.parseTimestampMs(s);
            }
            try {
                double seconds = Double.parseDouble(s);
                return seconds >= 0.0 ? Math.round(seconds * 1000.0) : -1L;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1L;
    }

    @Nullable
    private static Object firstNonNull(@Nullable Object... values) {
        for (Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @Nullable
    private static String getString(@NonNull Map<String, Object> obj, @NonNull String... keys) {
        for (String key : keys) {
            Object val = obj.get(key);
            if (val instanceof String) {
                return (String) val;
            }
        }
        return null;
    }

    private static long getLong(@NonNull Map<String, Object> obj, long defaultValue, @NonNull String... keys) {
        for (String key : keys) {
            Object val = obj.get(key);
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
            if (val instanceof String) {
                try {
                    return Long.parseLong(((String) val).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultValue;
    }

    @Nullable
    private static String joinStringList(@Nullable Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<?> list = (List<?>) raw;
        List<String> parts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String) {
                String s = ((String) item).trim();
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            } else if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                String name = getString((Map<String, Object>) item, "name", "title");
                if (name != null && !name.trim().isEmpty()) {
                    parts.add(name.trim());
                }
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    @Nullable
    private static String readTextFromUri(@NonNull Context context, @NonNull Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > MAX_JSON_BYTES) {
                    return null;
                }
                out.write(buf, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Failed to read metadata JSON from " + uri, e);
            return null;
        }
    }

    /**
     * Minimal, zero-dependency RFC-8259 JSON parser so metadata parsing works identically
     * on Android devices and local JVM unit tests.
     */
    private static final class SimpleJsonParser {
        private final String src;
        private int pos = 0;

        SimpleJsonParser(@NonNull String src) {
            this.src = src;
        }

        @Nullable
        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + pos);
                }
                pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON object");
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < src.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON array");
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isFloat = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String token = src.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(token);
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return Double.parseDouble(token);
            }
        }

        private void expectLiteral(@NonNull String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at " + pos);
            }
            pos += literal.length();
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
