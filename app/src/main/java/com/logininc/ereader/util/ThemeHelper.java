package com.logininc.ereader.util;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.logininc.ereader.R;
import com.logininc.ereader.persistence.SettingsDao;

/**
 * Applies user-selected themes and locales persisted in SQLite.
 */
public final class ThemeHelper {

    private ThemeHelper() {
    }

    public static void applySavedLocale(@NonNull Context context) {
        String tag = new SettingsDao(context).getLanguageTag();
        LocaleListCompat desired = tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag);
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (!desired.toLanguageTags().equals(current.toLanguageTags())) {
            AppCompatDelegate.setApplicationLocales(desired);
        }
    }

    public static void applyTheme(@NonNull Activity activity, boolean isCanvasScreen) {
        String mode = new SettingsDao(activity).getThemeMode();
        int themeRes;
        switch (mode) {
            case SettingsDao.THEME_OLED:
                themeRes = R.style.Theme_EReader_Oled;
                break;
            case SettingsDao.THEME_LIGHT:
                themeRes = R.style.Theme_EReader_Light;
                break;
            case SettingsDao.THEME_SEPIA:
                themeRes = R.style.Theme_EReader_Sepia;
                break;
            case SettingsDao.THEME_DARK:
            default:
                themeRes = isCanvasScreen ? R.style.Theme_EReader_Canvas : R.style.Theme_EReader;
                break;
        }
        activity.setTheme(themeRes);
    }

    @NonNull
    public static String cycleNextTheme(@NonNull Context context) {
        SettingsDao settingsDao = new SettingsDao(context);
        String current = settingsDao.getThemeMode();
        String next;
        switch (current) {
            case SettingsDao.THEME_DARK:
                next = SettingsDao.THEME_OLED;
                break;
            case SettingsDao.THEME_OLED:
                next = SettingsDao.THEME_SEPIA;
                break;
            case SettingsDao.THEME_SEPIA:
                next = SettingsDao.THEME_LIGHT;
                break;
            case SettingsDao.THEME_LIGHT:
            default:
                next = SettingsDao.THEME_DARK;
                break;
        }
        settingsDao.setThemeMode(next);
        return next;
    }
}
