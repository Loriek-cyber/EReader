package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.logininc.ereader.persistence.DatabaseContract.SettingsEntry;

/**
 * SQLite-backed settings storage replacing SharedPreferences so all persistent state lives in SQLite.
 */
public class SettingsDao {

    public static final String THEME_DARK = "DARK";
    public static final String THEME_OLED = "OLED";
    public static final String THEME_LIGHT = "LIGHT";
    public static final String THEME_SEPIA = "SEPIA";

    public static final String KEY_PROFILE_NAME = "profile_name";
    public static final String KEY_EBOOK_FOLDER_URI = "ebook_folder_uri";
    public static final String KEY_AUDIO_FOLDER_URI = "audio_folder_uri";
    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String KEY_LANGUAGE_TAG = "language_tag";
    public static final String KEY_READER_FONT_SIZE = "reader_font_size_sp";
    public static final String KEY_READER_FONT_FAMILY = "reader_font_family";
    public static final String KEY_SLEEP_ENABLED = "sleep_enabled";
    public static final String KEY_SLEEP_START_HOUR = "sleep_start_hour";
    public static final String KEY_SLEEP_START_MIN = "sleep_start_min";
    public static final String KEY_SLEEP_END_HOUR = "sleep_end_hour";
    public static final String KEY_SLEEP_END_MIN = "sleep_end_min";
    public static final String KEY_SLEEP_INTERVAL_MIN = "sleep_interval_min";
    public static final String KEY_DEFAULT_AUDIO_SPEED = "default_audio_speed";
    public static final String KEY_AUDIO_RESUME_REWIND_SEC = "audio_resume_rewind_sec";
    public static final String KEY_AUDIO_AUTO_ADVANCE_CHAPTER = "audio_auto_advance_chapter";
    public static final String KEY_AUTO_BOOKMARK = "auto_bookmark";
    public static final String KEY_ACTIVE_SESSION_TITLE_ID = "active_session_title_id";
    public static final String KEY_ACTIVE_SESSION_PLAYING = "active_session_playing";

    private final EReaderDbHelper dbHelper;

