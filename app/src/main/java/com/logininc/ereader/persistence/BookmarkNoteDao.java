package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.logininc.ereader.model.BookmarkNote;
import com.logininc.ereader.persistence.DatabaseContract.BookmarkNoteEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * DAO for persisting bookmarks and notes linked to a {@link com.logininc.ereader.model.Title}.
 */
public class BookmarkNoteDao {

    private final EReaderDbHelper dbHelper;

    public BookmarkNoteDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public BookmarkNoteDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(@NonNull BookmarkNote item) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(BookmarkNoteEntry.COLUMN_TITLE_ID, item.getTitleId());
        values.put(BookmarkNoteEntry.COLUMN_CHAPTER_INDEX, item.getChapterIndex());
        values.put(BookmarkNoteEntry.COLUMN_ENTRY_TYPE, item.getEntryType());
        values.put(BookmarkNoteEntry.COLUMN_POSITION_LABEL, item.getPositionLabel());
        values.put(BookmarkNoteEntry.COLUMN_NOTE_TEXT, item.getNoteText());
        values.put(BookmarkNoteEntry.COLUMN_CREATED_AT, item.getCreatedAt());
        long rowId = db.insert(BookmarkNoteEntry.TABLE_NAME, null, values);
        if (rowId != -1) {
            item.setId((int) rowId);
        }
        return rowId;
    }

    @NonNull
    public List<BookmarkNote> getByTitleAndType(int titleId, @NonNull String entryType) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<BookmarkNote> results = new ArrayList<>();
        try (Cursor cursor = db.query(
                BookmarkNoteEntry.TABLE_NAME,
                null,
                BookmarkNoteEntry.COLUMN_TITLE_ID + " = ? AND " + BookmarkNoteEntry.COLUMN_ENTRY_TYPE + " = ?",
                new String[]{String.valueOf(titleId), entryType},
                null,
                null,
                BookmarkNoteEntry.COLUMN_CREATED_AT + " DESC"
        )) {
            while (cursor.moveToNext()) {
                results.add(new BookmarkNote(
                        cursor.getInt(cursor.getColumnIndexOrThrow(BookmarkNoteEntry._ID)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_TITLE_ID)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_CHAPTER_INDEX)),
                        cursor.getString(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_ENTRY_TYPE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_POSITION_LABEL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_NOTE_TEXT)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(BookmarkNoteEntry.COLUMN_CREATED_AT))
                ));
            }
        }
        return results;
    }

    public int countByTitleAndType(int titleId, @NonNull String entryType) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(
                db,
                BookmarkNoteEntry.TABLE_NAME,
                BookmarkNoteEntry.COLUMN_TITLE_ID + " = ? AND " + BookmarkNoteEntry.COLUMN_ENTRY_TYPE + " = ?",
                new String[]{String.valueOf(titleId), entryType}
        );
    }

    public int deleteById(int id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(BookmarkNoteEntry.TABLE_NAME,
                BookmarkNoteEntry._ID + " = ?",
                new String[]{String.valueOf(id)});
    }
}
