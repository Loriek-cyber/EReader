package com.logininc.ereader.persistence;

import android.provider.BaseColumns;

/**
 * Defines the SQLite database schema, table names, column names, and SQL statements.
 */
public final class DatabaseContract {

    public static final String DATABASE_NAME = "ereader.db";
    public static final int DATABASE_VERSION = 5;

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
        public static final String COLUMN_SCROLL_Y = "scroll_y";
        public static final String COLUMN_SCROLL_MAX_HEIGHT = "scroll_max_height";
        public static final String COLUMN_VIEWPORT_HEIGHT = "viewport_height";
        public static final String COLUMN_TEXT_SIZE_SP = "text_size_sp";
        public static final String COLUMN_CHAR_OFFSET = "char_offset";
        public static final String COLUMN_SCROLL_PERMILLE = "scroll_permille";

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
                        + COLUMN_LAST_OPENED_AT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_SCROLL_Y + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_SCROLL_MAX_HEIGHT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_VIEWPORT_HEIGHT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_TEXT_SIZE_SP + " INTEGER NOT NULL DEFAULT 18, "
                        + COLUMN_CHAR_OFFSET + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_SCROLL_PERMILLE + " INTEGER NOT NULL DEFAULT 0"
                        + ");";

        public static final String SQL_CREATE_INDEX_CONTENT_URI =
                "CREATE INDEX IF NOT EXISTS idx_titles_content_uri ON "
                        + TABLE_NAME + " (" + COLUMN_CONTENT_URI + ");";

        public static final String SQL_CREATE_INDEX_TYPE_OPENED =
                "CREATE INDEX IF NOT EXISTS idx_titles_type_opened ON "
                        + TABLE_NAME + " (" + COLUMN_TYPE + ", " + COLUMN_LAST_OPENED_AT + " DESC);";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private TitleEntry() {
        }
    }

    /**
     * Schema definition for the {@code chapters} table linked via foreign key to {@code titles}
     * and self-referencing {@code parent_chapter_id} for hierarchical sections/subsections.
     */
    public static final class ChapterEntry implements BaseColumns {
        public static final String TABLE_NAME = "chapters";
        public static final String COLUMN_TITLE_ID = "title_id";
        public static final String COLUMN_PARENT_CHAPTER_ID = "parent_chapter_id";
        public static final String COLUMN_CHAPTER_INDEX = "chapter_index";
        public static final String COLUMN_LEVEL = "level";
        public static final String COLUMN_SORT_ORDER = "sort_order";
        public static final String COLUMN_CHAPTER_TITLE = "chapter_title";
        public static final String COLUMN_SUBTITLE = "subtitle";
        public static final String COLUMN_HREF = "href";
        public static final String COLUMN_FRAGMENT_ID = "fragment_id";
        public static final String COLUMN_PAGE_OR_DURATION = "page_or_duration";
        public static final String COLUMN_CONTENT_TEXT = "content_text";
        public static final String COLUMN_RAW_HTML = "raw_html";
        public static final String COLUMN_AUDIO_START_MS = "audio_start_ms";
        public static final String COLUMN_AUDIO_DURATION_MS = "audio_duration_ms";
        public static final String COLUMN_PROGRESS_PERCENT = "progress_percent";
        public static final String COLUMN_IS_COMPLETED = "is_completed";

        public static final String INDEX_TITLE_PARENT = "idx_chapters_title_parent";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TITLE_ID + " INTEGER NOT NULL, "
                        + COLUMN_PARENT_CHAPTER_ID + " INTEGER DEFAULT NULL, "
                        + COLUMN_CHAPTER_INDEX + " INTEGER NOT NULL, "
                        + COLUMN_LEVEL + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_SORT_ORDER + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_CHAPTER_TITLE + " TEXT NOT NULL, "
                        + COLUMN_SUBTITLE + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_HREF + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_FRAGMENT_ID + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_PAGE_OR_DURATION + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_CONTENT_TEXT + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_RAW_HTML + " TEXT NOT NULL DEFAULT '', "
                        + COLUMN_AUDIO_START_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_AUDIO_DURATION_MS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_PROGRESS_PERCENT + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_IS_COMPLETED + " INTEGER NOT NULL DEFAULT 0, "
                        + "FOREIGN KEY(" + COLUMN_TITLE_ID + ") REFERENCES "
                        + TitleEntry.TABLE_NAME + "(" + TitleEntry._ID + ") ON DELETE CASCADE, "
                        + "FOREIGN KEY(" + COLUMN_PARENT_CHAPTER_ID + ") REFERENCES "
                        + TABLE_NAME + "(" + _ID + ") ON DELETE CASCADE"
                        + ");";

        public static final String SQL_CREATE_INDEX =
                "CREATE INDEX IF NOT EXISTS " + INDEX_TITLE_PARENT + " ON "
                        + TABLE_NAME + " (" + COLUMN_TITLE_ID + ", "
                        + COLUMN_PARENT_CHAPTER_ID + ", "
                        + COLUMN_SORT_ORDER + ");";

        public static final String SQL_CREATE_INDEX_TITLE_CHAPTER =
                "CREATE INDEX IF NOT EXISTS idx_chapters_title_index ON "
                        + TABLE_NAME + " (" + COLUMN_TITLE_ID + ", " + COLUMN_CHAPTER_INDEX + ");";

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

        public static final String SQL_CREATE_INDEX_TITLE_TYPE =
                "CREATE INDEX IF NOT EXISTS idx_bookmarks_title_type ON "
                        + TABLE_NAME + " (" + COLUMN_TITLE_ID + ", " + COLUMN_ENTRY_TYPE + ", " + COLUMN_CREATED_AT + " DESC);";

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
