package com.logininc.ereader.persistence;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

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
        db.execSQL(DatabaseContract.TitleEntry.SQL_CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(DatabaseContract.TitleEntry.SQL_DROP_TABLE);
        onCreate(db);
    }
}
