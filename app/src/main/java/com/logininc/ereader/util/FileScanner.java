package com.logininc.ereader.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.TitleDao;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.domain.SpineReference;
import io.documentnode.epub4j.domain.TOCReference;

/**
 * Imports selected document URIs, parses EPUBs via {@link EbookParser} ({@code epub4j Book})
 * and M4B/audio files via {@link M4BParser}, and persists them into SQLite.
 */
public final class FileScanner {

    private static final String TAG = "FileScanner";

    private FileScanner() {
    }

    @NonNull
    public static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null && !last.trim().isEmpty() ? last.trim() : "Untitled";
    }

    public static boolean isEbookFile(@Nullable String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".epub") || lower.endsWith(".pdf");
    }

    public static boolean isAudioFile(@Nullable String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m4b")
                || lower.endsWith(".m4a")
                || lower.endsWith(".mp3")
                || lower.endsWith(".aac")
                || lower.endsWith(".ogg")
                || lower.endsWith(".opus")
                || lower.endsWith(".flac");
    }

    @Nullable
    public static Title importSingleUri(@NonNull Context context, @NonNull Uri uri) {
        TitleDao titleDao = new TitleDao(context);
        Title existing = titleDao.findByContentUri(uri);
        if (existing != null) {
            return existing;
        }

        String displayName = queryDisplayName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        String lowerName = displayName.toLowerCase(Locale.ROOT);
        String lowerMime = mime != null ? mime.toLowerCase(Locale.ROOT) : "";

        if (lowerName.endsWith(".epub") || "application/epub+zip".equals(lowerMime)) {
            return importEpub(context, uri, displayName, titleDao);
        }
        if (lowerName.endsWith(".pdf") || "application/pdf".equals(lowerMime)) {
            return importPdf(context, uri, displayName, titleDao);
        }
        if (isAudioFile(displayName) || M4BParser.isM4bOrAudioMime(mime)) {
            return importAudiobook(context, uri, displayName, titleDao);
        }
        return null;
    }

    @Nullable
    private static Book importEpub(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return null;
            }
            io.documentnode.epub4j.domain.Book epubBook = EbookParser.parse(is);

            String titleText = epubBook.getTitle();
            if (titleText == null || titleText.trim().isEmpty()) {
                titleText = stripExtension(displayName);
            } else {
                titleText = titleText.trim();
            }

            String author = extractEpubAuthor(epubBook);
            Uri coverUri = null;
            if (epubBook.getCoverImage() != null && epubBook.getCoverImage().getData() != null) {
                coverUri = saveCoverBytes(context, "epub_" + Math.abs(uri.toString().hashCode()),
                        epubBook.getCoverImage().getData());
            }

            List<Chapter> chapters = buildEpubChapters(epubBook);
            if (chapters.isEmpty()) {
                chapters.add(new Chapter(
                        0, 0, 0, 1, 0, 0,
                        titleText, "", "", "", "",
                        "", "", 0L, 0L, 0, false
                ));
            }

            Book book = new Book(0, titleText, chapters.size(), uri);
            book.setAuthor(author);
            book.setCoverUri(coverUri);
            book.setTotalPages(Math.max(1, chapters.size()));
            book.setChapters(chapters);

            long id = titleDao.saveBookWithChapters(book);
            return id != -1 ? book : null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to import EPUB: " + uri, e);
            return null;
        }
    }

    @NonNull
    private static String extractEpubAuthor(@NonNull io.documentnode.epub4j.domain.Book epubBook) {
        if (epubBook.getMetadata() == null || epubBook.getMetadata().getAuthors() == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Author author : epubBook.getMetadata().getAuthors()) {
            String first = author.getFirstname() != null ? author.getFirstname().trim() : "";
            String last = author.getLastname() != null ? author.getLastname().trim() : "";
            String combined = (first + " " + last).trim();
            if (!combined.isEmpty()) {
                names.add(combined);
            }
        }
        return String.join(", ", names);
    }

    @NonNull
    private static List<Chapter> buildEpubChapters(@NonNull io.documentnode.epub4j.domain.Book epubBook) {
        List<Chapter> roots = new ArrayList<>();
        int[] indexCounter = {1};

        List<TOCReference> tocRefs = epubBook.getTableOfContents() != null
                ? epubBook.getTableOfContents().getTocReferences()
                : null;

        if (tocRefs != null && !tocRefs.isEmpty()) {
            for (int i = 0; i < tocRefs.size(); i++) {
                roots.add(convertTocReference(tocRefs.get(i), 0, i, indexCounter));
            }
            return roots;
        }

        List<SpineReference> spineRefs = epubBook.getSpine() != null
                ? epubBook.getSpine().getSpineReferences()
                : null;
        if (spineRefs != null) {
            for (int i = 0; i < spineRefs.size(); i++) {
                Resource res = spineRefs.get(i).getResource();
                String rawHtml = readResourceHtml(res);
                String plainText = extractReadableText(rawHtml, null);
                String chTitle = (res != null && res.getTitle() != null && !res.getTitle().trim().isEmpty())
                        ? res.getTitle().trim()
                        : "Chapter " + (i + 1);
                String href = res != null && res.getHref() != null ? res.getHref() : "";
                roots.add(new Chapter(
                        0, 0, 0, indexCounter[0]++, 0, i,
                        chTitle, "", href, "", "",
                        plainText, "", 0L, 0L, 0, false
                ));
            }
        }
        return roots;
    }

    @NonNull
    private static Chapter convertTocReference(
            @NonNull TOCReference ref,
            int level,
            int sortOrder,
            @NonNull int[] indexCounter
    ) {
        int chIndex = indexCounter[0]++;
        Resource res = ref.getResource();
        String href = res != null && res.getHref() != null ? res.getHref() : "";
        String fragmentId = ref.getFragmentId() != null ? ref.getFragmentId() : "";
        String rawHtml = readResourceHtml(res);
        String plainText = extractReadableText(rawHtml, fragmentId);
        String chTitle = ref.getTitle() != null && !ref.getTitle().trim().isEmpty()
                ? ref.getTitle().trim()
                : "Chapter " + chIndex;

        Chapter chapter = new Chapter(
                0, 0, 0, chIndex, level, sortOrder,
                chTitle, "", href, fragmentId, "",
                plainText, "", 0L, 0L, 0, false
        );

        List<TOCReference> children = ref.getChildren();
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                chapter.addChild(convertTocReference(children.get(i), level + 1, i, indexCounter));
            }
        }
        return chapter;
    }

    /**
     * Extracts readable plain text from EPUB HTML while preserving paragraph breaks and
     * scoping to {@code fragmentId} when present so sibling subsections do not duplicate the whole chapter.
     */
    @NonNull
    public static String extractReadableText(@Nullable String rawHtml, @Nullable String fragmentId) {
        if (rawHtml == null || rawHtml.trim().isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(rawHtml);
        doc.select("script, style").remove();

        Element root = doc.body();
        if (root == null) {
            return "";
        }

        String scopedHtml = null;
        if (fragmentId != null && !fragmentId.trim().isEmpty()) {
            Element target = doc.getElementById(fragmentId.trim());
            if (target != null) {
                String tag = target.tagName().toLowerCase(Locale.ROOT);
                boolean isHeadingOrAnchor = tag.matches("h[1-6]|a|span");
                if (isHeadingOrAnchor) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(target.outerHtml());
                    Element sibling = target.nextElementSibling();
                    while (sibling != null) {
                        String sibId = sibling.id();
                        String sibTag = sibling.tagName().toLowerCase(Locale.ROOT);
                        if (!sibId.isEmpty() && sibTag.matches("h[1-6]|section|div|article")) {
                            break;
                        }
                        if (tag.matches("h[1-6]") && sibTag.matches("h[1-6]")
                                && sibTag.charAt(1) <= tag.charAt(1)) {
                            break;
                        }
                        sb.append(sibling.outerHtml());
                        sibling = sibling.nextElementSibling();
                    }
                    scopedHtml = sb.toString();
                } else {
                    scopedHtml = target.outerHtml();
                }
            }
        }

        Document workDoc = scopedHtml != null ? Jsoup.parseBodyFragment(scopedHtml) : doc;
        Element workBody = workDoc.body();
        if (workBody == null) {
            return "";
        }
        workBody.select("br").append("%%BR%%");
        workBody.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, div, tr").prepend("%%PARA%%");

        String rawText = workBody.text()
                .replace("%%PARA%%", "\n\n")
                .replace("%%BR%%", "\n");

        String[] lines = rawText.split("\n");
        StringBuilder cleaned = new StringBuilder(rawText.length());
        int consecutiveEmpty = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                consecutiveEmpty++;
                if (cleaned.length() > 0 && consecutiveEmpty == 1) {
                    cleaned.append("\n\n");
                }
            } else {
                if (cleaned.length() > 0 && consecutiveEmpty == 0) {
                    cleaned.append('\n');
                }
                cleaned.append(trimmed);
                consecutiveEmpty = 0;
            }
        }
        return cleaned.toString().trim();
    }

    @NonNull
    private static String readResourceHtml(@Nullable Resource res) {
        if (res == null) {
            return "";
        }
        try {
            byte[] data = res.getData();
            return data != null ? new String(data, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    @Nullable
    private static Book importPdf(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        int pageCount = 1;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    pageCount = Math.max(1, renderer.getPageCount());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inspect PDF page count for " + uri, e);
        }

        String titleText = stripExtension(displayName);
        Book book = new Book(0, titleText, 1, uri);
        book.setTotalPages(pageCount);

        List<Chapter> chapters = new ArrayList<>();
        chapters.add(new Chapter(
                0, 0, 0, 1, 0, 0,
                titleText, "", "", "", pageCount + " p.",
                "", "", 0L, 0L, 0, false
        ));
        book.setChapters(chapters);

        long id = titleDao.saveBookWithChapters(book);
        return id != -1 ? book : null;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Nullable
    private static AudioBook importAudiobook(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        M4BParser.M4bMetadata metadata = null;
        try {
            metadata = new M4BParser().parseM4bMetadata(context, uri).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "M4B metadata extraction failed for " + uri, e);
        }

        String titleText = (metadata != null && !metadata.title.isEmpty())
                ? metadata.title
                : stripExtension(displayName);
        String author = metadata != null ? metadata.author : "";
        String narrator = metadata != null ? metadata.narrator : "";
        long totalDurationMs = metadata != null ? metadata.totalDurationMs : 0L;

        Uri coverUri = null;
        if (metadata != null && metadata.coverArtData != null) {
            coverUri = saveCoverBytes(context, "m4b_" + Math.abs(uri.toString().hashCode()),
                    metadata.coverArtData);
        }

        List<Chapter> chapters = new ArrayList<>();
        if (metadata != null && !metadata.chapters.isEmpty()) {
            for (int i = 0; i < metadata.chapters.size(); i++) {
                M4BParser.M4bChapter ch = metadata.chapters.get(i);
                chapters.add(new Chapter(
                        0, 0, 0, ch.index, 0, i,
                        ch.title, "", "", "", formatDurationLabel(ch.durationMs),
                        "", "", ch.startMs, ch.durationMs, 0, false
                ));
            }
        } else {
            chapters.add(new Chapter(
                    0, 0, 0, 1, 0, 0,
                    titleText, "", "", "", formatDurationLabel(totalDurationMs),
                    "", "", 0L, totalDurationMs, 0, false
            ));
        }

        AudioBook audioBook = new AudioBook(0, titleText, chapters.size(), uri);
        audioBook.setAuthor(author);
        audioBook.setNarrator(narrator);
        audioBook.setTotalDurationMs(totalDurationMs);
        audioBook.setCoverUri(coverUri);

        long id = titleDao.insert(audioBook);
        if (id == -1) {
            return null;
        }
        new ChapterDao(context).replaceChapterTree((int) id, chapters);
        return audioBook;
    }

    @Nullable
    private static Uri saveCoverBytes(@NonNull Context context, @NonNull String key, @NonNull byte[] bytes) {
        File dir = new File(context.getFilesDir(), "covers");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File outFile = new File(dir, key + ".jpg");
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                int inSampleSize = 1;
                int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                while ((maxDim / inSampleSize) > 512) {
                    inSampleSize *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = inSampleSize;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bmp != null) {
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    } finally {
                        bmp.recycle();
                    }
                    return Uri.fromFile(outFile);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
                return Uri.fromFile(outFile);
            }
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "Failed to write cover image", e);
            return null;
        }
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @NonNull
    public static String formatDuration(long durationMs) {
        long totalSec = Math.max(0L, durationMs) / 1000L;
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.ROOT, "%d:%02d", mins, secs);
    }

    @NonNull
    public static String queryReadablePathLabel(@Nullable Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) {
            return "";
        }
        String last = uri.getLastPathSegment();
        if (last != null && !last.trim().isEmpty()) {
            int colon = last.lastIndexOf(':');
            return colon >= 0 && colon + 1 < last.length()
                    ? last.substring(colon + 1)
                    : last;
        }
        return uri.toString();
    }

    @NonNull
    private static String formatDurationLabel(long durationMs) {
        if (durationMs <= 0L) {
            return "";
        }
        long totalSec = durationMs / 1000L;
        long mins = totalSec / 60L;
        long secs = totalSec % 60L;
        return String.format(Locale.ROOT, "%d:%02d", mins, secs);
    }
}
