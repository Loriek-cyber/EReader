package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.DatabaseContract.TitleEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object (DAO) for persisting and querying {@link Title} entities in SQLite.
 */
public class TitleDao {

    private final EReaderDbHelper dbHelper;

    public TitleDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public TitleDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
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
     * Finds a {@link Title} by its database ID.
     *
     * @return the matching {@link Book} or {@link AudioBook}, or {@code null} if not found.
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
     * Returns all stored titles ordered by title ascending.
     */
    @NonNull
    public List<Title> getAll() {
        return queryTitles(null, null, TitleEntry.COLUMN_TITLE + " ASC");
    }

    /**
     * Returns all stored {@link Book} entries ordered by title ascending.
     */
    @NonNull
    public List<Book> getAllBooks() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ?",
                new String[]{TitleEntry.TYPE_BOOK},
                TitleEntry.COLUMN_TITLE + " ASC"
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
     * Returns all stored {@link AudioBook} entries ordered by title ascending.
     */
    @NonNull
    public List<AudioBook> getAllAudioBooks() {
        List<Title> titles = queryTitles(
                TitleEntry.COLUMN_TYPE + " = ?",
                new String[]{TitleEntry.TYPE_AUDIOBOOK},
                TitleEntry.COLUMN_TITLE + " ASC"
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
     * Searches titles whose name contains {@code query} (case-insensitive in ASCII).
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
     *
     * @return the number of rows affected.
     */
    public int update(@NonNull Title title) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = TitleEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(title.getId())};
        return db.update(TitleEntry.TABLE_NAME, toContentValues(title), whereClause, whereArgs);
    }

    /**
     * Deletes a {@link Title} by its ID.
     *
     * @return the number of rows deleted.
     */
    public int deleteById(int id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String whereClause = TitleEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        return db.delete(TitleEntry.TABLE_NAME, whereClause, whereArgs);
    }

    /**
     * Deletes all rows from the {@code titles} table.
     *
     * @return the number of rows deleted.
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
        values.put(TitleEntry.COLUMN_LEN_CHAPTERS, title.getLenChapters());
        Uri uri = title.getContentUri();
        values.put(TitleEntry.COLUMN_CONTENT_URI, uri != null ? uri.toString() : "");
        values.put(TitleEntry.COLUMN_TYPE, resolveType(title));
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
        int lenChapters = cursor.getInt(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_LEN_CHAPTERS));
        String uriString = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_CONTENT_URI));
        String type = cursor.getString(cursor.getColumnIndexOrThrow(TitleEntry.COLUMN_TYPE));

        Uri contentUri = (uriString != null && !uriString.isEmpty()) ? Uri.parse(uriString) : Uri.EMPTY;

        if (TitleEntry.TYPE_AUDIOBOOK.equals(type)) {
            return new AudioBook(id, titleText, lenChapters, contentUri);
        }
        return new Book(id, titleText, lenChapters, contentUri);
    }
}