    public SettingsDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public SettingsDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public void putString(@NonNull String key, @NonNull String value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SettingsEntry.COLUMN_KEY, key);
        values.put(SettingsEntry.COLUMN_VALUE, value);
        db.insertWithOnConflict(SettingsEntry.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @NonNull
    public String getString(@NonNull String key, @NonNull String defaultValue) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(
                SettingsEntry.TABLE_NAME,
                new String[]{SettingsEntry.COLUMN_VALUE},
                SettingsEntry.COLUMN_KEY + " = ?",
                new String[]{key},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                String val = cursor.getString(0);
                return val != null ? val : defaultValue;
            }
            return defaultValue;
        }
    }

    public void putInt(@NonNull String key, int value) {
        putString(key, String.valueOf(value));
    }

    public int getInt(@NonNull String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void putFloat(@NonNull String key, float value) {
        putString(key, String.valueOf(value));
    }

    public float getFloat(@NonNull String key, float defaultValue) {
        try {
            return Float.parseFloat(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void putBoolean(@NonNull String key, boolean value) {
        putString(key, value ? "1" : "0");
    }

    public boolean getBoolean(@NonNull String key, boolean defaultValue) {
        String raw = getString(key, defaultValue ? "1" : "0");
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
    }

    @NonNull
    public String getProfileName(@NonNull String fallback) {
        return getString(KEY_PROFILE_NAME, fallback);
    }

    public void setProfileName(@NonNull String name) {
        putString(KEY_PROFILE_NAME, name.trim());
    }

    @NonNull
    public String getEbookFolderUri() {
        return getString(KEY_EBOOK_FOLDER_URI, "");
    }

    public void setEbookFolderUri(@NonNull String uri) {
        putString(KEY_EBOOK_FOLDER_URI, uri);
    }

    @NonNull
    public String getAudioFolderUri() {
        return getString(KEY_AUDIO_FOLDER_URI, "");
    }

    public void setAudioFolderUri(@NonNull String uri) {
        putString(KEY_AUDIO_FOLDER_URI, uri);
    }

    @NonNull
    public String getThemeMode() {
        return getString(KEY_THEME_MODE, THEME_DARK);
    }

    public void setThemeMode(@NonNull String themeMode) {
        putString(KEY_THEME_MODE, themeMode);
    }

    @NonNull
    public String getLanguageTag() {
        return getString(KEY_LANGUAGE_TAG, "");
    }

    public void setLanguageTag(@NonNull String languageTag) {
        putString(KEY_LANGUAGE_TAG, languageTag);
    }

    public int getReaderFontSizeSp() {
        return getInt(KEY_READER_FONT_SIZE, 16);
    }

    public void setReaderFontSizeSp(int sizeSp) {
        putInt(KEY_READER_FONT_SIZE, Math.max(12, Math.min(28, sizeSp)));
    }

    @NonNull
    public String getReaderFontFamily() {
        return getString(KEY_READER_FONT_FAMILY, "serif");
    }

    public void setReaderFontFamily(@NonNull String family) {
        putString(KEY_READER_FONT_FAMILY, family);
    }

    public boolean isSleepTimerEnabled() {
        return getBoolean(KEY_SLEEP_ENABLED, true);
    }

    public void setSleepTimerEnabled(boolean enabled) {
        putBoolean(KEY_SLEEP_ENABLED, enabled);
    }

    public int getSleepStartHour() {
        return getInt(KEY_SLEEP_START_HOUR, 22);
    }

    public int getSleepStartMin() {
        return getInt(KEY_SLEEP_START_MIN, 30);
    }

    public int getSleepEndHour() {
        return getInt(KEY_SLEEP_END_HOUR, 7);
    }

    public int getSleepEndMin() {
        return getInt(KEY_SLEEP_END_MIN, 0);
    }

    public void setSleepWindow(int startHour, int startMin, int endHour, int endMin) {
        putInt(KEY_SLEEP_START_HOUR, startHour);
        putInt(KEY_SLEEP_START_MIN, startMin);
        putInt(KEY_SLEEP_END_HOUR, endHour);
        putInt(KEY_SLEEP_END_MIN, endMin);
    }

    public int getSleepIntervalMinutes() {
        return getInt(KEY_SLEEP_INTERVAL_MIN, 30);
    }

    public void setSleepIntervalMinutes(int minutes) {
        putInt(KEY_SLEEP_INTERVAL_MIN, Math.max(1, minutes));
    }

    public float getDefaultAudioSpeed() {
        return getFloat(KEY_DEFAULT_AUDIO_SPEED, 1.0f);
    }

    public void setDefaultAudioSpeed(float speed) {
        putFloat(KEY_DEFAULT_AUDIO_SPEED, speed);
    }

    public int getAudioResumeRewindSec() {
        return Math.max(0, Math.min(60, getInt(KEY_AUDIO_RESUME_REWIND_SEC, 5)));
    }

    public void setAudioResumeRewindSec(int seconds) {
        putInt(KEY_AUDIO_RESUME_REWIND_SEC, Math.max(0, Math.min(60, seconds)));
    }

    public boolean isAudioAutoAdvanceChapter() {
        return getBoolean(KEY_AUDIO_AUTO_ADVANCE_CHAPTER, true);
    }

    public void setAudioAutoAdvanceChapter(boolean enabled) {
        putBoolean(KEY_AUDIO_AUTO_ADVANCE_CHAPTER, enabled);
    }

    public boolean isAutoBookmark() {
        return getBoolean(KEY_AUTO_BOOKMARK, true);
    }

    public void setAutoBookmark(boolean enabled) {
        putBoolean(KEY_AUTO_BOOKMARK, enabled);
    }

    public int getActiveSessionTitleId() {
        return getInt(KEY_ACTIVE_SESSION_TITLE_ID, -1);
    }

    public void setActiveSessionTitleId(int titleId) {
        putInt(KEY_ACTIVE_SESSION_TITLE_ID, titleId);
    }

    public boolean isActiveSessionPlaying() {
        return getBoolean(KEY_ACTIVE_SESSION_PLAYING, false);
    }

    public void setActiveSessionPlaying(boolean playing) {
        putBoolean(KEY_ACTIVE_SESSION_PLAYING, playing);
    }
}
