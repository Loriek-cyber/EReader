package com.logininc.ereader.screen;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.BookmarkNote;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.BookmarkNoteDao;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

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

    private PlaybackSessionManager.SessionListener miniPlayerListener;

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
        if (miniPlayerListener != null) {
            PlaybackSessionManager.getInstance().removeListener(miniPlayerListener);
            miniPlayerListener = null;
        }
    }

    private void bindViews() {
        findViewById(R.id.btnAudioChaptersBack).setOnClickListener(v -> finish());
        tvTitle = findViewById(R.id.tvAudioChaptersTitle);

        chipChapters = findViewById(R.id.chipAudioChapters);
        chipBookmarks = findViewById(R.id.chipAudioBookmarks);
        chipNotes = findViewById(R.id.chipAudioNotes);
        etSearch = findViewById(R.id.etAudioChapterSearch);

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

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderActiveTab();
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

        tvTitle.setText(audioBook.getTitle());
        tvOverallPercent.setText(getString(R.string.format_percent_completed, audioBook.getProgressPercent()));
        progressOverall.setProgress(audioBook.getProgressPercent());

        List<Chapter> allChapters = chapterDao.getChaptersForTitle(audioBook.getId());
        rootChapters = chapterDao.getChapterTreeForTitle(audioBook.getId());
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
    }

    private int resolveTargetChapterIndex() {
        int targetChapterIndex = audioBook.getCurrentChapter();
        if (audioBook.isCurrentChapterCompleted() && targetChapterIndex < audioBook.getLenChapters()) {
            targetChapterIndex = targetChapterIndex + 1;
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
            progressActive.setProgress(currentCh.getProgressPercent());

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
            renderAudioChapterTreeNodes(displayTree, containerItems, 0, filtering, inflater);
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

    private void renderAudioChapterTreeNodes(
            @NonNull List<ChapterTreeHelper.TreeNode> nodes,
            @NonNull LinearLayout container,
            int depth,
            boolean filtering,
            @NonNull LayoutInflater inflater
    ) {
        Set<Integer> allExpandable = ChapterTreeHelper.collectExpandableIndices(rootChapters);
        float density = getResources().getDisplayMetrics().density;

        for (ChapterTreeHelper.TreeNode node : nodes) {
            Chapter ch = node.chapter;
            View row = inflater.inflate(R.layout.item_chapter_audio, container, false);

            MaterialCardView cardRow = row.findViewById(R.id.cardAudioChapterRow);
            ImageView ivIcon = row.findViewById(R.id.ivAudioChapterIcon);
            TextView tvNum = row.findViewById(R.id.tvAudioChapterNum);
            TextView tvRowTitle = row.findViewById(R.id.tvAudioChapterTitle);
            TextView tvMeta = row.findViewById(R.id.tvAudioChapterMeta);
            ImageButton btnExpand = row.findViewById(R.id.btnAudioChapterExpand);
            ImageButton btnAction = row.findViewById(R.id.btnAudioChapterAction);
            View layoutChildrenWrapper = row.findViewById(R.id.layoutAudioChapterChildrenWrapper);
            View viewTreeGuide = row.findViewById(R.id.viewAudioChapterTreeGuide);
            LinearLayout containerChildren = row.findViewById(R.id.containerAudioChapterChildren);

            if (depth > 0) {
                tvRowTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            }
            tvRowTitle.setText(ch.getChapterTitle());

            String durLabel = !ch.getPageOrDurationLabel().isEmpty()
                    ? ch.getPageOrDurationLabel()
                    : FileScanner.formatDuration(ch.getAudioDurationMs());

            boolean isActiveChapter = ch.getChapterIndex() == audioBook.getCurrentChapter()
                    && Title.STATUS_IN_PROGRESS.equals(audioBook.getStatus());

            String statusPart;
            if (ch.isCompleted()) {
                ivIcon.setVisibility(View.VISIBLE);
                tvNum.setVisibility(View.GONE);
                ivIcon.setImageResource(R.drawable.ic_check_circle);
                statusPart = getString(R.string.chapter_status_completed);
            } else if (isActiveChapter) {
                ivIcon.setVisibility(View.VISIBLE);
                tvNum.setVisibility(View.GONE);
                ivIcon.setImageResource(R.drawable.ic_equalizer);
                cardRow.setStrokeColor(getColor(R.color.border_active));
                cardRow.setStrokeWidth(Math.round(1.5f * density));
                statusPart = getString(R.string.format_percent, ch.getProgressPercent());
            } else {
                ivIcon.setVisibility(View.GONE);
                tvNum.setVisibility(View.VISIBLE);
                tvNum.setText(String.format(Locale.ROOT, "%02d", ch.getChapterIndex()));
                statusPart = getString(R.string.chapter_status_to_start);
            }

            if (ch.hasChildren()) {
                int childCount = ch.getChildren().size();
                String sectionsLabel = getResources().getQuantityString(
                        R.plurals.chapter_sections_count, childCount, childCount);
                tvMeta.setText(durLabel + " • " + sectionsLabel + " • " + statusPart);
            } else {
                tvMeta.setText(durLabel + " • " + statusPart);
            }

            boolean isPlayingThis = isCurrentlyPlayingChapter(ch.getChapterIndex());
            btnAction.setImageResource(isPlayingThis ? R.drawable.ic_pause : R.drawable.ic_play_circle);

            View.OnClickListener playListener = v -> openChapterInPlayer(ch.getChapterIndex());
            cardRow.setOnClickListener(playListener);
            btnAction.setOnClickListener(playListener);

            UiSessionHelper.bindRecursiveChapterChildren(
                    node,
                    depth,
                    filtering,
                    expandedChapterIndices,
                    btnExpand,
                    null,
                    layoutChildrenWrapper,
                    viewTreeGuide,
                    containerChildren,
                    () -> UiSessionHelper.updateExpandAllButtonLabel(
                            btnToggleExpandAll, allExpandable, expandedChapterIndices),
                    (children, childContainer, nextDepth) ->
                            renderAudioChapterTreeNodes(children, childContainer, nextDepth, filtering, inflater)
            );

            container.addView(row);
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
        Chapter targetChapter = chapterDao.getChapter(audioBook.getId(), chapterIndex);
        if (targetChapter != null) {
            audioBook.setCurrentPositionMs(targetChapter.getAudioStartMs());
        }
        audioBook.setCurrentChapter(chapterIndex);
        audioBook.setCurrentChapterCompleted(false);
        audioBook.setStatus(Title.STATUS_IN_PROGRESS);
        audioBook.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(audioBook);

        NavigationFlowHelper.openReaderOrPlayer(this, audioBook, chapterIndex);
        finish();
    }
}
