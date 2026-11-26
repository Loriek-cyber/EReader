package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.util.List;
import java.util.Map;

/**
 * Loads and applies structured {@code .json} metadata files to {@link AudioBook} and {@link Book}
 * titles via Gson, supporting nested chapter hierarchies and flat Audiobookshelf/FFmpeg chapter lists.
 */
public final class MetadataJsonParser {

    private static final String TAG = "MetadataJsonParser";
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final Gson GSON = new Gson();

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
        try {
            return applyParsedMetadata(context, title, parseJson(rawJson));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid metadata JSON: " + jsonUri, e);
            return new ApplyResult(false, 0);
        }
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
        if (title instanceof AudioBook) {
            return applyToAudioBook(context, (AudioBook) title, metadata);
        }
        if (title instanceof Book) {
            return applyToBook(context, (Book) title, metadata);
        }
        new TitleDao(context).update(title);
        return new ApplyResult(true, 0);
    }

    @NonNull
    private static ApplyResult applyToAudioBook(
            @NonNull Context context,
            @NonNull AudioBook audioBook,
            @NonNull ParsedMetadata metadata
    ) {
        if (metadata.narrator != null && !metadata.narrator.trim().isEmpty()) {
            audioBook.setNarrator(metadata.narrator.trim());
        }
        if (metadata.totalDurationMs > 0L) {
            audioBook.setTotalDurationMs(metadata.totalDurationMs);
        }

        int totalNodes = 0;
        if (!metadata.chapters.isEmpty()) {
            List<Chapter> roots = buildAudioChapterTree(
                    audioBook.getId(), metadata.chapters, audioBook.getTotalDurationMs(), audioBook.getCurrentPositionMs());
            totalNodes = Chapter.flattenTree(roots).size();
            if (totalNodes > 0) {
                new ChapterDao(context).replaceChapterTree(audioBook.getId(), roots);
                audioBook.setLenChapters(totalNodes);
                audioBook.setCurrentChapter(findMatchingLeafChapter(roots, audioBook.getCurrentPositionMs()));
                inferDurationIfMissing(audioBook, roots);
            }
        }
        new TitleDao(context).update(audioBook);
        PlaybackSessionManager.getInstance().invalidateChapterCache(audioBook.getId());
        return new ApplyResult(true, totalNodes);
    }

    private static int findMatchingLeafChapter(@NonNull List<Chapter> roots, long savedPosMs) {
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

    private static void inferDurationIfMissing(@NonNull AudioBook audioBook, @NonNull List<Chapter> roots) {
        if (audioBook.getTotalDurationMs() > 0L) {
            return;
        }
        long inferred = 0L;
        for (Chapter root : roots) {
            inferred = Math.max(inferred, root.getAudioStartMs() + root.getAudioDurationMs());
        }
        if (inferred > 0L) {
            audioBook.setTotalDurationMs(inferred);
        }
    }

    @NonNull
    private static ApplyResult applyToBook(
            @NonNull Context context,
            @NonNull Book book,
            @NonNull ParsedMetadata metadata
    ) {
        if (metadata.totalPages > 0) {
            book.setTotalPages(metadata.totalPages);
        }
        ChapterDao chapterDao = new ChapterDao(context);
        int updatedCount = 0;
        if (!metadata.chapters.isEmpty()) {
            List<Chapter> existing = chapterDao.getChaptersForTitle(book.getId());
            Map<Integer, Chapter> existingByIndex = new HashMap<>(existing.size());
            for (Chapter ch : existing) {
                existingByIndex.put(ch.getChapterIndex(), ch);
            }
            List<Chapter> roots = buildEbookChapterTree(book.getId(), metadata.chapters, existingByIndex);
            updatedCount = Chapter.flattenTree(roots).size();
            if (updatedCount > 0) {
                chapterDao.replaceChapterTree(book.getId(), roots);
                book.setLenChapters(updatedCount);
                book.setCurrentChapter(ChapterTreeHelper.resolveNearestLeafIndex(roots, book.getCurrentChapter()));
            }
        }
        new TitleDao(context).update(book);
        PlaybackSessionManager.getInstance().syncActiveTitle(book);
        return new ApplyResult(true, updatedCount);
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(@NonNull String rawJson) {
        return GSON.fromJson(parseRootObject(rawJson), Map.class);
    }

    @NonNull
    private static JsonObject parseRootObject(@NonNull String rawJson) {
        try {
            JsonElement element = JsonParser.parseString(rawJson);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Root JSON element must be an object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed JSON", e);
        }
    }

    @NonNull
    public static ParsedMetadata parseJson(@NonNull String rawJson) {
        JsonObject root = parseRootObject(rawJson);
        String title = optString(root, "title", "name");
        String author = optString(root, "author");
        if (author == null || author.isEmpty()) {
            author = joinNames(root.get("authors"));
        }
        String narrator = optString(root, "narrator");
        if (narrator == null || narrator.isEmpty()) {
            narrator = joinNames(root.get("narrators"));
        }
        long totalDurationMs = optTimeFieldMs(root, "totalDurationMs", "durationMs", "total_duration_ms", "duration_ms");
        if (totalDurationMs < 0L) {
            totalDurationMs = parseSecondsOrTimestampMs(root.get("duration"));
        }
        int totalPages = (int) optLong(root, -1L, "totalPages", "total_pages", "pages");
        List<ParsedChapterNode> chapters = parseChapterList(firstMember(root, "chapters", "sections", "toc"));
        return new ParsedMetadata(title, author, narrator, totalDurationMs, totalPages, chapters);
    }

    @NonNull
    public static List<Chapter> buildAudioChapterTree(
            int titleId,
            @NonNull List<ParsedChapterNode> parsedNodes,
            long totalDurationMs,
            long savedPositionMs
    ) {
        if (!hasAnyChildren(parsedNodes)) {
            List<M4BParser.M4bChapter> flat = new ArrayList<>(parsedNodes.size());
            for (int i = 0; i < parsedNodes.size(); i++) {
                ParsedChapterNode n = parsedNodes.get(i);
                long fallbackEnd = i + 1 < parsedNodes.size() ? parsedNodes.get(i + 1).startMs : totalDurationMs;
                flat.add(new M4BParser.M4bChapter(i + 1, n.title, n.startMs, resolveAudioDurationMs(n, fallbackEnd), n.level));
            }
            return FileScanner.buildAudiobookChapterTree(titleId, flat, savedPositionMs);
        }
        int[] indexCounter = {1};
        List<Chapter> roots = new ArrayList<>(parsedNodes.size());
        for (int i = 0; i < parsedNodes.size(); i++) {
            long nextStart = i + 1 < parsedNodes.size() ? parsedNodes.get(i + 1).startMs : totalDurationMs;
            roots.add(convertAudioNodeRecursive(
                    titleId, parsedNodes.get(i), 0, i, nextStart, savedPositionMs, indexCounter));
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
        int pct = completed ? 100
                : (savedPositionMs > 0L && savedPositionMs >= node.startMs && durMs > 0L && savedPositionMs < endMs)
                        ? PlaybackSessionManager.computeProgressPercent(savedPositionMs - node.startMs, durMs)
                        : 0;

        Chapter chapter = new Chapter(
                0, titleId, 0, chIndex, level, sortOrder,
                node.title, "", "", "", FileScanner.formatDuration(durMs),
                "", "", node.startMs, durMs, pct, completed
        );

        long maxEnd = endMs;
        for (int i = 0; i < node.children.size(); i++) {
            long childEnd = i + 1 < node.children.size() ? node.children.get(i + 1).startMs : fallbackEndMs;
            Chapter child = convertAudioNodeRecursive(
                    titleId, node.children.get(i), level + 1, i, childEnd, savedPositionMs, indexCounter);
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
                node.title, "", href, frag, label, contentText, "", startPage, endPage, pct, done
        );
        for (int i = 0; i < node.children.size(); i++) {
            chapter.addChild(convertEbookNodeRecursive(
                    titleId, node.children.get(i), level + 1, i, existingByIndex, indexCounter));
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
        return fallbackEndMs > node.startMs ? fallbackEndMs - node.startMs : 0L;
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
    private static List<ParsedChapterNode> parseChapterList(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = raw.getAsJsonArray();
        List<ParsedChapterNode> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!item.isJsonObject()) {
                continue;
            }
            out.add(parseSingleChapter(item.getAsJsonObject(), i));
        }
        return out;
    }

    @NonNull
    private static ParsedChapterNode parseSingleChapter(@NonNull JsonObject obj, int position) {
        String rawTitle = optString(obj, "title", "name", "chapterTitle", "chapter_title");
        int explicitLevel = (int) optLong(obj, -1L, "level", "depth");
        int level = explicitLevel >= 0 ? explicitLevel : Mp4ChapterParser.detectHierarchyLevel(rawTitle);
        String title = rawTitle != null && !rawTitle.trim().isEmpty()
                ? rawTitle.trim()
                : ("Chapter " + (position + 1));

        long startMs = optTimeFieldMs(obj, "startMs", "start_ms", "audioStartMs", "audio_start_ms");
        if (startMs < 0L) {
            startMs = Math.max(0L, parseSecondsOrTimestampMs(firstMember(obj, "start", "startTime", "start_time")));
        }

        long endMs = optTimeFieldMs(obj, "endMs", "end_ms");
        if (endMs < 0L) {
            endMs = parseSecondsOrTimestampMs(firstMember(obj, "end", "endTime", "end_time"));
        }

        long durationMs = optTimeFieldMs(obj, "durationMs", "duration_ms", "audioDurationMs", "audio_duration_ms");
        if (durationMs < 0L) {
            durationMs = Math.max(0L, parseSecondsOrTimestampMs(obj.get("duration")));
        }

        int startPage = (int) optLong(obj, 0L, "startPage", "start_page", "page");
        int endPage = (int) optLong(obj, 0L, "endPage", "end_page");
        List<ParsedChapterNode> children = parseChapterList(firstMember(obj, "children", "subchapters", "sections"));
        return new ParsedChapterNode(title, startMs, durationMs, endMs, startPage, endPage, level, children);
    }

    private static long optTimeFieldMs(@NonNull JsonObject obj, @NonNull String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el == null || !el.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                return Math.max(0L, p.getAsLong());
            }
            if (p.isString()) {
                String s = p.getAsString().trim();
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

    private static long parseSecondsOrTimestampMs(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return -1L;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isNumber()) {
            double seconds = p.getAsDouble();
            return seconds >= 0.0 ? Math.round(seconds * 1000.0) : -1L;
        }
        if (!p.isString()) {
            return -1L;
        }
        String s = p.getAsString().trim();
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
            return -1L;
        }
    }

    @Nullable
    private static JsonElement firstMember(@NonNull JsonObject obj, @NonNull String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) {
                return el;
            }
        }
        return null;
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return el.getAsString();
            }
        }
        return null;
    }

    private static long optLong(@NonNull JsonObject obj, long defaultValue, @NonNull String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el == null || !el.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsLong();
            }
            if (p.isString()) {
                try {
                    return Long.parseLong(p.getAsString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultValue;
    }

    @Nullable
    private static String joinNames(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String s = item.getAsString().trim();
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            } else if (item.isJsonObject()) {
                String name = optString(item.getAsJsonObject(), "name", "title");
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
}
