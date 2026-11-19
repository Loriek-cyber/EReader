package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.DatabaseContract.TitleEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object (DAO) for persisting and querying {@link Title} entities in SQLite.
 */
public class TitleDao {

    private final EReaderDbHelper dbHelper;
    private final ChapterDao chapterDao;

    public TitleDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public TitleDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
        this.chapterDao = new ChapterDao(dbHelper);
    }

    /**
     * Inserts a {@link Title} ({@link Book} or {@link AudioBook}) into the database.
     * Updates the object's id with the generated row ID when insertion succeeds.
     *
     * @return the newly inserted row ID, or {@code -1} if an error occurred.
     */
    public long insert(@NonNull Title title) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = db.insert(TitleEntry.TABLE_NAME, null, toContentValues(title));
        if (rowId != -1) {
            title.setId((int) rowId);
        }
        return rowId;
    }

    /**
     * Atomically inserts or updates a {@link Book} together with its entire hierarchical
     * {@link Book#getChapters()} tree (chapters, sections, subsections) inside a single transaction.
     *
     * @return the persisted book row ID, or {@code -1} if saving failed.
     */
    public long saveBookWithChapters(@NonNull Book book) {
        List<Chapter> flattened = book.getFlattenedChapters();
        if (!flattened.isEmpty()) {
            book.setLenChapters(flattened.size());
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (book.getId() > 0) {
                int updated = db.update(
                        TitleEntry.TABLE_NAME,
                        toContentValues(book),
                        TitleEntry._ID + " = ?",
                        new String[]{String.valueOf(book.getId())}
                );
                if (updated == 0) {
                    long rowId = db.insert(TitleEntry.TABLE_NAME, null, toContentValues(book));
                    if (rowId == -1) {
                        return -1;
                    }
                    book.setId((int) rowId);
                }
            } else {
                long rowId = db.insert(TitleEntry.TABLE_NAME, null, toContentValues(book));
                if (rowId == -1) {
                    return -1;
                }
                book.setId((int) rowId);
            }

            chapterDao.replaceChapterTree(db, book.getId(), book.getChapters());
            db.setTransactionSuccessful();
            return book.getId();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Loads a {@link Book} by ID together with its reconstructed hierarchical chapter/section tree.
     */
    @Nullable
    public Book getBookWithChapters(int bookId) {
        Title loaded = getById(bookId);
        if (!(loaded instanceof Book)) {
            return null;
        }
        Book book = (Book) loaded;
        book.setChapters(chapterDao.getChapterTreeForTitle(book.getId()));
        return book;
    }

    /**
     * Loads a {@link Book} by its persisted document URI together with its reconstructed
     * hierarchical chapter/section tree.
     */
    @Nullable
    public Book findBookWithChaptersByContentUri(@NonNull Uri uri) {
        Title loaded = findByContentUri(uri);
        if (!(loaded instanceof Book)) {
            return null;
        }
        Book book = (Book) loaded;
        book.setChapters(chapterDao.getChapterTreeForTitle(book.getId()));
        return book;
    }

    /**
     * Finds a {@link Title} by its database ID.
     */
    @Nullable
    public Title getById(int id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String selection = TitleEntry._ID + " = ?";
        String[] selectionArgs = {String.valueOf(id)};

        try (Cursor cursor = db.query(
                TitleEntry.TABLE_NAME,
                null,
                selection,
                selectionArgs,
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
            return null;
        }
    }

    /**
     * Finds an existing title by its persisted document URI string.
     */
    @Nullable
    public Title findByContentUri(@NonNull Uri uri) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String selection = TitleEntry.COLUMN_CONTENT_URI + " = ?";
        String[] selectionArgs = {uri.toString()};

        try (Cursor cursor = db.query(
                TitleEntry.TABLE_NAME,
                null,
                selection,
                selectionArgs,
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
            return null;
        }
    }

    /**
     * Returns all stored titles ordered by title ascending.
     */
    @NonNull
    public List<Title> getAll() {
        return queryTitles(null, null, TitleEntry.COLUMN_TITLE + " ASC");
    }

    /**
     * Returns all stored {@link Book} entries ordered by last opened desc then title asc.
     */
    @NonNull
    public List<Book> getAllBooks() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ?",
                new String[]{TitleEntry.TYPE_BOOK},
                TitleEntry.COLUMN_LAST_OPENED_AT + " DESC, " + TitleEntry.COLUMN_TITLE + " ASC"
        );
        List<Book> books = new ArrayList<>(titles.size());
        for (Title title : titles) {
            if (title instanceof Book) {
                books.add((Book) title);
            }
        }
        return books;
    }

    /**
     * Returns all stored {@link AudioBook} entries ordered by last opened desc then title asc.
     */
    @NonNull
    public List<AudioBook> getAllAudioBooks() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ?",
                new String[]{TitleEntry.TYPE_AUDIOBOOK},
                TitleEntry.COLUMN_LAST_OPENED_AT + " DESC, " + TitleEntry.COLUMN_TITLE + " ASC"
        );
        List<AudioBook> audioBooks = new ArrayList<>(titles.size());
        for (Title title : titles) {
            if (title instanceof AudioBook) {
                audioBooks.add((AudioBook) title);
            }
        }
        return audioBooks;
    }

    /**
     * Returns the most recently opened in-progress Book, or null if none is in progress.
     */
    @Nullable
    public Book getMostRecentInProgressBook() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ? AND " + TitleEntry.COLUMN_STATUS + " = ?",
                new String[]{TitleEntry.TYPE_BOOK, Title.STATUS_IN_PROGRESS},
                TitleEntry.COLUMN_LAST_OPENED_AT + " DESC LIMIT 1"
        );
        if (!titles.isEmpty() && titles.get(0) instanceof Book) {
            return (Book) titles.get(0);
        }
        return null;
    }

    /**
     * Returns the most recently opened in-progress AudioBook, or null if none is in progress.
     */
    @Nullable
    public AudioBook getMostRecentInProgressAudioBook() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ? AND " + TitleEntry.COLUMN_STATUS + " = ?",
                new String[]{TitleEntry.TYPE_AUDIOBOOK, Title.STATUS_IN_PROGRESS},
                TitleEntry.COLUMN_LAST_OPENED_AT + " DESC LIMIT 1"
        );
        if (!titles.isEmpty() && titles.get(0) instanceof AudioBook) {
            return (AudioBook) titles.get(0);
        }
        return null;
    }

    /**
     * Searches titles of a given type matching title or author.
     */
    @NonNull
    public List<Title> searchByTypeAndQuery(@NonNull String type, @NonNull String query) {
        String like = "%" + query + "%";
        return queryTitles(
                TitleEntry.COLUMN_TYPE + " = ? AND ("
                        + TitleEntry.COLUMN_TITLE + " LIKE ? OR "
                        + TitleEntry.COLUMN_AUTHOR + " LIKE ? OR "
                        + TitleEntry.COLUMN_NARRATOR + " LIKE ?)",
                new String[]{type, like, like, like},
                TitleEntry.COLUMN_LAST_OPENED_AT + " DESC, " + TitleEntry.COLUMN_TITLE + " ASC"
        );
    }

    /**
     * Searches titles whose name contains {@code query}.
     */
    @NonNull
    public List<Title> searchByTitle(@NonNull String query) {
        return queryTitles(
                TitleEntry.COLUMN_TITLE + " LIKE ?",
                new String[]{"%" + query + "%"},
                TitleEntry.COLUMN_TITLE + " ASC"
        );
    }

    /**
     * Updates an existing {@link Title} matched by its ID.
     */
    public int update(@NonNull Title title) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = TitleEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(title.getId())};
        return db.update(TitleEntry.TABLE_NAME, toContentValues(title), whereClause, whereArgs);
    }

    /**
     * Returns the count of stored titles of the given type ({@link TitleEntry#TYPE_BOOK} or {@link TitleEntry#TYPE_AUDIOBOOK}).
     */
    public int countByType(@NonNull String type) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(
                db,
                TitleEntry.TABLE_NAME,
                TitleEntry.COLUMN_TYPE + " = ?",
                new String[]{type}
        );
    }

    /**
     * Deletes a {@link Title} by its ID, also removing any local cover image file owned by it.
     */
    public int deleteById(int id) {
        Title existing = getById(id);
        if (existing != null && existing.getCoverUri() != null
                && "file".equals(existing.getCoverUri().getScheme())) {
            String path = existing.getCoverUri().getPath();
            if (path != null && !path.isEmpty()) {
                File coverFile = new File(path);
                if (coverFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    coverFile.delete();
                }
            }
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = TitleEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        return db.delete(TitleEntry.TABLE_NAME, whereClause, whereArgs);
    }

    /**
     * Deletes all rows from the {@code titles} table.
     */
    public int deleteAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(TitleEntry.TABLE_NAME, null, null);
    }

    @NonNull
    private List<Title> queryTitles(
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String orderBy
    ) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Title> results = new ArrayList<>();

        try (Cursor cursor = db.query(
                TitleEntry.TABLE_NAME,
                null,
                selection,
                selectionArgs,
                null,
                null,
                orderBy
        )) {
            while (cursor.moveToNext()) {
                results.add(fromCursor(cursor));
            }
        }
        return results;
    }

    @NonNull
    private ContentValues toContentValues(@NonNull Title title) {
        ContentValues values = new ContentValues();
        values.put(TitleEntry.COLUMN_TITLE, title.getTitle());
        values.put(TitleEntry.COLUMN_AUTHOR, title.getAuthor());
        values.put(TitleEntry.COLUMN_LEN_CHAPTERS, title.getLenChapters());
        values.put(TitleEntry.COLUMN_CURRENT_CHAPTER, title.getCurrentChapter());
        values.put(TitleEntry.COLUMN_CURRENT_CHAPTER_COMPLETED, title.isCurrentChapterCompleted() ? 1 : 0);
        values.put(TitleEntry.COLUMN_PROGRESS_PERCENT, title.getProgressPercent());

        Uri contentUri = title.getContentUri();
        values.put(TitleEntry.COLUMN_CONTENT_URI, contentUri != null ? contentUri.toString() : "");
        Uri coverUri = title.getCoverUri();
        values.put(TitleEntry.COLUMN_COVER_URI, coverUri != null ? coverUri.toString() : "");

        values.put(TitleEntry.COLUMN_TYPE, resolveType(title));
        values.put(TitleEntry.COLUMN_STATUS, title.getStatus());
        values.put(TitleEntry.COLUMN_LAST_OPENED_AT, title.getLastOpenedAt());

        if (title instanceof Book) {
            Book book = (Book) title;
            values.put(TitleEntry.COLUMN_NARRATOR, "");
            values.put(TitleEntry.COLUMN_CURRENT_PAGE, book.getCurrentPage());
            values.put(TitleEntry.COLUMN_TOTAL_PAGES, book.getTotalPages());
            values.put(TitleEntry.COLUMN_CURRENT_POSITION_MS, 0L);
            values.put(TitleEntry.COLUMN_TOTAL_DURATION_MS, 0L);
            values.put(TitleEntry.COLUMN_SCROLL_Y, book.getScrollY());
            values.put(TitleEntry.COLUMN_SCROLL_MAX_HEIGHT, book.getScrollMaxHeight());
            values.put(TitleEntry.COLUMN_VIEWPORT_HEIGHT, book.getViewportHeight());
            values.put(TitleEntry.COLUMN_TEXT_SIZE_SP, book.getTextSizeSp());
            values.put(TitleEntry.COLUMN_CHAR_OFFSET, book.getCharOffset());
            values.put(TitleEntry.COLUMN_SCROLL_PERMILLE, book.getScrollPermille());
        } else if (title instanceof AudioBook) {
            AudioBook audioBook = (AudioBook) title;
            values.put(TitleEntry.COLUMN_NARRATOR, audioBook.getNarrator());
            values.put(TitleEntry.COLUMN_CURRENT_PAGE, 1);
            values.put(TitleEntry.COLUMN_TOTAL_PAGES, 1);
            values.put(TitleEntry.COLUMN_CURRENT_POSITION_MS, audioBook.getCurrentPositionMs());
            values.put(TitleEntry.COLUMN_TOTAL_DURATION_MS, audioBook.getTotalDurationMs());
            values.put(TitleEntry.COLUMN_SCROLL_Y, 0);
            values.put(TitleEntry.COLUMN_SCROLL_MAX_HEIGHT, 0);
            values.put(TitleEntry.COLUMN_VIEWPORT_HEIGHT, 0);
            values.put(TitleEntry.COLUMN_TEXT_SIZE_SP, 18);
            values.put(TitleEntry.COLUMN_CHAR_OFFSET, 0);
            values.put(TitleEntry.COLUMN_SCROLL_PERMILLE, 0);
        }
        return values;
    }

    @NonNull
    private String resolveType(@NonNull Title title) {
        if (title instanceof AudioBook) {
            return TitleEntry.TYPE_AUDIOBOOK;
        }
        if (title instanceof Book) {
            return TitleEntry.TYPE_BOOK;
        }
        throw new IllegalArgumentException("Unsupported Title subtype: " + title.getClass().getName());
    }

    @NonNull
    private Title fromCursor(@NonNull Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry._ID));
        String titleText = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_TITLE));
        String author = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_AUTHOR));
        String narrator = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_NARRATOR));
        int lenChapters = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_LEN_CHAPTERS));
        int currentChapter = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CURRENT_CHAPTER));
        boolean chapterCompleted = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CURRENT_CHAPTER_COMPLETED)) == 1;
        int progressPercent = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_PROGRESS_PERCENT));
        int currentPage = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CURRENT_PAGE));
        int totalPages = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_TOTAL_PAGES));
        long currentPositionMs = cursor.getLong(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CURRENT_POSITION_MS));
        long totalDurationMs = cursor.getLong(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_TOTAL_DURATION_MS));
        String uriString = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CONTENT_URI));
        String coverString = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_COVER_URI));
        String type = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_TYPE));
        String status = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_STATUS));
        long lastOpenedAt = cursor.getLong(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_LAST_OPENED_AT));

        Uri contentUri = (uriString != null && !uriString.isEmpty()) ? Uri.parse(uriString) : Uri.EMPTY;
        Uri coverUri = (coverString != null && !coverString.isEmpty()) ? Uri.parse(coverString) : null;

        Title result;
        if (TitleEntry.TYPE_AUDIOBOOK.equals(type)) {
            AudioBook audioBook = new AudioBook(id, titleText, lenChapters, contentUri);
            audioBook.setNarrator(narrator);
            audioBook.setCurrentPositionMs(currentPositionMs);
            audioBook.setTotalDurationMs(totalDurationMs);
            result = audioBook;
        } else {
            Book book = new Book(id, titleText, lenChapters, contentUri);
            book.setCurrentPage(currentPage);
            book.setTotalPages(totalPages);
            book.setScrollY(getOptionalInt(cursor, TitleEntry.COLUMN_SCROLL_Y, 0));
            book.setScrollMaxHeight(getOptionalInt(cursor, TitleEntry.COLUMN_SCROLL_MAX_HEIGHT, 0));
            book.setViewportHeight(getOptionalInt(cursor, TitleEntry.COLUMN_VIEWPORT_HEIGHT, 0));
            book.setTextSizeSp(getOptionalInt(cursor, TitleEntry.COLUMN_TEXT_SIZE_SP, 18));
            book.setCharOffset(getOptionalInt(cursor, TitleEntry.COLUMN_CHAR_OFFSET, 0));
            book.setScrollPermille(getOptionalInt(cursor, TitleEntry.COLUMN_SCROLL_PERMILLE, 0));
            result = book;
        }

        result.setAuthor(author);
        result.setCurrentChapter(currentChapter);
        result.setCurrentChapterCompleted(chapterCompleted);
        result.setProgressPercent(progressPercent);
        result.setCoverUri(coverUri);
        result.setStatus(status);
        result.setLastOpenedAt(lastOpenedAt);
        return result;
    }

    private int getOptionalInt(@NonNull Cursor cursor, @NonNull String columnName, int defaultValue) {
        int idx = cursor.getColumnIndex(columnName);
        return idx >= 0 && !cursor.isNull(idx) ? cursor.getInt(idx) : defaultValue;
    }
}
