package com.logininc.ereader.screen;

import android.app.TimePickerDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.logininc.ereader.R;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.PermissionHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileSettingsActivity extends AppCompatActivity {

    private static final float[] SPEED_OPTIONS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int[] RESUME_REWIND_OPTIONS_SEC = {0, 5, 10, 15, 30};
    private static final String[] THEME_CODES = {
            SettingsDao.THEME_DARK,
            SettingsDao.THEME_OLED,
            SettingsDao.THEME_LIGHT,
            SettingsDao.THEME_SEPIA
    };
    private static final String[] LANGUAGE_TAGS = {"", "it", "en"};

    private SettingsDao settingsDao;
    private TitleDao titleDao;
    private String appliedThemeMode;
    private String appliedLanguageTag;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private TextView tvProfileInitials;
    private TextView tvProfileName;
    private TextView tvEbookFolderPath;
    private TextView tvEbookFolderStats;
    private TextView tvAudioFolderPath;
    private TextView tvAudioFolderStats;
    private MaterialButton btnScanFolders;

    private MaterialSwitch switchSleepTimer;
    private TextView btnSleepWindow;
    private MaterialButton btnInterval15;
    private MaterialButton btnInterval30;
    private MaterialButton btnInterval45;
    private MaterialButton btnInterval60;

    private TextView tvCurrentTheme;
    private MaterialSwitch switchOledMode;
    private TextView tvCurrentLanguage;
    private TextView btnDefaultSpeed;
    private TextView btnAudioResumeRewind;
    private MaterialSwitch switchAudioAutoAdvance;
    private MaterialSwitch switchAutoBookmark;

    private PlaybackSessionManager.SessionListener miniPlayerListener;

    private final ActivityResultLauncher<Uri> pickEbookFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    onFolderPicked(uri, false);
                }
            });

    private final ActivityResultLauncher<Uri> pickAudioFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    onFolderPicked(uri, true);
                }
            });

    private final CompoundButton.OnCheckedChangeListener sleepSwitchListener =
            (buttonView, isChecked) -> settingsDao.setSleepTimerEnabled(isChecked);

    private final CompoundButton.OnCheckedChangeListener oledSwitchListener =
            (buttonView, isChecked) -> {
                String targetTheme = isChecked ? SettingsDao.THEME_OLED : SettingsDao.THEME_DARK;
                if (!targetTheme.equals(settingsDao.getThemeMode())) {
                    settingsDao.setThemeMode(targetTheme);
                    recreate();
                }
            };

    private final CompoundButton.OnCheckedChangeListener autoBookmarkListener =
            (buttonView, isChecked) -> settingsDao.setAutoBookmark(isChecked);

    private final CompoundButton.OnCheckedChangeListener audioAutoAdvanceListener =
            (buttonView, isChecked) -> settingsDao.setAudioAutoAdvanceChapter(isChecked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        appliedThemeMode = settingsDao.getThemeMode();
        appliedLanguageTag = settingsDao.getLanguageTag();
        ThemeHelper.applyTheme(this, false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_settings);

        titleDao = new TitleDao(this);
        bindViews();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                UiSessionHelper.navigateToBottomNavTarget(ProfileSettingsActivity.this, LibraryActivity.class);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsDao.getThemeMode().equals(appliedThemeMode)
                || !settingsDao.getLanguageTag().equals(appliedLanguageTag)) {
            recreate();
            return;
        }
        UiSessionHelper.syncBottomNavigation(this, R.id.nav_profilo);
        miniPlayerListener = UiSessionHelper.attachMiniPlayer(this);
        renderAllSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (miniPlayerListener != null) {
            PlaybackSessionManager.getInstance().removeListener(miniPlayerListener);
            miniPlayerListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private void bindViews() {
        tvProfileInitials = findViewById(R.id.tvProfileInitials);
        tvProfileName = findViewById(R.id.tvProfileName);
        tvEbookFolderPath = findViewById(R.id.tvEbookFolderPath);
        tvEbookFolderStats = findViewById(R.id.tvEbookFolderStats);
        tvAudioFolderPath = findViewById(R.id.tvAudioFolderPath);
        tvAudioFolderStats = findViewById(R.id.tvAudioFolderStats);
        btnScanFolders = findViewById(R.id.btnScanFolders);

        switchSleepTimer = findViewById(R.id.switchSleepTimer);
        btnSleepWindow = findViewById(R.id.btnSleepWindow);
        btnInterval15 = findViewById(R.id.btnInterval15);
        btnInterval30 = findViewById(R.id.btnInterval30);
        btnInterval45 = findViewById(R.id.btnInterval45);
        btnInterval60 = findViewById(R.id.btnInterval60);

        tvCurrentTheme = findViewById(R.id.tvCurrentTheme);
        switchOledMode = findViewById(R.id.switchOledMode);
        tvCurrentLanguage = findViewById(R.id.tvCurrentLanguage);
        btnDefaultSpeed = findViewById(R.id.btnDefaultSpeed);
        btnAudioResumeRewind = findViewById(R.id.btnAudioResumeRewind);
        switchAudioAutoAdvance = findViewById(R.id.switchAudioAutoAdvance);
        switchAutoBookmark = findViewById(R.id.switchAutoBookmark);

        findViewById(R.id.btnEditProfile).setOnClickListener(v -> showEditProfileDialog());
        findViewById(R.id.btnPickEbookFolder).setOnClickListener(v -> {
            PermissionHelper.requestMissingPermissions(this);
            pickEbookFolderLauncher.launch(parseUriOrNull(settingsDao.getEbookFolderUri()));
        });
        findViewById(R.id.btnPickAudioFolder).setOnClickListener(v -> {
            PermissionHelper.requestMissingPermissions(this);
            pickAudioFolderLauncher.launch(parseUriOrNull(settingsDao.getAudioFolderUri()));
        });
        btnScanFolders.setOnClickListener(v -> scanConfiguredFolders());

        btnSleepWindow.setOnClickListener(v -> showSleepWindowPickers());
        btnInterval15.setOnClickListener(v -> updateSleepInterval(15));
        btnInterval30.setOnClickListener(v -> updateSleepInterval(30));
        btnInterval45.setOnClickListener(v -> updateSleepInterval(45));
        btnInterval60.setOnClickListener(v -> updateSleepInterval(60));

        findViewById(R.id.rowThemeSelector).setOnClickListener(v -> showThemePickerDialog());
        findViewById(R.id.rowLanguageSelector).setOnClickListener(v -> showLanguagePickerDialog());
        btnDefaultSpeed.setOnClickListener(v -> showDefaultSpeedDialog());
        btnAudioResumeRewind.setOnClickListener(v -> showResumeRewindDialog());

        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        UiSessionHelper.bindBottomNavigation(this, bottomNav, R.id.nav_profilo);
    }

    private void renderAllSettings() {
        String profileName = settingsDao.getProfileName(getString(R.string.default_profile_name));
        if (profileName.isEmpty()) {
            profileName = getString(R.string.default_profile_name);
        }
        tvProfileName.setText(profileName);
        tvProfileInitials.setText(computeInitials(profileName));

        String ebookUriStr = settingsDao.getEbookFolderUri();
        tvEbookFolderPath.setText(ebookUriStr.isEmpty()
                ? getString(R.string.settings_folder_not_set)
                : FileScanner.queryReadablePathLabel(Uri.parse(ebookUriStr)));

        String audioUriStr = settingsDao.getAudioFolderUri();
        tvAudioFolderPath.setText(audioUriStr.isEmpty()
                ? getString(R.string.settings_folder_not_set)
                : FileScanner.queryReadablePathLabel(Uri.parse(audioUriStr)));

        int bookCount = titleDao.countByType(com.logininc.ereader.persistence.DatabaseContract.TitleEntry.TYPE_BOOK);
        int audioCount = titleDao.countByType(com.logininc.ereader.persistence.DatabaseContract.TitleEntry.TYPE_AUDIOBOOK);
        tvEbookFolderStats.setText(getString(R.string.format_titles_count, bookCount));
        tvAudioFolderStats.setText(getString(R.string.format_titles_count, audioCount));

        switchSleepTimer.setOnCheckedChangeListener(null);
        switchSleepTimer.setChecked(settingsDao.isSleepTimerEnabled());
        switchSleepTimer.setOnCheckedChangeListener(sleepSwitchListener);

        btnSleepWindow.setText(String.format(
                Locale.ROOT,
                "%02d:%02d — %02d:%02d",
                settingsDao.getSleepStartHour(),
                settingsDao.getSleepStartMin(),
                settingsDao.getSleepEndHour(),
                settingsDao.getSleepEndMin()
        ));

        updateIntervalButtons(settingsDao.getSleepIntervalMinutes());

        String themeMode = settingsDao.getThemeMode();
        tvCurrentTheme.setText(getThemeDisplayName(themeMode));

        switchOledMode.setOnCheckedChangeListener(null);
        switchOledMode.setChecked(SettingsDao.THEME_OLED.equals(themeMode));
        switchOledMode.setOnCheckedChangeListener(oledSwitchListener);

        tvCurrentLanguage.setText(getLanguageDisplayName(settingsDao.getLanguageTag()));
        btnDefaultSpeed.setText(formatSpeed(settingsDao.getDefaultAudioSpeed()));
        btnAudioResumeRewind.setText(formatRewindSeconds(settingsDao.getAudioResumeRewindSec()));

        switchAudioAutoAdvance.setOnCheckedChangeListener(null);
        switchAudioAutoAdvance.setChecked(settingsDao.isAudioAutoAdvanceChapter());
        switchAudioAutoAdvance.setOnCheckedChangeListener(audioAutoAdvanceListener);

        switchAutoBookmark.setOnCheckedChangeListener(null);
        switchAutoBookmark.setChecked(settingsDao.isAutoBookmark());
        switchAutoBookmark.setOnCheckedChangeListener(autoBookmarkListener);
    }

    private void showResumeRewindDialog() {
        String[] labels = new String[RESUME_REWIND_OPTIONS_SEC.length];
        int current = settingsDao.getAudioResumeRewindSec();
        int checked = 1;
        for (int i = 0; i < RESUME_REWIND_OPTIONS_SEC.length; i++) {
            int sec = RESUME_REWIND_OPTIONS_SEC[i];
            labels[i] = formatRewindSeconds(sec);
            if (sec == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_audio_resume_rewind)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int sec = RESUME_REWIND_OPTIONS_SEC[which];
                    settingsDao.setAudioResumeRewindSec(sec);
                    btnAudioResumeRewind.setText(formatRewindSeconds(sec));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @NonNull
    private String formatRewindSeconds(int seconds) {
        if (seconds <= 0) {
            return getString(R.string.settings_audio_resume_rewind_off);
        }
        return getString(R.string.settings_audio_resume_rewind_sec_fmt, seconds);
    }

    private void onFolderPicked(@NonNull Uri treeUri, boolean isAudio) {
        //TODO
    }

    private void scanConfiguredFolders() {
        //TODO
    }

    private void showEditProfileDialog() {
        final EditText input = new EditText(this);
        input.setText(settingsDao.getProfileName(getString(R.string.default_profile_name)));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_edit_profile_title)
                .setView(container)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String entered = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!entered.isEmpty()) {
                        settingsDao.setProfileName(entered);
                        renderAllSettings();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showSleepWindowPickers() {
        int startH = settingsDao.getSleepStartHour();
        int startM = settingsDao.getSleepStartMin();
        TimePickerDialog startPicker = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> showEndWindowPicker(hourOfDay, minute),
                startH,
                startM,
                true
        );
        startPicker.setTitle(R.string.settings_sleep_start_picker);
        startPicker.show();
    }

    private void showEndWindowPicker(int startHour, int startMin) {
        int endH = settingsDao.getSleepEndHour();
        int endM = settingsDao.getSleepEndMin();
        TimePickerDialog endPicker = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    settingsDao.setSleepWindow(startHour, startMin, hourOfDay, minute);
                    renderAllSettings();
                },
                endH,
                endM,
                true
        );
        endPicker.setTitle(R.string.settings_sleep_end_picker);
        endPicker.show();
    }

    private void updateSleepInterval(int minutes) {
        settingsDao.setSleepIntervalMinutes(minutes);
        updateIntervalButtons(minutes);
    }

    private void updateIntervalButtons(int selectedMinutes) {
        styleIntervalButton(btnInterval15, selectedMinutes == 15);
        styleIntervalButton(btnInterval30, selectedMinutes == 30);
        styleIntervalButton(btnInterval45, selectedMinutes == 45);
        styleIntervalButton(btnInterval60, selectedMinutes == 60);
    }

    private void styleIntervalButton(@NonNull MaterialButton button, boolean selected) {
        if (selected) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    resolveAttrColor(com.google.android.material.R.attr.colorPrimaryContainer)));
            button.setTextColor(getColor(R.color.white));
        } else {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.TRANSPARENT));
            button.setTextColor(resolveAttrColor(R.attr.ereaderTextSecondary));
        }
    }

    private int resolveAttrColor(int attrRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    private void showThemePickerDialog() {
        String current = settingsDao.getThemeMode();
        String[] labels = new String[THEME_CODES.length];
        int checked = 0;
        for (int i = 0; i < THEME_CODES.length; i++) {
            labels[i] = getThemeDisplayName(THEME_CODES[i]);
            if (THEME_CODES[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_theme_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String chosen = THEME_CODES[which];
                    dialog.dismiss();
                    if (!chosen.equals(current)) {
                        settingsDao.setThemeMode(chosen);
                        recreate();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showLanguagePickerDialog() {
        String current = settingsDao.getLanguageTag();
        String[] labels = new String[LANGUAGE_TAGS.length];
        int checked = 0;
        for (int i = 0; i < LANGUAGE_TAGS.length; i++) {
            labels[i] = getLanguageDisplayName(LANGUAGE_TAGS[i]);
            if (LANGUAGE_TAGS[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String chosen = LANGUAGE_TAGS[which];
                    dialog.dismiss();
                    if (!chosen.equals(current)) {
                        settingsDao.setLanguageTag(chosen);
                        appliedLanguageTag = chosen;
                        LocaleListCompat beforeLocales = AppCompatDelegate.getApplicationLocales();
                        ThemeHelper.applySavedLocale(this);
                        LocaleListCompat desired = chosen.isEmpty()
                                ? LocaleListCompat.getEmptyLocaleList()
                                : LocaleListCompat.forLanguageTags(chosen);
                        if (desired.toLanguageTags().equals(beforeLocales.toLanguageTags())) {
                            recreate();
                        } else {
                            renderAllSettings();
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDefaultSpeedDialog() {
        float current = settingsDao.getDefaultAudioSpeed();
        String[] labels = new String[SPEED_OPTIONS.length];
        int checked = 1;
        for (int i = 0; i < SPEED_OPTIONS.length; i++) {
            labels[i] = formatSpeed(SPEED_OPTIONS[i]);
            if (Math.abs(SPEED_OPTIONS[i] - current) < 0.05f) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_default_speed)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settingsDao.setDefaultAudioSpeed(SPEED_OPTIONS[which]);
                    renderAllSettings();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void clearCacheAndRefresh() {
        deleteDirectoryContents(new File(getFilesDir(), "covers"));
        deleteDirectoryContents(getCacheDir());
        Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
        renderAllSettings();
    }

    private long computeCacheSizeBytes() {
        return directorySize(new File(getFilesDir(), "covers")) + directorySize(getCacheDir());
    }

    private long directorySize(@NonNull File dir) {
        if (!dir.exists()) {
            return 0L;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long sum = 0L;
        for (File f : files) {
            sum += f.isDirectory() ? directorySize(f) : f.length();
        }
        return sum;
    }

    private void deleteDirectoryContents(@NonNull File dir) {
        if (!dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirectoryContents(f);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @NonNull
    private String getThemeDisplayName(@NonNull String code) {
        switch (code) {
            case SettingsDao.THEME_OLED:
                return getString(R.string.theme_oled);
            case SettingsDao.THEME_LIGHT:
                return getString(R.string.theme_light);
            case SettingsDao.THEME_SEPIA:
                return getString(R.string.theme_sepia);
            case SettingsDao.THEME_DARK:
            default:
                return getString(R.string.theme_dark);
        }
    }

    @NonNull
    private String getLanguageDisplayName(@NonNull String tag) {
        if ("it".equals(tag)) {
            return getString(R.string.lang_italian);
        }
        if ("en".equals(tag)) {
            return getString(R.string.lang_english);
        }
        return getString(R.string.lang_system);
    }

    @NonNull
    private String computeInitials(@NonNull String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "L";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        String first = parts[0].substring(0, 1);
        String second = parts[1].substring(0, 1);
        return (first + second).toUpperCase(Locale.ROOT);
    }

    @NonNull
    private String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return String.format(Locale.ROOT, "%.1fx", speed);
        }
        return speed + "x";
    }

    @Nullable
    private Uri parseUriOrNull(@NonNull String raw) {
        return raw.isEmpty() ? null : Uri.parse(raw);
    }
}
