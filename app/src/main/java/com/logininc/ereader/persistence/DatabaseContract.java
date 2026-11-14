package com.logininc.ereader.persistence;

import android.provider.BaseColumns;

/**
 * Defines the SQLite database schema, table names, column names, and SQL statements.
 */
public final class DatabaseContract {

    public static final String DATABASE_NAME = "ereader.db";
    public static final int DATABASE_VERSION = 1;

    private DatabaseContract() {
    }

    /**
     * Schema definition for the {@code titles} table storing books and audiobooks.
     */
    public static final class TitleEntry implements BaseColumns {
        public static final String TABLE_NAME = "titles";
        public static final String COLUMN_TITLE = "title";
        public static final String COLUMN_LEN_CHAPTERS = "len_chapters";
        public static final String COLUMN_CONTENT_URI = "content_uri";
        public static final String COLUMN_TYPE = "type";

        public static final String TYPE_BOOK = "BOOK";
        public static final String TYPE_AUDIOBOOK = "AUDIOBOOK";

        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TITLE + " TEXT NOT NULL, "
                        + COLUMN_LEN_CHAPTERS + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_CONTENT_URI + " TEXT NOT NULL, "
                        + COLUMN_TYPE + " TEXT NOT NULL"
                        + ");";

        public static final String SQL_DROP_TABLE =
                "DROP TABLE IF EXISTS " + TABLE_NAME + ";";

        private TitleEntry() {
        }
    }
}
