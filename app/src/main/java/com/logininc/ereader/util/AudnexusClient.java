package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.logininc.ereader.model.AudioBook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Client for the Audnexus API ({@code https://api.audnex.us}) backed by OkHttp and Gson,
 * with Audible public catalog search support for resolving queries to ASINs.
 */
public final class AudnexusClient {

    private static final String TAG = "AudnexusClient";
    private static final String AUDNEXUS_BASE = "https://api.audnex.us";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Pattern ASIN_PATTERN = Pattern.compile("^[A-Z0-9]{10}$");

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

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
        return ASIN_PATTERN.matcher(raw.trim().toUpperCase(Locale.ROOT)).matches();
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
            String urlStr = "https://api.audible." + audibleTldForRegion(normRegion)
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

        long totalDurationMs = (chaptersDetail != null && chaptersDetail.runtimeLengthMs > 0L)
                ? chaptersDetail.runtimeLengthMs
                : (bookDetail != null && bookDetail.runtimeLengthMs > 0L ? bookDetail.runtimeLengthMs : -1L);

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
        JsonObject root = parseRootObject(rawJson);
        JsonElement productsEl = root.get("products");
        if (productsEl == null || !productsEl.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray products = productsEl.getAsJsonArray();
        List<SearchCandidate> out = new ArrayList<>(products.size());
        for (JsonElement item : products) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject prod = item.getAsJsonObject();
            String asin = optString(prod, "asin");
            String title = optString(prod, "title");
            if (asin == null || asin.trim().isEmpty() || title == null || title.trim().isEmpty()) {
                continue;
            }
            out.add(new SearchCandidate(
                    asin.trim(),
                    title.trim(),
                    extractNamedList(prod.get("authors")),
                    extractNamedList(prod.get("narrators"))
            ));
        }
        return out;
    }

    @NonNull
    public static AudnexusBookDetail parseAudnexusBookResponse(@NonNull String fallbackAsin, @NonNull String rawJson) {
        JsonObject root = parseRootObject(rawJson);
        String asin = optString(root, "asin");
        if (asin == null || asin.trim().isEmpty()) {
            asin = fallbackAsin;
        }
        String title = optString(root, "title");
        String authors = extractNamedList(root.get("authors"));
        String narrators = extractNamedList(root.get("narrators"));
        String image = optString(root, "image");
        long runtimeMin = optLong(root, "runtimeLengthMin");
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
        JsonObject root = parseRootObject(rawJson);
        long runtimeMs = optLong(root, "runtimeLengthMs");
        JsonElement rawChapters = root.get("chapters");
        if (rawChapters == null || !rawChapters.isJsonArray()) {
            return new AudnexusChaptersDetail(runtimeMs, Collections.emptyList());
        }
        JsonArray arr = rawChapters.getAsJsonArray();
        List<MetadataJsonParser.ParsedChapterNode> chapters = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject ch = item.getAsJsonObject();
            String rawTitle = optString(ch, "title");
            int level = Mp4ChapterParser.detectHierarchyLevel(rawTitle);
            String cleanTitle = rawTitle != null && !rawTitle.trim().isEmpty()
                    ? rawTitle.trim()
                    : ("Chapter " + (i + 1));

            long startMs = optLong(ch, "startOffsetMs");
            if (startMs < 0L) {
                long startSec = optLong(ch, "startOffsetSec");
                startMs = startSec >= 0L ? startSec * 1000L : 0L;
            }
            long lengthMs = Math.max(0L, optLong(ch, "lengthMs"));
            chapters.add(new MetadataJsonParser.ParsedChapterNode(
                    cleanTitle, startMs, lengthMs, -1L, 0, 0, level, Collections.emptyList()));
        }
        return new AudnexusChaptersDetail(runtimeMs, chapters);
    }

    @NonNull
    private static JsonObject parseRootObject(@NonNull String rawJson) {
        try {
            JsonElement el = JsonParser.parseString(rawJson);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("Root JSON element must be an object");
            }
            return el.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed JSON", e);
        }
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
                ? el.getAsString()
                : null;
    }

    private static long optLong(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return -1L;
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
        return -1L;
    }

    @NonNull
    private static String extractNamedList(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String s = item.getAsString().trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            } else if (item.isJsonObject()) {
                String name = optString(item.getAsJsonObject(), "name");
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
        }
        return String.join(", ", names);
    }

    @Nullable
    private static String httpGetString(@NonNull String urlStr) {
        byte[] bytes = httpGetBytes(urlStr, MAX_JSON_BYTES);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    @Nullable
    private static byte[] httpGetBytes(@NonNull String urlStr, int maxBytes) {
        Request request = new Request.Builder()
                .url(urlStr)
                .header("Accept", "application/json")
                .header("User-Agent", "EReader-Android/1.0")
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null || body.contentLength() > maxBytes) {
                return null;
            }
            try (InputStream is = body.byteStream()) {
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
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "HTTP GET failed: " + urlStr, e);
            return null;
        }
    }
}
