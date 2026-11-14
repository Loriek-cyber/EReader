package com.logininc.ereader.persistence;

import android.provider.BaseColumns;

/**
 * Defines the SQLite database schema, table names, column names, and SQL statements.
 */
public final class DatabaseContract {

    public static final String DATABASE_NAME = "ereader.db";
    public static final int DATABASE_VERSION = 2;

    private DatabaseContract() {
    }

    /**
     * Schema definition for the {@code titles} table storing books and audiobooks.
     */
    public static final class TitleEntry implements BaseColumns {
        public static final String TABLE_NAME = "titles";
        public static final String COLUMN_TITLE = "title";
        public static final String COLUMN_AUTHOR = "author";
        public static final String COLUMN_NARRATOR = "narrator";
        public static final String COLUMN_LEN_CHAPTERS = "len_chapters";
        public static final String COLUMN_CURRENT_CHAPTER = "current_chapter";
        public static final String COLUMN_CURRENT_CHAPTER_COMPLETED = "current_chapter_completed";
        public static final String COLUMN_PROGRESS_PERCENT = "progress_percent";
        public static final String COLUMN_CURRENT_PAGE = "current_page";
        public static final String COLUMN_TOTAL_PAGES = "total_pages";
        public static final String COLUMN_CURRENT_POSITION_MS = "current_position_ms";
        public static final String COLUMN_TOTAL_DURATION_MS = "total_duration_ms";
        public static final String COLUMN_CONTENT_URI = "content_uri";
        public static final String COLUMN_COVER_URI = "cover_uri";
        public static final String COLUMN_TYPE = "type";
        public static final String COLUMN_STATUS = "status";
        public static final String COLUMN_LAST_OPENED_AT = "last_opened_at";

        public static final String TYPE_BOOK = "BOOK";
        public static final String TYPE_AUDIOBOOK = "AUDIOBOOK";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TITLE + " TEXT NOT NULL, "
                        + COLUMN_AUTHOR + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_NARRATOR + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_LEN_CHAPTERS + " INTEGER NOT NULL DEFAULT 1, "
                        + COLUMN_CURRENT_CHAPTER + " INTEGER NOT NULL DEFAULT 1, "
                        + COLUMN_CURRENT_CHAPTER_COMPLETED + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_PROGRESS_PERCENT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_CURRENT_PAGE + " INTEGER NOT NULL DEFAULT 1, "
                        + COLUMN_TOTAL_PAGES + " INTEGER NOT NULL DEFAULT 1, "
                        + COLUMN_CURRENT_POSITION_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_TOTAL_DURATION_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_CONTENT_URI + " TEXT NOT NULL, "
                        + COLUMN_COVER_URI + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_TYPE + " TEXT NOT NULL, "
                        + COLUMN_STATUS + " TEXT NOT NULL DEFAULT 'TO_START', "
                        + COLUMN_LAST_OPENED_AT + " INTEGER NOT NULL DEFAULT 0"
                        + ");";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private TitleEntry() {
        }
    }

    /**
     * Schema definition for the {@code chapters} table linked via foreign key to {@code titles}.
     */
    public static final class ChapterEntry implements BaseColumns {
        public static final String TABLE_NAME = "chapters";
        public static final String COLUMN_TITLE_ID = "title_id";
        public static final String COLUMN_CHAPTER_INDEX = "chapter_index";
        public static final String COLUMN_CHAPTER_TITLE = "chapter_title";
        public static final String COLUMN_SUBTITLE = "subtitle";
        public static final String COLUMN_PAGE_OR_DURATION = "page_or_duration";
        public static final String COLUMN_CONTENT_TEXT = "content_text";
        public static final String COLUMN_AUDIO_START_MS = "audio_start_ms";
        public static final String COLUMN_AUDIO_DURATION_MS = "audio_duration_ms";
        public static final String COLUMN_PROGRESS_PERCENT = "progress_percent";
        public static final String COLUMN_IS_COMPLETED = "is_completed";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TITLE_ID + " INTEGER NOT NULL, "
                        + COLUMN_CHAPTER_INDEX + " INTEGER NOT NULL, "
                        + COLUMN_CHAPTER_TITLE + " TEXT NOT NULL, "
                        + COLUMN_SUBTITLE + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_PAGE_OR_DURATION + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_CONTENT_TEXT + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_AUDIO_START_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_AUDIO_DURATION_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_PROGRESS_PERCENT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_IS_COMPLETED + " INTEGER NOT NULL DEFAULT 0, "
                        + "FOREIGN KEY(" + COLUMN_TITLE_ID + ") REFERENCES "
                        + TitleEntry.TABLE_NAME + "(" + TitleEntry._ID + ") ON DELETE CASCADE"
                        + ");";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private ChapterEntry() {
        }
    }

    /**
     * Schema definition for bookmarks and notes linked to a title.
     */
    public static final class BookmarkNoteEntry implements BaseColumns {
        public static final String TABLE_NAME = "bookmarks_notes";
        public static final String COLUMN_TITLE_ID = "title_id";
        public static final String COLUMN_CHAPTER_INDEX = "chapter_index";
        public static final String COLUMN_ENTRY_TYPE = "entry_type";
        public static final String COLUMN_POSITION_LABEL = "position_label";
        public static final String COLUMN_NOTE_TEXT = "note_text";
        public static final String COLUMN_CREATED_AT = "created_at";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TITLE_ID + " INTEGER NOT NULL, "
                        + COLUMN_CHAPTER_INDEX + " INTEGER NOT NULL DEFAULT 1, "
                        + COLUMN_ENTRY_TYPE + " TEXT NOT NULL, "
                        + COLUMN_POSITION_LABEL + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_NOTE_TEXT + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_CREATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                        + "FOREIGN KEY(" + COLUMN_TITLE_ID + ") REFERENCES "
                        + TitleEntry.TABLE_NAME + "(" + TitleEntry._ID + ") ON DELETE CASCADE"
                        + ");";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private BookmarkNoteEntry() {
        }
    }

    /**
     * Key-value settings table persisted in SQLite.
     */
    public static final class SettingsEntry {
        public static final String TABLE_NAME = "settings";
        public static final String COLUMN_KEY = "setting_key";
        public static final String COLUMN_VALUE = "setting_value";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + COLUMN_KEY + " TEXT PRIMARY KEY, "
                        + COLUMN_VALUE + " TEXT NOT NULL"
                        + ");";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private SettingsEntry() {
        }
    }
}
