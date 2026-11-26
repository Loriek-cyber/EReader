package com.logininc.ereader.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.TitleDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Thin facade routing file-import requests to {@link EpubImporter}, {@link PdfImporter},
 * and {@link AudioImporter}, plus shared URI/cover/duration utilities.
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
            return EpubImporter.importEpub(context, uri, displayName, titleDao);
        }
        if (lowerName.endsWith(".pdf") || "application/pdf".equals(lowerMime)) {
            return PdfImporter.importPdf(context, uri, displayName, titleDao);
        }
        if (isAudioFile(displayName) || M4BParser.isM4bOrAudioMime(mime)) {
            return AudioImporter.importAudiobook(context, uri, displayName, titleDao);
        }
        return null;
    }

    @NonNull
    public static List<Chapter> buildEpubChapters(@NonNull io.documentnode.epub4j.domain.Book epubBook) {
        return EpubImporter.buildEpubChapters(epubBook);
    }

    public static int estimateEpubTotalPages(@NonNull List<Chapter> rootChapters) {
        return EpubImporter.estimateEpubTotalPages(rootChapters);
    }

    public static void attachHtmlSectionsIfPresent(
            @NonNull Chapter parent,
            @Nullable String rawHtml,
            @NonNull String href,
            int parentLevel,
            @NonNull int[] indexCounter
    ) {
        EpubImporter.attachHtmlSectionsIfPresent(parent, rawHtml, href, parentLevel, indexCounter);
    }

    @NonNull
    public static String extractReadableText(@Nullable String rawHtml, @Nullable String fragmentId) {
        return EpubImporter.extractReadableText(rawHtml, fragmentId);
    }

    @NonNull
    public static MetadataJsonParser.ApplyResult reloadAudiobookEmbeddedMetadata(
            @NonNull Context context,
            @NonNull AudioBook audioBook
    ) {
        return AudioImporter.reloadAudiobookEmbeddedMetadata(context, audioBook);
    }

    @NonNull
    public static List<Chapter> buildAudiobookChapterTree(
            int titleId,
            @NonNull List<M4BParser.M4bChapter> flatChapters,
            long savedPositionMs
    ) {
        return AudioImporter.buildAudiobookChapterTree(titleId, flatChapters, savedPositionMs);
    }

    public static boolean refreshAudiobookChaptersIfNeeded(@NonNull Context context, @NonNull AudioBook audioBook) {
        return AudioImporter.refreshAudiobookChaptersIfNeeded(context, audioBook);
    }

    @Nullable
    public static Uri saveCoverBytes(@NonNull Context context, @NonNull String key, @NonNull byte[] bytes) {
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
    static String stripExtension(@NonNull String name) {
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
    static String formatDurationLabel(long durationMs) {
        return durationMs > 0L ? formatDuration(durationMs) : "";
    }
}
