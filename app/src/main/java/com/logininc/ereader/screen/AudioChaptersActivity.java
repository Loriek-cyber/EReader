package com.logininc.ereader.screen;

import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
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
import com.logininc.ereader.util.AudnexusClient;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.MetadataJsonParser;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;
import com.logininc.ereader.view.RecursiveAudioChapterTreeView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AudioChaptersActivity extends AppCompatActivity {

    private static final int TAB_CHAPTERS = 0;
    private static final int TAB_BOOKMARKS = 1;
    private static final int TAB_NOTES = 2;

    private TitleDao titleDao;
    private ChapterDao chapterDao;
    private BookmarkNoteDao bookmarkNoteDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;

    private int titleId;
    private AudioBook audioBook;
    private int selectedTab = TAB_CHAPTERS;

    private TextView tvTitle;
    private MaterialButton chipChapters;
    private MaterialButton chipBookmarks;
    private MaterialButton chipNotes;
    private EditText etSearch;

    private TextView tvAudioMetadataSummary;
    private TextView tvOverallPercent;
    private LinearProgressIndicator progressOverall;

    private View cardActiveChapter;
    private TextView tvActiveBadge;
    private TextView tvActiveTime;
    private TextView tvActiveTitle;
    private ImageButton btnActivePlayPause;
    private LinearProgressIndicator progressActive;

    private View layoutTreeControls;
    private TextView tvTreeSummary;
    private MaterialButton btnToggleExpandAll;
    private TextView tvEmpty;
    private LinearLayout containerItems;

    private List<Chapter> rootChapters = new ArrayList<>();
    private final Set<Integer> expandedChapterIndices = new HashSet<>();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedSearchRunnable = this::renderActiveTab;
    private boolean attemptedChapterRefresh = false;

    private PlaybackSessionManager.SessionListener miniPlayerListener;

    private final ActivityResultLauncher<String[]> openMetadataJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importMetadataJson(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        appliedThemeMode = settingsDao.getThemeMode();
        ThemeHelper.applyTheme(this, false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_chapters);

        titleId = getIntent().getIntExtra(NavigationFlowHelper.EXTRA_TITLE_ID, -1);
        titleDao = new TitleDao(this);
        chapterDao = new ChapterDao(this);
        bookmarkNoteDao = new BookmarkNoteDao(this);

        bindViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsDao.getThemeMode().equals(appliedThemeMode)) {
            recreate();
            return;
        }
        miniPlayerListener = UiSessionHelper.attachMiniPlayer(this);
        loadAndRender();
    }

    @Override
    protected void onPause() {
        super.onPause();
        searchHandler.removeCallbacks(debouncedSearchRunnable);
        if (miniPlayerListener != null) {
            PlaybackSessionManager.getInstance().removeListener(miniPlayerListener);
            miniPlayerListener = null;
        }
    }

    private void bindViews() {
        findViewById(R.id.btnAudioChaptersBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnImportAudioMetadataJson).setOnClickListener(v -> showMetadataSourceDialog());
        tvTitle = findViewById(R.id.tvAudioChaptersTitle);

        chipChapters = findViewById(R.id.chipAudioChapters);
        chipBookmarks = findViewById(R.id.chipAudioBookmarks);
        chipNotes = findViewById(R.id.chipAudioNotes);
        etSearch = findViewById(R.id.etAudioChapterSearch);

        tvAudioMetadataSummary = findViewById(R.id.tvAudioMetadataSummary);
        tvOverallPercent = findViewById(R.id.tvAudioOverallPercent);
        progressOverall = findViewById(R.id.progressAudioOverall);

        cardActiveChapter = findViewById(R.id.cardActiveAudioChapter);
        tvActiveBadge = findViewById(R.id.tvActiveChapterBadge);
        tvActiveTime = findViewById(R.id.tvActiveChapterTime);
        tvActiveTitle = findViewById(R.id.tvAudioActiveChapterTitle);
        btnActivePlayPause = findViewById(R.id.btnActiveChapterPause);
        progressActive = findViewById(R.id.progressAudioActiveChapter);

        layoutTreeControls = findViewById(R.id.layoutAudioTreeControls);
        tvTreeSummary = findViewById(R.id.tvAudioTreeSummary);
        btnToggleExpandAll = findViewById(R.id.btnAudioToggleExpandAll);
        tvEmpty = findViewById(R.id.tvAudioChaptersEmpty);
        containerItems = findViewById(R.id.containerAudioChapters);

        chipChapters.setOnClickListener(v -> switchTab(TAB_CHAPTERS));
        chipBookmarks.setOnClickListener(v -> switchTab(TAB_BOOKMARKS));
        chipNotes.setOnClickListener(v -> switchTab(TAB_NOTES));

        UiSessionHelper.bindSearchClearAndImeAction(etSearch, null);
        UiSessionHelper.attachImeSafeBottomDock(
                findViewById(android.R.id.content),
                findViewById(R.id.bottomDockAudioChapters),
                null
        );

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchHandler.removeCallbacks(debouncedSearchRunnable);
                searchHandler.postDelayed(debouncedSearchRunnable, 200L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void switchTab(int tab) {
        selectedTab = tab;
        updateChipAppearance();
        renderActiveTab();
    }

    private void updateChipAppearance() {
        styleChip(chipChapters, selectedTab == TAB_CHAPTERS);
        styleChip(chipBookmarks, selectedTab == TAB_BOOKMARKS);
        styleChip(chipNotes, selectedTab == TAB_NOTES);
    }

    private void styleChip(@NonNull MaterialButton button, boolean active) {
        if (active) {
            int primaryContainer = resolveAttrColor(com.google.android.material.R.attr.colorPrimaryContainer);
            int white = getColor(R.color.white);
            button.setBackgroundTintList(ColorStateList.valueOf(primaryContainer));
            button.setTextColor(white);
            button.setIconTint(ColorStateList.valueOf(white));
        } else {
            int chipBg = resolveAttrColor(R.attr.ereaderChipBg);
            int muted = resolveAttrColor(R.attr.ereaderTextMuted);
            button.setBackgroundTintList(ColorStateList.valueOf(chipBg));
            button.setTextColor(muted);
            button.setIconTint(ColorStateList.valueOf(muted));
        }
    }

    private int resolveAttrColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    private void loadAndRender() {
        Title loaded = titleDao.getById(titleId);
        if (!(loaded instanceof AudioBook)) {
            finish();
            return;
        }
        audioBook = (AudioBook) loaded;
        PlaybackSessionManager session = PlaybackSessionManager.getInstance();
        Title activeSessionTitle = session.getActiveTitle();
        if (activeSessionTitle instanceof AudioBook && activeSessionTitle.getId() == audioBook.getId()) {
            long livePos = session.getCurrentPositionMs();
            long liveDur = Math.max(session.getTotalDurationMs(), audioBook.getTotalDurationMs());
            audioBook.setCurrentChapter(activeSessionTitle.getCurrentChapter());
            audioBook.setCurrentPositionMs(livePos);
            if (liveDur > 0L) {
                audioBook.setTotalDurationMs(liveDur);
                audioBook.setProgressPercent(PlaybackSessionManager.computeProgressPercent(livePos, liveDur));
            }
        } else if (audioBook.getTotalDurationMs() > 0L && audioBook.getCurrentPositionMs() > 0L) {
            audioBook.setProgressPercent(PlaybackSessionManager.computeProgressPercent(
                    audioBook.getCurrentPositionMs(), audioBook.getTotalDurationMs()));
        }

        tvTitle.setText(audioBook.getTitle());
        StringBuilder metaSummary = new StringBuilder();
        if (!audioBook.getAuthor().isEmpty()) {
            metaSummary.append(audioBook.getAuthor());
        }
        if (!audioBook.getNarrator().isEmpty()) {
            if (metaSummary.length() > 0) {
                metaSummary.append(" • ");
            }
            metaSummary.append(getString(R.string.format_narrator, audioBook.getNarrator()));
        }
        if (audioBook.getTotalDurationMs() > 0L) {
            if (metaSummary.length() > 0) {
                metaSummary.append(" • ");
            }
            metaSummary.append(FileScanner.formatDuration(audioBook.getTotalDurationMs()));
        }
        if (metaSummary.length() > 0) {
            tvAudioMetadataSummary.setText(metaSummary.toString());
            tvAudioMetadataSummary.setVisibility(View.VISIBLE);
        } else {
            tvAudioMetadataSummary.setVisibility(View.GONE);
        }
        tvOverallPercent.setText(getString(R.string.format_percent_completed, audioBook.getProgressPercent()));
        progressOverall.setProgress(audioBook.getProgressPercent());

        List<Chapter> allChapters = chapterDao.getChapterSummariesForTitle(audioBook.getId());
        rootChapters = ChapterDao.buildTreeFromFlat(allChapters);
        if (Title.STATUS_IN_PROGRESS.equals(audioBook.getStatus())) {
            expandedChapterIndices.addAll(
                    ChapterTreeHelper.collectAncestorIndices(rootChapters, resolveTargetChapterIndex())
            );
        }

        int bmCount = bookmarkNoteDao.countByTitleAndType(audioBook.getId(), BookmarkNote.TYPE_BOOKMARK);
        int noteCount = bookmarkNoteDao.countByTitleAndType(audioBook.getId(), BookmarkNote.TYPE_NOTE);

        chipChapters.setText(getString(R.string.tab_chapters_fmt, allChapters.size()));
        chipBookmarks.setText(getString(R.string.tab_bookmarks_fmt, bmCount));
        chipNotes.setText(getString(R.string.tab_notes_fmt, noteCount));

        updateChipAppearance();
        bindActiveChapterCard(allChapters);
        renderActiveTab();

        if (!attemptedChapterRefresh && audioBook.getLenChapters() <= 1) {
            attemptedChapterRefresh = true;
            final AudioBook targetBook = audioBook;
            new Thread(() -> {
                if (FileScanner.refreshAudiobookChaptersIfNeeded(getApplicationContext(), targetBook)) {
                    PlaybackSessionManager.getInstance().invalidateChapterCache(targetBook.getId());
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            loadAndRender();
                        }
                    });
                }
            }).start();
        }
    }

    private int resolveTargetChapterIndex() {
        int targetChapterIndex = ChapterTreeHelper.resolveNearestLeafIndex(rootChapters, audioBook.getCurrentChapter());
        if (audioBook.isCurrentChapterCompleted()) {
            int nextLeaf = ChapterTreeHelper.findNextLeafIndex(rootChapters, targetChapterIndex);
            if (nextLeaf > 0) {
                targetChapterIndex = nextLeaf;
            }
        }
        return targetChapterIndex;
    }

    private void bindActiveChapterCard(@NonNull List<Chapter> allChapters) {
        int targetChapterIndex = resolveTargetChapterIndex();
        Chapter currentCh = null;
        for (Chapter ch : allChapters) {
            if (ch.getChapterIndex() == targetChapterIndex) {
                currentCh = ch;
                break;
            }
        }
        if (currentCh == null && !allChapters.isEmpty()) {
            currentCh = allChapters.get(0);
        }

        if (currentCh != null && Title.STATUS_IN_PROGRESS.equals(audioBook.getStatus())) {
            cardActiveChapter.setVisibility(View.VISIBLE);
            tvActiveBadge.setText(getString(R.string.chapter_active_badge_fmt, currentCh.getChapterIndex()));

            String durationText = !currentCh.getPageOrDurationLabel().isEmpty()
                    ? currentCh.getPageOrDurationLabel()
                    : FileScanner.formatDuration(currentCh.getAudioDurationMs());
            tvActiveTime.setText(durationText);
            tvActiveTitle.setText(currentCh.getChapterTitle());
            int activeChPct = currentCh.getProgressPercent();
            if (currentCh.getChapterIndex() == audioBook.getCurrentChapter()
                    && currentCh.getAudioDurationMs() > 0L) {
                long relMs = Math.max(0L, audioBook.getCurrentPositionMs() - currentCh.getAudioStartMs());
                activeChPct = Math.max(activeChPct,
                        PlaybackSessionManager.computeProgressPercent(relMs, currentCh.getAudioDurationMs()));
            }
            progressActive.setProgress(activeChPct);

            boolean isPlayingThis = isCurrentlyPlayingChapter(currentCh.getChapterIndex());
            btnActivePlayPause.setImageResource(isPlayingThis ? R.drawable.ic_pause : R.drawable.ic_play);

            final int chIndex = currentCh.getChapterIndex();
            View.OnClickListener openListener = v -> openChapterInPlayer(chIndex);
            cardActiveChapter.setOnClickListener(openListener);
            btnActivePlayPause.setOnClickListener(openListener);
        } else {
            cardActiveChapter.setVisibility(View.GONE);
        }
    }

    private void renderActiveTab() {
        containerItems.removeAllViews();
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        LayoutInflater inflater = LayoutInflater.from(this);

        if (selectedTab == TAB_CHAPTERS) {
            boolean filtering = !query.isEmpty();
            UiSessionHelper.bindChapterTreeControls(
                    this,
                    rootChapters,
                    !filtering,
                    layoutTreeControls,
                    tvTreeSummary,
                    btnToggleExpandAll,
                    expandedChapterIndices,
                    this::renderActiveTab
            );
            List<ChapterTreeHelper.TreeNode> displayTree = ChapterTreeHelper.buildDisplayTree(rootChapters, query);
            tvEmpty.setVisibility(displayTree.isEmpty() ? View.VISIBLE : View.GONE);
            if (!displayTree.isEmpty()) {
                Set<Integer> allExpandable = ChapterTreeHelper.collectExpandableIndices(rootChapters);
                RecursiveAudioChapterTreeView treeView = new RecursiveAudioChapterTreeView(this);
                treeView.renderTree(
                        displayTree,
                        audioBook,
                        expandedChapterIndices,
                        allExpandable,
                        filtering,
                        this::isCurrentlyPlayingChapter,
                        () -> UiSessionHelper.updateExpandAllButtonLabel(
                                btnToggleExpandAll, allExpandable, expandedChapterIndices),
                        ch -> openChapterInPlayer(ch.getChapterIndex())
                );
                containerItems.addView(treeView);
            }
            return;
        }

        layoutTreeControls.setVisibility(View.GONE);
        String entryType = selectedTab == TAB_BOOKMARKS
                ? BookmarkNote.TYPE_BOOKMARK
                : BookmarkNote.TYPE_NOTE;
        List<BookmarkNote> all = bookmarkNoteDao.getByTitleAndType(audioBook.getId(), entryType);
        List<BookmarkNote> filtered = new ArrayList<>();
        for (BookmarkNote item : all) {
            if (query.isEmpty()
                    || item.getNoteText().toLowerCase(Locale.ROOT).contains(query)
                    || item.getPositionLabel().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(item);
            }
        }
        tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        for (BookmarkNote item : filtered) {
            View row = inflater.inflate(R.layout.item_chapter_audio, containerItems, false);
            MaterialCardView cardRow = row.findViewById(R.id.cardAudioChapterRow);
            ImageView ivIcon = row.findViewById(R.id.ivAudioChapterIcon);
            TextView tvNum = row.findViewById(R.id.tvAudioChapterNum);
            TextView tvRowTitle = row.findViewById(R.id.tvAudioChapterTitle);
            TextView tvMeta = row.findViewById(R.id.tvAudioChapterMeta);
            ImageButton btnAction = row.findViewById(R.id.btnAudioChapterAction);

            tvNum.setVisibility(View.GONE);
            ivIcon.setVisibility(View.VISIBLE);
            ivIcon.setImageResource(selectedTab == TAB_BOOKMARKS ? R.drawable.ic_bookmark_filled : R.drawable.ic_note);

            tvRowTitle.setText(item.getNoteText().isEmpty()
                    ? getString(R.string.format_chapter_n, item.getChapterIndex())
                    : item.getNoteText());
            String meta = item.getPositionLabel().isEmpty()
                    ? fmt.format(new Date(item.getCreatedAt()))
                    : item.getPositionLabel() + " • " + fmt.format(new Date(item.getCreatedAt()));
            tvMeta.setText(meta);

            View.OnClickListener openListener = v -> openChapterInPlayer(item.getChapterIndex());
            cardRow.setOnClickListener(openListener);
            btnAction.setOnClickListener(openListener);
            cardRow.setOnLongClickListener(v -> {
                confirmDeleteEntry(item);
                return true;
            });
            containerItems.addView(row);
        }
    }

    private boolean isCurrentlyPlayingChapter(int chapterIndex) {
        PlaybackSessionManager manager = PlaybackSessionManager.getInstance();
        Title active = manager.getActiveTitle();
        return manager.isPlaying()
                && active instanceof AudioBook
                && active.getId() == audioBook.getId()
                && active.getCurrentChapter() == chapterIndex;
    }

    private void confirmDeleteEntry(@NonNull BookmarkNote item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_delete)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    bookmarkNoteDao.deleteById(item.getId());
                    loadAndRender();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void openChapterInPlayer(int chapterIndex) {
        boolean switchingChapter = chapterIndex != audioBook.getCurrentChapter();
        Chapter targetChapter = chapterDao.getChapter(audioBook.getId(), chapterIndex);
        if (targetChapter != null) {
            long chStart = Math.max(0L, targetChapter.getAudioStartMs());
            long chEnd = chStart + Math.max(0L, targetChapter.getAudioDurationMs());
            long savedPos = audioBook.getCurrentPositionMs();
            boolean outOfChapterBounds = savedPos < chStart
                    || (targetChapter.getAudioDurationMs() > 0 && savedPos >= chEnd);
            if (switchingChapter || outOfChapterBounds) {
                audioBook.setCurrentPositionMs(chStart);
            }
        }
        audioBook.setCurrentChapter(chapterIndex);
        audioBook.setCurrentChapterCompleted(false);
        audioBook.setStatus(Title.STATUS_IN_PROGRESS);
        audioBook.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(audioBook);

        NavigationFlowHelper.openReaderOrPlayer(this, audioBook, chapterIndex);
        finish();
    }

    private void showMetadataSourceDialog() {
        String[] options = {
                getString(R.string.metadata_source_audnexus),
                getString(R.string.metadata_source_json),
                getString(R.string.metadata_source_file)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.metadata_source_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showAudnexusSearchDialog();
                    } else if (which == 1) {
                        openMetadataJsonLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
                    } else {
                        reloadEmbeddedFileMetadata();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void reloadEmbeddedFileMetadata() {
        if (audioBook == null) {
            return;
        }
        final AudioBook target = audioBook;
        new Thread(() -> {
            MetadataJsonParser.ApplyResult res =
                    FileScanner.reloadAudiobookEmbeddedMetadata(getApplicationContext(), target);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (res.success) {
                    Toast.makeText(
                            this,
                            getString(R.string.metadata_file_reloaded_fmt, res.chapterCount),
                            Toast.LENGTH_SHORT
                    ).show();
                    loadAndRender();
                } else {
                    Toast.makeText(this, R.string.metadata_file_reload_error, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showAudnexusSearchDialog() {
        if (audioBook == null) {
            return;
        }
        int pad = Math.round(20f * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etQuery = new EditText(this);
        etQuery.setHint(R.string.audnexus_query_hint);
        etQuery.setSingleLine(true);
        etQuery.setText(audioBook.getTitle());
        if (etQuery.getText() != null) {
            etQuery.setSelection(etQuery.getText().length());
        }
        container.addView(etQuery);

        TextView tvRegion = new TextView(this);
        tvRegion.setText(R.string.audnexus_region_label);
        tvRegion.setPadding(0, pad / 2, 0, pad / 4);
        container.addView(tvRegion);

        Spinner spRegion = new Spinner(this);
        String[] regionLabels = new String[AudnexusClient.SUPPORTED_REGIONS.length];
        String defaultRegion = "en".equalsIgnoreCase(settingsDao.getLanguageTag()) ? "us" : "it";
        int defaultPos = 0;
        for (int i = 0; i < AudnexusClient.SUPPORTED_REGIONS.length; i++) {
            String r = AudnexusClient.SUPPORTED_REGIONS[i];
            regionLabels[i] = r.toUpperCase(Locale.ROOT);
            if (r.equals(defaultRegion)) {
                defaultPos = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, regionLabels);
        spRegion.setAdapter(adapter);
        spRegion.setSelection(defaultPos);
        container.addView(spRegion);

        new AlertDialog.Builder(this)
                .setTitle(R.string.audnexus_dialog_title)
                .setView(container)
                .setPositiveButton(R.string.audnexus_search_btn, (d, w) -> {
                    String query = etQuery.getText() != null ? etQuery.getText().toString().trim() : "";
                    String region = AudnexusClient.SUPPORTED_REGIONS[spRegion.getSelectedItemPosition()];
                    if (!query.isEmpty()) {
                        runAudnexusSearch(query, region);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void runAudnexusSearch(@NonNull String query, @NonNull String region) {
        Toast.makeText(this, R.string.audnexus_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<AudnexusClient.SearchCandidate> results = AudnexusClient.search(query, region);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (results.isEmpty()) {
                    Toast.makeText(this, R.string.audnexus_no_results, Toast.LENGTH_LONG).show();
                    return;
                }
                boolean autoPickFirst = SettingsDao.METADATA_SOURCE_AUDNEXUS.equals(
                        settingsDao.getAudioMetadataSource());
                if (autoPickFirst || (results.size() == 1 && AudnexusClient.isAsin(query))) {
                    applyAudnexusCandidate(results.get(0), region);
                    return;
                }
                String[] labels = new String[results.size()];
                for (int i = 0; i < results.size(); i++) {
                    labels[i] = results.get(i).formatDisplayLabel();
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.audnexus_pick_result)
                        .setItems(labels, (dialog, which) -> applyAudnexusCandidate(results.get(which), region))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
        }).start();
    }

    private void applyAudnexusCandidate(@NonNull AudnexusClient.SearchCandidate candidate, @NonNull String region) {
        if (audioBook == null) {
            return;
        }
        Toast.makeText(this, R.string.audnexus_applying, Toast.LENGTH_SHORT).show();
        final AudioBook target = audioBook;
        new Thread(() -> {
            MetadataJsonParser.ApplyResult res =
                    AudnexusClient.fetchAndApply(getApplicationContext(), target, candidate.asin, region);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (res.success) {
                    String msg = res.chapterCount > 0
                            ? getString(R.string.audnexus_applied_fmt, res.chapterCount)
                            : getString(R.string.audnexus_applied_no_chapters);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    loadAndRender();
                } else {
                    Toast.makeText(this, R.string.audnexus_error, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void importMetadataJson(@NonNull Uri jsonUri) {
        if (audioBook == null) {
            return;
        }
        final AudioBook target = audioBook;
        new Thread(() -> {
            MetadataJsonParser.ApplyResult res =
                    MetadataJsonParser.applyJsonUriToTitle(getApplicationContext(), target, jsonUri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (res.success) {
                    String msg = res.chapterCount > 0
                            ? getString(R.string.metadata_json_imported_fmt, res.chapterCount)
                            : getString(R.string.metadata_json_imported_no_chapters);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    loadAndRender();
                } else {
                    Toast.makeText(this, R.string.metadata_json_error, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
