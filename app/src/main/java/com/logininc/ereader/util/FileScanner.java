package com.logininc.ereader.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.TitleDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans SAF folders or selected document URIs, parses real book/audiobook metadata and chapters,
 * and persists them into SQLite without creating synthetic/fake data.
 */
public final class FileScanner {

    public static final class ScanResult {
        public final int importedCount;
        public final int existingCount;
        public final long totalBytes;

        public ScanResult(int importedCount, int existingCount, long totalBytes) {
            this.importedCount = importedCount;
            this.existingCount = existingCount;
            this.totalBytes = totalBytes;
        }
    }

    private static final int MAX_SCAN_DEPTH = 3;
    private static final long AUDIO_SEGMENT_MS = 10 * 60 * 1000L; // 10 min per chapter for long single-file audiobooks

    private FileScanner() {
    }

    public static boolean isEbookFile(@NonNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".epub") || lower.endsWith(".pdf") || lower.endsWith(".txt");
    }

    public static boolean isAudioFile(@NonNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".m4b")
                || lower.endsWith(".m4a")
                || lower.endsWith(".ogg")
                || lower.endsWith(".wav")
                || lower.endsWith(".flac")
                || lower.endsWith(".aac");
    }

    @NonNull
    public static ScanResult scanTree(@NonNull Context context, @NonNull Uri treeUri, boolean expectAudio) throws Exception {
        PermissionHelper.takePersistableReadPermission(context, treeUri);
        List<ScannedDoc> docs = new ArrayList<>();
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        collectTreeDocs(context, treeUri, rootDocId, 0, expectAudio, docs);

        TitleDao titleDao = new TitleDao(context);
        ChapterDao chapterDao = new ChapterDao(context);

        int imported = 0;
        int existing = 0;
        long totalBytes = 0L;

        for (ScannedDoc doc : docs) {
            totalBytes += Math.max(0L, doc.sizeBytes);
            Title found = titleDao.findByContentUri(doc.uri);
            if (found != null) {
                existing++;
                continue;
            }
            Title created = expectAudio
                    ? importAudioDocument(context, doc.uri, doc.displayName, titleDao, chapterDao)
                    : importEbookDocument(context, doc.uri, doc.displayName, titleDao);
            if (created != null) {
                imported++;
            }
        }
        return new ScanResult(imported, existing, totalBytes);
    }

    @Nullable
    public static Title importSingleUri(@NonNull Context context, @NonNull Uri uri) {
        PermissionHelper.takePersistableReadPermission(context, uri);
        TitleDao titleDao = new TitleDao(context);
        Title existing = titleDao.findByContentUri(uri);
        if (existing != null) {
            return existing;
        }

        String displayName = queryDisplayName(context, uri);
        String mimeType = context.getContentResolver().getType(uri);
        boolean isAudio = isAudioFile(displayName) || M4bParser.isM4bOrAudioMime(mimeType);

        if (isAudio) {
            ChapterDao chapterDao = new ChapterDao(context);
            return importAudioDocument(context, uri, displayName, titleDao, chapterDao);
        }
        try {
            return importEbookDocument(context, uri, displayName, titleDao);
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectTreeDocs(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @NonNull String parentDocId,
            int depth,
            boolean expectAudio,
            @NonNull List<ScannedDoc> out
    ) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0L : cursor.getLong(3);

                if (docId == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectTreeDocs(context, treeUri, docId, depth + 1, expectAudio, out);
                } else {
                    boolean match = expectAudio
                            ? (isAudioFile(name) || M4bParser.isM4bOrAudioMime(mime))
                            : isEbookFile(name);
                    if (match) {
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        out.add(new ScannedDoc(docUri, name, size));
                    }
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    @Nullable
    private static Book importEbookDocument(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) throws Exception {
        Book book = EbookParserV2.parseToBook(context, uri, stripExtension(displayName));
        long id = EbookParserV2.saveBook(context, book, titleDao);
        if (id == -1) {
            return null;
        }
        return book;
    }

    @Nullable
    private static AudioBook importAudioDocument(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao,
            @NonNull ChapterDao chapterDao
    ) {
        M4bParser.M4bMetadata m4bMeta = M4bParser.parse(context, uri);

        String fallbackTitle = stripExtension(displayName);
        String metaTitle = "";
        String metaAlbum = "";
        String metaArtist = "";
        String metaComposer = "";
        long durationMs = 0L;
        byte[] coverBytes = null;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            boolean dataSourceSet = false;
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    retriever.setDataSource(pfd.getFileDescriptor());
                    dataSourceSet = true;
                }
            } catch (IOException | RuntimeException ignored) {
            }
            if (!dataSourceSet) {
                retriever.setDataSource(context, uri);
            }

            metaTitle = safeTrim(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            metaAlbum = safeTrim(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            metaArtist = safeTrim(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            metaComposer = safeTrim(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER));
            String metaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (metaDuration != null) {
                try {
                    durationMs = Long.parseLong(metaDuration.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            coverBytes = retriever.getEmbeddedPicture();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }

        String titleName = firstNonEmpty(m4bMeta.album, metaAlbum, m4bMeta.title, metaTitle, fallbackTitle);
        String author = firstNonEmpty(metaArtist, m4bMeta.author);
        String narrator = firstNonEmpty(m4bMeta.narrator, metaComposer);
        if (durationMs <= 0L) {
            durationMs = m4bMeta.durationMs;
        }
        if ((coverBytes == null || coverBytes.length == 0) && m4bMeta.coverBytes != null) {
            coverBytes = m4bMeta.coverBytes;
        }
        Uri coverUri = (coverBytes != null && coverBytes.length > 0)
                ? saveCoverBytes(context, uri.toString().hashCode(), coverBytes)
                : null;

        List<Chapter> chapters = buildChaptersFromM4bOrFallback(titleName, durationMs, m4bMeta.chapters);

        AudioBook audioBook = new AudioBook(0, titleName, chapters.size(), uri);
        audioBook.setAuthor(author);
        audioBook.setNarrator(narrator);
        audioBook.setTotalDurationMs(durationMs);
        audioBook.setCurrentPositionMs(0L);
        audioBook.setCoverUri(coverUri);
        audioBook.setStatus(Title.STATUS_TO_START);

        long id = titleDao.insert(audioBook);
        if (id == -1) {
            return null;
        }
        chapterDao.replaceChapters(audioBook.getId(), chapters);
        return audioBook;
    }

    @NonNull
    private static List<Chapter> buildChaptersFromM4bOrFallback(
            @NonNull String titleName,
            long totalDurationMs,
            @NonNull List<M4bParser.M4bChapter> m4bChapters
    ) {
        if (m4bChapters.isEmpty()) {
            return buildAudioChapters(titleName, totalDurationMs);
        }
        List<Chapter> chapters = new ArrayList<>(m4bChapters.size());
        for (int i = 0; i < m4bChapters.size(); i++) {
            M4bParser.M4bChapter src = m4bChapters.get(i);
            long dur = src.durationMs;
            if (dur <= 0L) {
                if (i + 1 < m4bChapters.size()) {
                    dur = Math.max(0L, m4bChapters.get(i + 1).startMs - src.startMs);
                } else if (totalDurationMs > src.startMs) {
                    dur = totalDurationMs - src.startMs;
                }
            }
            String durLabel = formatDuration(dur);
            chapters.add(new Chapter(
                    0,
                    0,
                    i + 1,
                    src.title,
                    durLabel,
                    durLabel,
                    "",
                    src.startMs,
                    dur,
                    0,
                    false
            ));
        }
        return chapters;
    }

    @NonNull
    private static String safeTrim(@Nullable String value) {
        return value != null ? value.trim() : "";
    }

    @NonNull
    private static String firstNonEmpty(@NonNull String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    @NonNull
    private static List<Chapter> buildAudioChapters(@NonNull String bookTitle, long totalDurationMs) {
        List<Chapter> chapters = new ArrayList<>();
        if (totalDurationMs <= AUDIO_SEGMENT_MS) {
            chapters.add(new Chapter(
                    0, 0, 1,
                    bookTitle,
                    formatDuration(totalDurationMs),
                    formatDuration(totalDurationMs),
                    "",
                    0L,
                    totalDurationMs,
                    0,
                    false
            ));
            return chapters;
        }
        int count = (int) Math.ceil((double) totalDurationMs / AUDIO_SEGMENT_MS);
        for (int i = 0; i < count; i++) {
            long start = i * AUDIO_SEGMENT_MS;
            long dur = Math.min(AUDIO_SEGMENT_MS, totalDurationMs - start);
            chapters.add(new Chapter(
                    0, 0, i + 1,
                    "Part " + (i + 1),
                    formatDuration(dur),
                    formatDuration(dur),
                    "",
                    start,
                    dur,
                    0,
                    false
            ));
        }
        return chapters;
    }

    @Nullable
    static Uri saveCoverBytes(@NonNull Context context, int hash, @NonNull byte[] bytes) {
        File coversDir = new File(context.getFilesDir(), "covers");
        if (!coversDir.exists() && !coversDir.mkdirs()) {
            return null;
        }
        File outFile = new File(coversDir, "cover_" + Math.abs(hash) + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(bytes);
            return Uri.fromFile(outFile);
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    public static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "Document";
    }

    @NonNull
    public static String queryReadablePathLabel(@NonNull Uri uri) {
        String seg = uri.getLastPathSegment();
        if (seg == null) {
            return uri.toString();
        }
        int colon = seg.indexOf(':');
        if (colon >= 0 && colon + 1 < seg.length()) {
            return "/" + seg.substring(colon + 1);
        }
        return seg;
    }

    @NonNull
    public static String formatDuration(long durationMs) {
        long totalSec = Math.max(0L, durationMs / 1000L);
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    @NonNull
    private static String stripExtension(@NonNull String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static final class ScannedDoc {
        final Uri uri;
        final String displayName;
        final long sizeBytes;

        ScannedDoc(Uri uri, String displayName, long sizeBytes) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }
}
