package com.logininc.ereader.screen;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.BookmarkNote;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.BookmarkNoteDao;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.service.SleepTimerController;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.PermissionHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.util.Locale;

public class AudioPlayerActivity extends AppCompatActivity {

    private static final float[] SPEED_OPTIONS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int[] TIMER_OPTIONS_MIN = {0, 15, 30, 45, 60};

    private TitleDao titleDao;
    private ChapterDao chapterDao;
    private BookmarkNoteDao bookmarkNoteDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;

    private AudioBook audioBook;
    private boolean isUserScrubbing = false;
    private boolean handledCompletion = false;
    private boolean attemptedChapterRefresh = false;

    private ImageView ivCover;
    private TextView tvChapterPill;
    private TextView tvTitle;
    private TextView tvAuthor;
    private Slider sliderProgress;
    private TextView tvElapsed;
    private TextView tvRemaining;
    private ImageButton btnPlayPause;
    private TextView tvSpeed;
    private TextView tvTimer;

    private final PlaybackSessionManager.SessionListener sessionListener =
            new PlaybackSessionManager.SessionListener() {
                @Override
                public void onSessionChanged(
                        @Nullable Title activeTitle,
                        boolean isPlaying,
                        long positionMs,
                        long durationMs
                ) {
                    runOnUiThread(() -> renderSessionState(activeTitle, isPlaying, positionMs, durationMs));
                }

                @Override
                public void onChapterCompleted(@NonNull Title title, int completedChapterIndex) {
                    runOnUiThread(() -> {
                        if (handledCompletion || audioBook == null || title.getId() != audioBook.getId()) {
                            return;
                        }
                        if (Title.STATUS_COMPLETED.equals(title.getStatus())) {
                            handledCompletion = true;
                            Toast.makeText(
                                    AudioPlayerActivity.this,
                                    R.string.player_audiobook_completed_toast,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        appliedThemeMode = settingsDao.getThemeMode();
        ThemeHelper.applyTheme(this, false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_player);

        PermissionHelper.requestMissingPermissions(this);

        titleDao = new TitleDao(this);
        chapterDao = new ChapterDao(this);
        bookmarkNoteDao = new BookmarkNoteDao(this);

        if (!resolveTargetAudioBook()) {
            finish();
            return;
        }

        bindViews();
        startOrAttachSession(savedInstanceState != null);
        maybeRefreshAudiobookChapters();
    }

    private void maybeRefreshAudiobookChapters() {
        if (attemptedChapterRefresh || audioBook == null || audioBook.getLenChapters() > 1) {
            return;
        }
        attemptedChapterRefresh = true;
        final AudioBook target = audioBook;
        new Thread(() -> {
            if (FileScanner.refreshAudiobookChaptersIfNeeded(getApplicationContext(), target)) {
                PlaybackSessionManager.getInstance().invalidateChapterCache(target.getId());
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        PlaybackSessionManager sm = PlaybackSessionManager.getInstance();
                        renderSessionState(
                                sm.getActiveTitle() != null ? sm.getActiveTitle() : audioBook,
                                sm.isPlaying(),
                                sm.getCurrentPositionMs(),
                                sm.getTotalDurationMs()
                        );
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handledCompletion = false;
        if (resolveTargetAudioBook()) {
            bindViews();
            startOrAttachSession(false);
        }
    }

    private boolean resolveTargetAudioBook() {
        PlaybackSessionManager sessionManager = PlaybackSessionManager.getInstance();
        int titleId = getIntent().getIntExtra(NavigationFlowHelper.EXTRA_TITLE_ID, -1);
        Title loaded = titleId > 0 ? titleDao.getById(titleId) : sessionManager.getActiveTitle();
        if (loaded == null) {
            loaded = sessionManager.restoreIfNeeded(this);
        }
        if (!(loaded instanceof AudioBook)) {
            return false;
        }
        audioBook = (AudioBook) loaded;
        return true;
    }

    private void startOrAttachSession(boolean fromStateRestore) {
        PlaybackSessionManager sessionManager = PlaybackSessionManager.getInstance();
        boolean hasExplicitChapter = getIntent().hasExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX);
        boolean autoPlay = !fromStateRestore
                && getIntent().getBooleanExtra(NavigationFlowHelper.EXTRA_AUTO_PLAY, true);
        boolean skipRewind = getIntent().getBooleanExtra(NavigationFlowHelper.EXTRA_SKIP_RESUME_REWIND, false);
        int targetChapter = hasExplicitChapter
                ? getIntent().getIntExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX, audioBook.getCurrentChapter())
                : audioBook.getCurrentChapter();

        Title currentActive = sessionManager.getActiveTitle();
        boolean sameTitle = currentActive instanceof AudioBook && currentActive.getId() == audioBook.getId();
        boolean sameChapter = sameTitle && currentActive.getCurrentChapter() == targetChapter;

        if (skipRewind) {
            sessionManager.skipNextResumeRewind();
        }
        if (!sameTitle || !sameChapter || !sessionManager.hasPreparedAudio()) {
            sessionManager.startOrSwitchSession(this, audioBook, targetChapter, autoPlay);
        } else {
            if (currentActive instanceof AudioBook) {
                audioBook = (AudioBook) currentActive;
            }
            if (autoPlay && !sessionManager.isPlaying()) {
                sessionManager.resume(this);
            }
        }
        if (sessionManager.getActiveTitle() == null) {
            Toast.makeText(this, R.string.player_audio_error, Toast.LENGTH_LONG).show();
        }
    }

    private void bindViews() {
        findViewById(R.id.btnPlayerCollapse).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayerStop).setOnClickListener(v -> {
            PlaybackSessionManager.getInstance().stopAndClearSession(this);
            finish();
        });

        ivCover = findViewById(R.id.ivPlayerCover);
        tvChapterPill = findViewById(R.id.tvPlayerChapterPill);
        tvTitle = findViewById(R.id.tvPlayerTitle);
        tvAuthor = findViewById(R.id.tvPlayerAuthor);
        sliderProgress = findViewById(R.id.sliderAudioProgress);
        tvElapsed = findViewById(R.id.tvPlayerElapsed);
        tvRemaining = findViewById(R.id.tvPlayerRemaining);
        btnPlayPause = findViewById(R.id.btnPlayPauseMain);
        tvSpeed = findViewById(R.id.tvPlayerSpeed);
        tvTimer = findViewById(R.id.tvPlayerTimer);

        UiSessionHelper.loadCover(ivCover, audioBook.getCoverUri());
        tvTitle.setText(audioBook.getTitle());
        String authorOrNarrator = !audioBook.getNarrator().isEmpty()
                ? audioBook.getNarrator()
                : audioBook.getAuthor();
        tvAuthor.setText(authorOrNarrator);

        findViewById(R.id.btnPlayerBookmark).setOnClickListener(v -> showAddBookmarkOrNoteDialog());

        findViewById(R.id.btnSkipBack15).setOnClickListener(v ->
                PlaybackSessionManager.getInstance().seekByMs(-15_000L));
        findViewById(R.id.btnSkipForward30).setOnClickListener(v ->
                PlaybackSessionManager.getInstance().seekByMs(30_000L));
        findViewById(R.id.btnPrevChapter).setOnClickListener(v -> shiftChapter(-1));
        findViewById(R.id.btnNextChapter).setOnClickListener(v -> shiftChapter(1));
        btnPlayPause.setOnClickListener(v ->
                PlaybackSessionManager.getInstance().togglePlayPause(this));

        findViewById(R.id.btnPlayerSpeed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btnPlayerSleepTimer).setOnClickListener(v -> showSleepTimerDialog());
        findViewById(R.id.btnPlayerChapters).setOnClickListener(v -> {
            NavigationFlowHelper.openChapterSelector(this, audioBook);
            finish();
        });

        sliderProgress.clearOnSliderTouchListeners();
        sliderProgress.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                isUserScrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                isUserScrubbing = false;
                long[] window = resolveChapterWindow(PlaybackSessionManager.getInstance().getTotalDurationMs());
                if (window[1] > 0) {
                    long targetMs = window[0] + (long) ((slider.getValue() / 100.0f) * window[1]);
                    PlaybackSessionManager.getInstance().seekToMs(targetMs);
                }
            }
        });

        sliderProgress.clearOnChangeListeners();
        sliderProgress.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                long[] window = resolveChapterWindow(PlaybackSessionManager.getInstance().getTotalDurationMs());
                if (window[1] > 0) {
                    long previewRelMs = (long) ((value / 100.0f) * window[1]);
                    tvElapsed.setText(FileScanner.formatDuration(previewRelMs));
                    tvRemaining.setText("-" + FileScanner.formatDuration(Math.max(0L, window[1] - previewRelMs)));
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsDao.getThemeMode().equals(appliedThemeMode)) {
            recreate();
            return;
        }
        PlaybackSessionManager sessionManager = PlaybackSessionManager.getInstance();
        sessionManager.addListener(sessionListener);
        renderSessionState(
                sessionManager.getActiveTitle() != null ? sessionManager.getActiveTitle() : audioBook,
                sessionManager.isPlaying(),
                sessionManager.getCurrentPositionMs(),
                sessionManager.getTotalDurationMs()
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        PlaybackSessionManager.getInstance().removeListener(sessionListener);
        PlaybackSessionManager.getInstance().persistCurrentState();
    }

    private void renderSessionState(
            @Nullable Title activeTitle,
            boolean isPlaying,
            long positionMs,
            long durationMs
    ) {
        if (activeTitle instanceof AudioBook && activeTitle.getId() == audioBook.getId()) {
            audioBook = (AudioBook) activeTitle;
        }
        String chapterPill = getString(
                R.string.format_chapter_of_total,
                audioBook.getCurrentChapter(),
                audioBook.getLenChapters()
        );
        Chapter currentCh = chapterDao.getChapter(audioBook.getId(), audioBook.getCurrentChapter());
        if (currentCh != null && !currentCh.getChapterTitle().isEmpty()) {
            chapterPill = chapterPill + " • " + currentCh.getChapterTitle();
        }
        tvChapterPill.setText(chapterPill);
        btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        float speed = PlaybackSessionManager.getInstance().getPlaybackSpeed();
        tvSpeed.setText(formatSpeed(speed));

        int activeTimerMinutes = SleepTimerController.getInstance().getManualTimerMinutes();
        tvTimer.setText(activeTimerMinutes > 0
                ? getString(R.string.player_timer_fmt, activeTimerMinutes)
                : getString(R.string.player_timer_off));

        long safeTotal = Math.max(durationMs, audioBook.getTotalDurationMs());
        long safePos = Math.max(0L, positionMs);
        long[] window = resolveChapterWindow(safeTotal);
        long windowStart = window[0];
        long windowDuration = window[1];
        if (windowDuration > 0 && !isUserScrubbing) {
            long relPos = Math.max(0L, Math.min(windowDuration, safePos - windowStart));
            float percent = Math.min(100.0f, Math.max(0.0f, (relPos * 100.0f) / windowDuration));
            sliderProgress.setValue(percent);
            tvElapsed.setText(FileScanner.formatDuration(relPos));
            tvRemaining.setText("-" + FileScanner.formatDuration(Math.max(0L, windowDuration - relPos)));
        } else if (windowDuration <= 0 && !isUserScrubbing) {
            sliderProgress.setValue(0.0f);
            tvElapsed.setText(FileScanner.formatDuration(Math.max(0L, safePos - windowStart)));
            tvRemaining.setText(FileScanner.formatDuration(0L));
        }
    }

    @NonNull
    private long[] resolveChapterWindow(long totalDurationMs) {
        Chapter ch = chapterDao.getChapter(audioBook.getId(), audioBook.getCurrentChapter());
        if (ch != null && ch.getAudioDurationMs() > 0) {
            return new long[]{Math.max(0L, ch.getAudioStartMs()), ch.getAudioDurationMs()};
        }
        return new long[]{0L, Math.max(0L, totalDurationMs)};
    }

    private void shiftChapter(int delta) {
        PlaybackSessionManager.getInstance().shiftChapter(this, delta);
    }

    private void showAddBookmarkOrNoteDialog() {
        long currentMs = PlaybackSessionManager.getInstance().getCurrentPositionMs();
        String posLabel = getString(R.string.format_chapter_n, audioBook.getCurrentChapter())
                + " • " + FileScanner.formatDuration(currentMs);

        final EditText input = new EditText(this);
        input.setHint(R.string.reader_note_hint);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_note_dialog_title)
                .setMessage(posLabel)
                .setView(container)
                .setPositiveButton(R.string.reader_save_note, (d, w) -> {
                    String note = input.getText() != null ? input.getText().toString().trim() : "";
                    String type = note.isEmpty() ? BookmarkNote.TYPE_BOOKMARK : BookmarkNote.TYPE_NOTE;
                    saveBookmarkNote(type, posLabel, note);
                })
                .setNeutralButton(R.string.reader_save_bookmark_only, (d, w) ->
                        saveBookmarkNote(BookmarkNote.TYPE_BOOKMARK, posLabel, ""))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void saveBookmarkNote(@NonNull String entryType, @NonNull String posLabel, @NonNull String noteText) {
        bookmarkNoteDao.insert(new BookmarkNote(
                0,
                audioBook.getId(),
                audioBook.getCurrentChapter(),
                entryType,
                posLabel,
                noteText,
                System.currentTimeMillis()
        ));
        Toast.makeText(this, R.string.reader_bookmark_saved, Toast.LENGTH_SHORT).show();
    }

    private void showSpeedDialog() {
        String[] labels = new String[SPEED_OPTIONS.length];
        int checked = 1;
        float current = PlaybackSessionManager.getInstance().getPlaybackSpeed();
        for (int i = 0; i < SPEED_OPTIONS.length; i++) {
            labels[i] = formatSpeed(SPEED_OPTIONS[i]);
            if (Math.abs(SPEED_OPTIONS[i] - current) < 0.05f) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_speed_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    PlaybackSessionManager.getInstance().setPlaybackSpeed(SPEED_OPTIONS[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showSleepTimerDialog() {
        String[] labels = new String[TIMER_OPTIONS_MIN.length];
        int checked = 0;
        int currentMinutes = SleepTimerController.getInstance().getManualTimerMinutes();
        for (int i = 0; i < TIMER_OPTIONS_MIN.length; i++) {
            int mins = TIMER_OPTIONS_MIN[i];
            labels[i] = mins == 0
                    ? getString(R.string.player_sleep_off)
                    : getString(R.string.player_timer_fmt, mins);
            if (mins == currentMinutes) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_sleep_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int mins = TIMER_OPTIONS_MIN[which];
                    if (mins == 0) {
                        SleepTimerController.getInstance().cancelManualTimer();
                    } else {
                        SleepTimerController.getInstance().armManualTimer(this, mins);
                    }
                    tvTimer.setText(mins > 0
                            ? getString(R.string.player_timer_fmt, mins)
                            : getString(R.string.player_timer_off));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @NonNull
    private String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return String.format(Locale.ROOT, "%.1fx", speed);
        }
        return String.format(Locale.ROOT, "%sx", Float.toString(speed));
    }
}
