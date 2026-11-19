package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client for the Audnexus API ({@code https://api.audnex.us}) with Audible public catalog
 * search support for resolving title/author queries to ASINs.
 */
public final class AudnexusClient {

    private static final String TAG = "AudnexusClient";
    private static final String AUDNEXUS_BASE = "https://api.audnex.us";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Pattern ASIN_PATTERN = Pattern.compile("^[A-Z0-9]{10}$");

    public static final String[] SUPPORTED_REGIONS = {"it", "us", "uk", "de", "fr", "es"};

    private AudnexusClient() {
    }

    public static final class SearchCandidate {
        @NonNull
        public final String asin;
        @NonNull
        public final String title;
        @NonNull
        public final String author;
        @NonNull
        public final String narrator;

        public SearchCandidate(
                @NonNull String asin,
                @NonNull String title,
                @NonNull String author,
                @NonNull String narrator
        ) {
            this.asin = asin;
            this.title = title;
            this.author = author;
            this.narrator = narrator;
        }

        @NonNull
        public String formatDisplayLabel() {
            StringBuilder sb = new StringBuilder(title);
            if (!author.isEmpty()) {
                sb.append("\n").append(author);
            }
            if (!narrator.isEmpty()) {
                sb.append(" • ").append(narrator);
            }
            sb.append(" [").append(asin).append("]");
            return sb.toString();
        }
    }

    public static final class AudnexusBookDetail {
        @NonNull
        public final String asin;
        @Nullable
        public final String title;
        @Nullable
        public final String author;
        @Nullable
        public final String narrator;
        @Nullable
        public final String imageUrl;
        public final long runtimeLengthMs;

        public AudnexusBookDetail(
                @NonNull String asin,
                @Nullable String title,
                @Nullable String author,
                @Nullable String narrator,
                @Nullable String imageUrl,
                long runtimeLengthMs
        ) {
            this.asin = asin;
            this.title = title;
            this.author = author;
            this.narrator = narrator;
            this.imageUrl = imageUrl;
            this.runtimeLengthMs = runtimeLengthMs;
        }
    }

    public static final class AudnexusChaptersDetail {
        public final long runtimeLengthMs;
        @NonNull
        public final List<MetadataJsonParser.ParsedChapterNode> chapters;

        public AudnexusChaptersDetail(
                long runtimeLengthMs,
                @NonNull List<MetadataJsonParser.ParsedChapterNode> chapters
        ) {
            this.runtimeLengthMs = runtimeLengthMs;
            this.chapters = chapters;
        }
    }

    public static boolean isAsin(@Nullable String raw) {
        if (raw == null) {
            return false;
        }
        String clean = raw.trim().toUpperCase(Locale.ROOT);
        return ASIN_PATTERN.matcher(clean).matches();
    }

    @NonNull
    public static String normalizeRegion(@Nullable String region) {
        if (region == null) {
            return "us";
        }
        String r = region.trim().toLowerCase(Locale.ROOT);
        for (String supported : SUPPORTED_REGIONS) {
            if (supported.equals(r)) {
                return supported;
            }
        }
        return "us";
    }

    @NonNull
    private static String audibleTldForRegion(@NonNull String region) {
        switch (region) {
            case "it":
                return "it";
            case "uk":
                return "co.uk";
            case "de":
                return "de";
            case "fr":
                return "fr";
            case "es":
                return "es";
            case "us":
            default:
                return "com";
        }
    }

    /**
     * Searches for audiobooks matching {@code query} (either a direct 10-char ASIN or a title/author
     * text query) in the specified Audible/Audnexus {@code region}.
     */
    @NonNull
    public static List<SearchCandidate> search(@NonNull String query, @NonNull String region) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        String normRegion = normalizeRegion(region);

        if (isAsin(trimmed)) {
            String asin = trimmed.toUpperCase(Locale.ROOT);
            AudnexusBookDetail detail = fetchBookDetail(asin, normRegion);
            if (detail == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(new SearchCandidate(
                    asin,
                    detail.title != null && !detail.title.isEmpty() ? detail.title : asin,
                    detail.author != null ? detail.author : "",
                    detail.narrator != null ? detail.narrator : ""
            ));
        }

        try {
            String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name());
            String tld = audibleTldForRegion(normRegion);
            String urlStr = "https://api.audible." + tld
                    + "/1.0/catalog/products?num_results=10&products_sort_by=Relevance"
                    + "&keywords=" + encoded
                    + "&response_groups=contributors,media,product_desc";
            String body = httpGetString(urlStr);
            if (body == null || body.isEmpty()) {
                return Collections.emptyList();
            }
            return parseAudibleSearchResponse(body);
        } catch (Exception e) {
            Log.w(TAG, "Audible catalog search failed for '" + trimmed + "' (" + normRegion + ")", e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetches book metadata, chapters, and cover image from Audnexus for {@code asin} and applies
     * them to {@code audioBook}, preserving its saved playback position.
     */
    @NonNull
    public static MetadataJsonParser.ApplyResult fetchAndApply(
            @NonNull Context context,
            @NonNull AudioBook audioBook,
            @NonNull String asin,
            @NonNull String region
    ) {
        String cleanAsin = asin.trim().toUpperCase(Locale.ROOT);
        String normRegion = normalizeRegion(region);

        AudnexusBookDetail bookDetail = fetchBookDetail(cleanAsin, normRegion);
        AudnexusChaptersDetail chaptersDetail = fetchChaptersDetail(cleanAsin, normRegion);

        if (bookDetail == null && (chaptersDetail == null || chaptersDetail.chapters.isEmpty())) {
            return new MetadataJsonParser.ApplyResult(false, 0);
        }

        if (bookDetail != null && bookDetail.imageUrl != null && !bookDetail.imageUrl.isEmpty()) {
            byte[] coverBytes = httpGetBytes(bookDetail.imageUrl, MAX_IMAGE_BYTES);
            if (coverBytes != null && coverBytes.length > 0) {
                Uri coverUri = FileScanner.saveCoverBytes(context, "audnexus_" + cleanAsin, coverBytes);
                if (coverUri != null) {
                    audioBook.setCoverUri(coverUri);
                }
            }
        }

        long totalDurationMs = -1L;
        if (chaptersDetail != null && chaptersDetail.runtimeLengthMs > 0L) {
            totalDurationMs = chaptersDetail.runtimeLengthMs;
        } else if (bookDetail != null && bookDetail.runtimeLengthMs > 0L) {
            totalDurationMs = bookDetail.runtimeLengthMs;
        }

        MetadataJsonParser.ParsedMetadata parsedMetadata = new MetadataJsonParser.ParsedMetadata(
                bookDetail != null ? bookDetail.title : null,
                bookDetail != null ? bookDetail.author : null,
                bookDetail != null ? bookDetail.narrator : null,
                totalDurationMs,
                -1,
                chaptersDetail != null ? chaptersDetail.chapters : Collections.emptyList()
        );

        return MetadataJsonParser.applyParsedMetadata(context, audioBook, parsedMetadata);
    }

    @Nullable
    public static AudnexusBookDetail fetchBookDetail(@NonNull String asin, @NonNull String region) {
        String urlStr = AUDNEXUS_BASE + "/books/" + asin + "?region=" + normalizeRegion(region);
        String body = httpGetString(urlStr);
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return parseAudnexusBookResponse(asin, body);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to parse Audnexus book JSON for " + asin, e);
            return null;
        }
    }

    @Nullable
    public static AudnexusChaptersDetail fetchChaptersDetail(@NonNull String asin, @NonNull String region) {
        String urlStr = AUDNEXUS_BASE + "/books/" + asin + "/chapters?region=" + normalizeRegion(region);
        String body = httpGetString(urlStr);
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return parseAudnexusChaptersResponse(body);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to parse Audnexus chapters JSON for " + asin, e);
            return null;
        }
    }

    @NonNull
    public static List<SearchCandidate> parseAudibleSearchResponse(@NonNull String rawJson) {
        Map<String, Object> root = MetadataJsonParser.parseJsonObject(rawJson);
        Object productsObj = root.get("products");
        if (!(productsObj instanceof List)) {
            return Collections.emptyList();
        }
        List<?> products = (List<?>) productsObj;
        List<SearchCandidate> out = new ArrayList<>(products.size());
        for (Object item : products) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> prod = (Map<String, Object>) item;
            String asin = stringField(prod, "asin");
            String title = stringField(prod, "title");
            if (asin == null || asin.trim().isEmpty() || title == null || title.trim().isEmpty()) {
                continue;
            }
            String authors = extractNamedList(prod.get("authors"));
            String narrators = extractNamedList(prod.get("narrators"));
            out.add(new SearchCandidate(asin.trim(), title.trim(), authors, narrators));
        }
        return out;
    }

    @NonNull
    public static AudnexusBookDetail parseAudnexusBookResponse(@NonNull String fallbackAsin, @NonNull String rawJson) {
        Map<String, Object> root = MetadataJsonParser.parseJsonObject(rawJson);
        String asin = stringField(root, "asin");
        if (asin == null || asin.trim().isEmpty()) {
            asin = fallbackAsin;
        }
        String title = stringField(root, "title");
        String authors = extractNamedList(root.get("authors"));
        String narrators = extractNamedList(root.get("narrators"));
        String image = stringField(root, "image");
        long runtimeMin = numberField(root, "runtimeLengthMin");
        long runtimeMs = runtimeMin > 0L ? runtimeMin * 60_000L : -1L;

        return new AudnexusBookDetail(
                asin.trim(),
                title != null && !title.trim().isEmpty() ? title.trim() : null,
                !authors.isEmpty() ? authors : null,
                !narrators.isEmpty() ? narrators : null,
                image != null && !image.trim().isEmpty() ? image.trim() : null,
                runtimeMs
        );
    }

    @NonNull
    public static AudnexusChaptersDetail parseAudnexusChaptersResponse(@NonNull String rawJson) {
        Map<String, Object> root = MetadataJsonParser.parseJsonObject(rawJson);
        long runtimeMs = numberField(root, "runtimeLengthMs");
        Object rawChapters = root.get("chapters");
        if (!(rawChapters instanceof List)) {
            return new AudnexusChaptersDetail(runtimeMs, Collections.emptyList());
        }
        List<?> list = (List<?>) rawChapters;
        List<MetadataJsonParser.ParsedChapterNode> chapters = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ch = (Map<String, Object>) item;
            String rawTitle = stringField(ch, "title");
            int level = Mp4ChapterParser.detectHierarchyLevel(rawTitle);
            String cleanTitle = rawTitle != null && !rawTitle.trim().isEmpty()
                    ? rawTitle.trim()
                    : ("Chapter " + (i + 1));

            long startMs = numberField(ch, "startOffsetMs");
            if (startMs < 0L) {
                long startSec = numberField(ch, "startOffsetSec");
                startMs = startSec >= 0L ? startSec * 1000L : 0L;
            }
            long lengthMs = Math.max(0L, numberField(ch, "lengthMs"));

            chapters.add(new MetadataJsonParser.ParsedChapterNode(
                    cleanTitle, startMs, lengthMs, -1L, 0, 0, level, Collections.emptyList()
            ));
        }
        return new AudnexusChaptersDetail(runtimeMs, chapters);
    }

    @Nullable
    private static String stringField(@NonNull Map<String, Object> map, @NonNull String key) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : null;
    }

    private static long numberField(@NonNull Map<String, Object> map, @NonNull String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        if (val instanceof String) {
            try {
                return Long.parseLong(((String) val).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1L;
    }

    @NonNull
    private static String extractNamedList(@Nullable Object raw) {
        if (!(raw instanceof List)) {
            return "";
        }
        List<?> list = (List<?>) raw;
        List<String> names = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String) {
                String s = ((String) item).trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            } else if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                String name = stringField((Map<String, Object>) item, "name");
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
        }
        return String.join(", ", names);
    }

    @Nullable
    private static String httpGetString(@NonNull String urlStr) {
        byte[] bytes = httpGetBytes(urlStr, 2 * 1024 * 1024);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    @Nullable
    private static byte[] httpGetBytes(@NonNull String urlStr, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "EReader-Android/1.0");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                int read;
                while ((read = is.read(buf)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        return null;
                    }
                    out.write(buf, 0, read);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            Log.w(TAG, "HTTP GET failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
