package com.logininc.ereader.screen;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.DatabaseContract.TitleEntry;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.M4BParser;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudiobooksActivity extends AppCompatActivity {

    private TitleDao titleDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;
    private String appliedLanguageTag;
    private int currentHeroAudioId = -1;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private EditText etSearch;
    private View sectionNowPlaying;
    private View cardAudioHero;
    private ImageView ivAudioHeroCover;
    private TextView tvHeroChapterBadge;
    private TextView tvHeroChapterTime;
    private TextView tvAudioHeroTitle;
    private TextView tvAudioHeroAuthor;
    private View layoutAudioHeroNarrator;
    private TextView tvAudioHeroNarrator;
    private View btnAudioHeroChapters;
    private ImageButton btnHeroPlayPause;
    private LinearProgressIndicator progressAudioHero;
    private TextView tvAudioHeroProgressLabel;
    private TextView tvAudioHeroRemaining;
    private TextView tvAudiobooksCount;
    private View layoutEmpty;
    private LinearLayout listAudiobooks;

    private PlaybackSessionManager.SessionListener miniPlayerListener;
    private PlaybackSessionManager.SessionListener heroSessionListener;

    private final ActivityResultLauncher<String[]> openAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importPickedAudio(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        appliedThemeMode = settingsDao.getThemeMode();
        appliedLanguageTag = settingsDao.getLanguageTag();
        ThemeHelper.applyTheme(this, false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audiobooks);

        titleDao = new TitleDao(this);
        bindViews();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                UiSessionHelper.navigateToBottomNavTarget(AudiobooksActivity.this, LibraryActivity.class);
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
        UiSessionHelper.syncBottomNavigation(this, R.id.nav_audiolibri);
        miniPlayerListener = UiSessionHelper.attachMiniPlayer(this);
        refreshAudiobooks();
        heroSessionListener = (activeTitle, isPlaying, positionMs, durationMs) ->
                runOnUiThread(() -> syncHeroWithSession(activeTitle, isPlaying, positionMs, durationMs));
        PlaybackSessionManager.getInstance().addListener(heroSessionListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (miniPlayerListener != null) {
            PlaybackSessionManager.getInstance().removeListener(miniPlayerListener);
            miniPlayerListener = null;
        }
        if (heroSessionListener != null) {
            PlaybackSessionManager.getInstance().removeListener(heroSessionListener);
            heroSessionListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private void bindViews() {
        etSearch = findViewById(R.id.etAudioSearch);
        sectionNowPlaying = findViewById(R.id.sectionAudioNowPlaying);
        cardAudioHero = findViewById(R.id.cardAudioHero);
        ivAudioHeroCover = findViewById(R.id.ivAudioHeroCover);
        tvHeroChapterBadge = findViewById(R.id.tvHeroChapterBadge);
        tvHeroChapterTime = findViewById(R.id.tvHeroChapterTime);
        tvAudioHeroTitle = findViewById(R.id.tvAudioHeroTitle);
        tvAudioHeroAuthor = findViewById(R.id.tvAudioHeroAuthor);
        layoutAudioHeroNarrator = findViewById(R.id.layoutAudioHeroNarrator);
        tvAudioHeroNarrator = findViewById(R.id.tvAudioHeroNarrator);
        btnAudioHeroChapters = findViewById(R.id.btnAudioHeroChapters);
        btnHeroPlayPause = findViewById(R.id.btnHeroPlayPause);
        progressAudioHero = findViewById(R.id.progressAudioHero);
        tvAudioHeroProgressLabel = findViewById(R.id.tvAudioHeroProgressLabel);
        tvAudioHeroRemaining = findViewById(R.id.tvAudioHeroRemaining);
        tvAudiobooksCount = findViewById(R.id.tvAudiobooksCount);
        layoutEmpty = findViewById(R.id.layoutAudioEmpty);
        listAudiobooks = findViewById(R.id.listAudiobooks);

        View.OnClickListener importListener = v -> openAudioLauncher.launch(new String[]{
                "audio/*",
                "audio/mp4",
                "audio/x-m4b",
                "audio/m4b",
                "video/mp4",
                "application/octet-stream",
                "*/*"
        });
        findViewById(R.id.btnImportAudio).setOnClickListener(importListener);
        findViewById(R.id.btnEmptyImportAudio).setOnClickListener(importListener);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshAudiobooks();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        UiSessionHelper.bindBottomNavigation(this, bottomNav, R.id.nav_audiolibri);
    }

    private void importPickedAudio(@NonNull Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        String name = FileScanner.queryDisplayName(this, uri);
        String mime = getContentResolver().getType(uri);
        if (!FileScanner.isAudioFile(name) && !M4BParser.isM4bOrAudioMime(mime)) {
            Toast.makeText(this, R.string.settings_audio_formats, Toast.LENGTH_SHORT).show();
            return;
        }
        ioExecutor.execute(() -> {
            Title imported = FileScanner.importSingleUri(getApplicationContext(), uri);
            runOnUiThread(() -> {
                if (imported != null) {
                    refreshAudiobooks();
                    NavigationFlowHelper.openTitle(this, imported);
                } else {
                    Toast.makeText(this, R.string.player_audio_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshAudiobooks() {
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";

        AudioBook hero = null;
        if (query.isEmpty()) {
            Title active = PlaybackSessionManager.getInstance().getActiveTitle();
            if (active instanceof AudioBook) {
                hero = (AudioBook) titleDao.getById(active.getId());
            }
            if (hero == null) {
                hero = titleDao.getMostRecentInProgressAudioBook();
            }
        }
        bindHeroAudioBook(hero);

        List<AudioBook> items;
        if (query.isEmpty()) {
            items = titleDao.getAllAudioBooks();
        } else {
            List<Title> matched = titleDao.searchByTypeAndQuery(TitleEntry.TYPE_AUDIOBOOK, query);
            items = new ArrayList<>(matched.size());
            for (Title t : matched) {
                if (t instanceof AudioBook) {
                    items.add((AudioBook) t);
                }
            }
        }

        tvAudiobooksCount.setText(getString(R.string.format_titles_count, items.size()));
        renderAudiobooksList(items);
    }

    private void bindHeroAudioBook(AudioBook hero) {
        if (hero == null) {
            currentHeroAudioId = -1;
            sectionNowPlaying.setVisibility(View.GONE);
            return;
        }
        currentHeroAudioId = hero.getId();
        sectionNowPlaying.setVisibility(View.VISIBLE);
        UiSessionHelper.loadCover(ivAudioHeroCover, hero.getCoverUri());
        tvHeroChapterBadge.setText(getString(R.string.format_chapter_of_total,
                hero.getCurrentChapter(), hero.getLenChapters()));
        tvHeroChapterTime.setText(FileScanner.formatDuration(hero.getCurrentPositionMs())
                + " / " + FileScanner.formatDuration(hero.getTotalDurationMs()));
        tvAudioHeroTitle.setText(hero.getTitle());
        tvAudioHeroAuthor.setText(hero.getAuthor());
        tvAudioHeroAuthor.setVisibility(hero.getAuthor().isEmpty() ? View.GONE : View.VISIBLE);

        if (hero.getNarrator().isEmpty()) {
            layoutAudioHeroNarrator.setVisibility(View.GONE);
        } else {
            layoutAudioHeroNarrator.setVisibility(View.VISIBLE);
            tvAudioHeroNarrator.setText(getString(R.string.format_narrator, hero.getNarrator()));
        }

        progressAudioHero.setProgress(hero.getProgressPercent());
        tvAudioHeroProgressLabel.setText(getString(R.string.format_percent_completed, hero.getProgressPercent()));
        long remainingMs = Math.max(0L, hero.getTotalDurationMs() - hero.getCurrentPositionMs());
        tvAudioHeroRemaining.setText(getString(R.string.format_remaining_time, FileScanner.formatDuration(remainingMs)));

        PlaybackSessionManager session = PlaybackSessionManager.getInstance();
        boolean isThisPlaying = session.isPlaying()
                && session.getActiveTitle() != null
                && session.getActiveTitle().getId() == hero.getId();
        btnHeroPlayPause.setImageResource(isThisPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        btnAudioHeroChapters.setOnClickListener(v -> NavigationFlowHelper.openChapterSelector(this, hero));
        cardAudioHero.setOnClickListener(v -> NavigationFlowHelper.openTitle(this, hero));
        btnHeroPlayPause.setOnClickListener(v -> {
            if (session.getActiveTitle() != null && session.getActiveTitle().getId() == hero.getId()) {
                session.togglePlayPause(this);
                refreshAudiobooks();
            } else {
                NavigationFlowHelper.openTitle(this, hero);
            }
        });
    }

    private void syncHeroWithSession(
            @Nullable Title activeTitle,
            boolean isPlaying,
            long positionMs,
            long durationMs
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (activeTitle == null) {
            if (currentHeroAudioId != -1) {
                refreshAudiobooks();
            }
            return;
        }
        if (!(activeTitle instanceof AudioBook)) {
            return;
        }
        AudioBook activeAudio = (AudioBook) activeTitle;
        if (activeAudio.getId() != currentHeroAudioId) {
            refreshAudiobooks();
            return;
        }
        btnHeroPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        long totalMs = Math.max(durationMs, activeAudio.getTotalDurationMs());
        long safePos = Math.max(0L, positionMs);
        tvHeroChapterBadge.setText(getString(R.string.format_chapter_of_total,
                activeAudio.getCurrentChapter(), activeAudio.getLenChapters()));
        tvHeroChapterTime.setText(FileScanner.formatDuration(safePos)
                + " / " + FileScanner.formatDuration(totalMs));
        int pct = totalMs > 0
                ? (int) Math.min(100L, Math.max(0L, (safePos * 100L) / totalMs))
                : activeAudio.getProgressPercent();
        progressAudioHero.setProgress(pct);
        tvAudioHeroProgressLabel.setText(getString(R.string.format_percent_completed, pct));
        long remainingMs = Math.max(0L, totalMs - safePos);
        tvAudioHeroRemaining.setText(getString(R.string.format_remaining_time, FileScanner.formatDuration(remainingMs)));
    }

    private void renderAudiobooksList(@NonNull List<AudioBook> items) {
        listAudiobooks.removeAllViews();
        if (items.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (AudioBook item : items) {
            View row = inflater.inflate(R.layout.item_audiobook_row, listAudiobooks, false);
            ImageView ivCover = row.findViewById(R.id.ivAudioCover);
            TextView tvBadge = row.findViewById(R.id.tvAudioBadge);
            TextView tvTitle = row.findViewById(R.id.tvAudioTitle);
            TextView tvSubtitle = row.findViewById(R.id.tvAudioSubtitle);
            LinearProgressIndicator progress = row.findViewById(R.id.progressAudio);
            TextView tvRemaining = row.findViewById(R.id.tvAudioRemaining);
            ImageButton btnPlay = row.findViewById(R.id.btnAudioPlay);

            UiSessionHelper.loadCover(ivCover, item.getCoverUri());
            tvTitle.setText(item.getTitle());

            String sub = item.getAuthor();
            if (!item.getNarrator().isEmpty()) {
                sub = sub.isEmpty()
                        ? getString(R.string.format_narrator, item.getNarrator())
                        : sub + " • " + getString(R.string.format_narrator, item.getNarrator());
            }
            tvSubtitle.setText(sub);
            tvSubtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);

            if (Title.STATUS_COMPLETED.equals(item.getStatus())) {
                tvBadge.setText(R.string.status_completed);
            } else if (item.requiresChapterSelector()) {
                tvBadge.setText(R.string.status_to_start);
            } else {
                tvBadge.setText(R.string.status_in_progress);
            }

            progress.setProgress(item.getProgressPercent());
            long remMs = Math.max(0L, item.getTotalDurationMs() - item.getCurrentPositionMs());
            tvRemaining.setText(getString(R.string.format_percent, item.getProgressPercent())
                    + " • " + getString(R.string.format_remaining_time, FileScanner.formatDuration(remMs)));

            View.OnClickListener openFlow = v -> NavigationFlowHelper.openTitle(this, item);
            row.setOnClickListener(openFlow);
            btnPlay.setOnClickListener(openFlow);
            row.setOnLongClickListener(v -> {
                confirmDeleteAudioBook(item);
                return true;
            });
            listAudiobooks.addView(row);
        }
    }

    private void confirmDeleteAudioBook(@NonNull AudioBook item) {
        new AlertDialog.Builder(this)
                .setTitle(item.getTitle())
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    if (PlaybackSessionManager.getInstance().getActiveTitle() != null
                            && PlaybackSessionManager.getInstance().getActiveTitle().getId() == item.getId()) {
                        PlaybackSessionManager.getInstance().stopAndClearSession(this);
                    }
                    titleDao.deleteById(item.getId());
                    refreshAudiobooks();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
