package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.DatabaseContract.ChapterEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * DAO for persisting and querying {@link Chapter} rows in SQLite.
 */
public class ChapterDao {

    private final EReaderDbHelper dbHelper;

    public ChapterDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public ChapterDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Replaces all chapters for {@code titleId} inside a transaction.
     */
    public void replaceChapters(int titleId, @NonNull List<Chapter> chapters) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(ChapterEntry.TABLE_NAME, ChapterEntry.COLUMN_TITLE_ID + " = ?",
                    new String[]{String.valueOf(titleId)});
            for (Chapter chapter : chapters) {
                chapter.setTitleId(titleId);
                long id = db.insert(ChapterEntry.TABLE_NAME, null, toContentValues(chapter));
                if (id != -1) {
                    chapter.setId((int) id);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @NonNull
    public List<Chapter> getChaptersForTitle(int titleId) {
        return queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ?",
                new String[]{String.valueOf(titleId)}
        );
    }

    @NonNull
    public List<Chapter> searchChapters(int titleId, @NonNull String query) {
        String like = "%" + query + "%";
        return queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND ("
                        + ChapterEntry.COLUMN_CHAPTER_TITLE + " LIKE ? OR "
                        + ChapterEntry.COLUMN_SUBTITLE + " LIKE ?)",
                new String[]{String.valueOf(titleId), like, like}
        );
    }

    @Nullable
    public Chapter getChapter(int titleId, int chapterIndex) {
        List<Chapter> rows = queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_CHAPTER_INDEX + " = ?",
                new String[]{String.valueOf(titleId), String.valueOf(chapterIndex)}
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateChapterProgress(int titleId, int chapterIndex, int progressPercent, boolean completed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(ChapterEntry.COLUMN_PROGRESS_PERCENT, Math.max(0, Math.min(100, progressPercent)));
        values.put(ChapterEntry.COLUMN_IS_COMPLETED, completed ? 1 : 0);
        db.update(
                ChapterEntry.TABLE_NAME,
                values,
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_CHAPTER_INDEX + " = ?",
                new String[]{String.valueOf(titleId), String.valueOf(chapterIndex)}
        );
    }

    @NonNull
    private List<Chapter> queryChapters(@NonNull String selection, @NonNull String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Chapter> results = new ArrayList<>();
        try (Cursor cursor = db.query(
                ChapterEntry.TABLE_NAME,
                null,
                selection,
                selectionArgs,
                null,
                null,
                ChapterEntry.COLUMN_CHAPTER_INDEX + " ASC"
        )) {
            while (cursor.moveToNext()) {
                results.add(fromCursor(cursor));
            }
        }
        return results;
    }

    @NonNull
    private ContentValues toContentValues(@NonNull Chapter chapter) {
        ContentValues values = new ContentValues();
        values.put(ChapterEntry.COLUMN_TITLE_ID, chapter.getTitleId());
        values.put(ChapterEntry.COLUMN_CHAPTER_INDEX, chapter.getChapterIndex());
        values.put(ChapterEntry.COLUMN_CHAPTER_TITLE, chapter.getChapterTitle());
        values.put(ChapterEntry.COLUMN_SUBTITLE, chapter.getSubtitle());
        values.put(ChapterEntry.COLUMN_PAGE_OR_DURATION, chapter.getPageOrDurationLabel());
        values.put(ChapterEntry.COLUMN_CONTENT_TEXT, chapter.getContentText());
        values.put(ChapterEntry.COLUMN_AUDIO_START_MS, chapter.getAudioStartMs());
        values.put(ChapterEntry.COLUMN_AUDIO_DURATION_MS, chapter.getAudioDurationMs());
        values.put(ChapterEntry.COLUMN_PROGRESS_PERCENT, chapter.getProgressPercent());
        values.put(ChapterEntry.COLUMN_IS_COMPLETED, chapter.isCompleted() ? 1 : 0);
        return values;
    }

    @NonNull
    private Chapter fromCursor(@NonNull Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry._ID));
        int titleId = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_TITLE_ID));
        int index = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_CHAPTER_INDEX));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_CHAPTER_TITLE));
        String subtitle = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_SUBTITLE));
        String label = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_PAGE_OR_DURATION));
        String contentText = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_CONTENT_TEXT));
        long startMs = cursor.getLong(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_AUDIO_START_MS));
        long durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_AUDIO_DURATION_MS));
        int progress = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_PROGRESS_PERCENT));
        boolean completed = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_IS_COMPLETED)) == 1;

        return new Chapter(id, titleId, index, title, subtitle, label, contentText,
                startMs, durationMs, progress, completed);
    }
}
