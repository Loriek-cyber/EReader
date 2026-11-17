package com.logininc.ereader.persistence;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import com.logininc.ereader.persistence.DatabaseContract.BookmarkNoteEntry;
import com.logininc.ereader.persistence.DatabaseContract.ChapterEntry;
import com.logininc.ereader.persistence.DatabaseContract.SettingsEntry;
import com.logininc.ereader.persistence.DatabaseContract.TitleEntry;

/**
 * Manages SQLite database creation, versioning, and connection lifecycle.
 */
public class EReaderDbHelper extends SQLiteOpenHelper {

    private static volatile EReaderDbHelper instance;

    private EReaderDbHelper(@NonNull Context context) {
        super(
                context.getApplicationContext(),
                DatabaseContract.DATABASE_NAME,
                null,
                DatabaseContract.DATABASE_VERSION
        );
    }

    /**
     * Returns the singleton {@link EReaderDbHelper} instance.
     */
    public static EReaderDbHelper getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (EReaderDbHelper.class) {
                if (instance == null) {
                    instance = new EReaderDbHelper(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(TitleEntry.SQL_CREATE_TABLE);
        db.execSQL(ChapterEntry.SQL_CREATE_TABLE);
        db.execSQL(ChapterEntry.SQL_CREATE_INDEX);
        db.execSQL(BookmarkNoteEntry.SQL_CREATE_TABLE);
        db.execSQL(SettingsEntry.SQL_CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3 && newVersion >= 3) {
            upgradeFromV2ToV3(db);
        }
        if (oldVersion < 4 && newVersion >= 4) {
            upgradeFromV3ToV4(db);
        }
        if (oldVersion >= newVersion) {
            recreateSchema(db);
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        recreateSchema(db);
    }

    private void upgradeFromV2ToV3(@NonNull SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_PARENT_CHAPTER_ID + " INTEGER DEFAULT NULL REFERENCES "
                + ChapterEntry.TABLE_NAME + "(" + ChapterEntry._ID + ") ON DELETE CASCADE;");
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_LEVEL + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_SORT_ORDER + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_HREF + " TEXT NOT NULL DEFAULT '';");
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_FRAGMENT_ID + " TEXT NOT NULL DEFAULT '';");
        db.execSQL("ALTER TABLE " + ChapterEntry.TABLE_NAME + " ADD COLUMN "
                + ChapterEntry.COLUMN_RAW_HTML + " TEXT NOT NULL DEFAULT '';");
        db.execSQL(ChapterEntry.SQL_CREATE_INDEX);
    }

    private void upgradeFromV3ToV4(@NonNull SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_SCROLL_Y + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_SCROLL_MAX_HEIGHT + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_VIEWPORT_HEIGHT + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_TEXT_SIZE_SP + " INTEGER NOT NULL DEFAULT 18;");
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_CHAR_OFFSET + " INTEGER NOT NULL DEFAULT 0;");
        db.execSQL("ALTER TABLE " + TitleEntry.TABLE_NAME + " ADD COLUMN "
                + TitleEntry.COLUMN_SCROLL_PERMILLE + " INTEGER NOT NULL DEFAULT 0;");
    }

    private void recreateSchema(@NonNull SQLiteDatabase db) {
        db.execSQL(BookmarkNoteEntry.SQL_DROP_TABLE);
        db.execSQL(ChapterEntry.SQL_DROP_TABLE);
        db.execSQL(TitleEntry.SQL_DROP_TABLE);
        db.execSQL(SettingsEntry.SQL_DROP_TABLE);
        onCreate(db);
    }
}
