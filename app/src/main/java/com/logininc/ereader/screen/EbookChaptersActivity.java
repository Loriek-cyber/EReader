package com.logininc.ereader.screen;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.BookmarkNote;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.BookmarkNoteDao;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;
import com.logininc.ereader.view.RecursiveChapterTreeView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EbookChaptersActivity extends AppCompatActivity {

    private static final int TAB_CHAPTERS = 0;
    private static final int TAB_BOOKMARKS = 1;
    private static final int TAB_NOTES = 2;

    private TitleDao titleDao;
    private ChapterDao chapterDao;
    private BookmarkNoteDao bookmarkNoteDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;

    private int titleId;
    private Book book;
    private int selectedTab = TAB_CHAPTERS;

    private TextView tvHeaderSub;
    private ImageView ivCover;
    private TextView tvSummaryTitle;
    private TextView tvSummaryMeta;
    private TextView tvSummaryPercent;
    private TextView tvSummaryPages;
    private LinearProgressIndicator progressSummary;

    private MaterialButton tabChapters;
    private MaterialButton tabBookmarks;
    private MaterialButton tabNotes;
    private EditText etSearch;

    private View cardActiveChapter;
    private TextView tvActiveStatus;
    private TextView tvActiveBadge;
    private TextView tvActiveTitle;
    private TextView tvActiveProgress;
    private LinearProgressIndicator progressActive;
    private MaterialButton btnResumeChapter;

    private View layoutTreeControls;
    private TextView tvTreeSummary;
    private MaterialButton btnToggleExpandAll;
    private TextView tvEmpty;
    private LinearLayout containerItems;
    private TextView tvBottomCurrent;
    private MaterialButton btnContinue;

    private List<Chapter> rootChapters = new ArrayList<>();
    private final Set<Integer> expandedChapterIndices = new HashSet<>();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedSearchRunnable = this::renderActiveTab;

    private PlaybackSessionManager.SessionListener miniPlayerListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        appliedThemeMode = settingsDao.getThemeMode();
        ThemeHelper.applyTheme(this, true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ebook_chapters);

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
        findViewById(R.id.btnChaptersBack).setOnClickListener(v -> finish());
        tvHeaderSub = findViewById(R.id.tvEbookChaptersHeaderSub);
        ivCover = findViewById(R.id.ivEbookSummaryCover);
        tvSummaryTitle = findViewById(R.id.tvEbookSummaryTitle);
        tvSummaryMeta = findViewById(R.id.tvEbookSummaryMeta);
        tvSummaryPercent = findViewById(R.id.tvEbookSummaryPercent);
        tvSummaryPages = findViewById(R.id.tvEbookSummaryPages);
        progressSummary = findViewById(R.id.progressEbookSummary);

        tabChapters = findViewById(R.id.tabEbookChapters);
        tabBookmarks = findViewById(R.id.tabEbookBookmarks);
        tabNotes = findViewById(R.id.tabEbookNotes);
        etSearch = findViewById(R.id.etEbookChapterSearch);

        cardActiveChapter = findViewById(R.id.cardActiveEbookChapter);
        tvActiveStatus = findViewById(R.id.tvActiveChapterStatus);
        tvActiveBadge = findViewById(R.id.tvActiveChapterBadge);
        tvActiveTitle = findViewById(R.id.tvActiveChapterTitle);
        tvActiveProgress = findViewById(R.id.tvActiveChapterProgress);
        progressActive = findViewById(R.id.progressActiveChapter);
        btnResumeChapter = findViewById(R.id.btnResumeChapter);

        layoutTreeControls = findViewById(R.id.layoutEbookTreeControls);
        tvTreeSummary = findViewById(R.id.tvEbookTreeSummary);
        btnToggleExpandAll = findViewById(R.id.btnEbookToggleExpandAll);
        tvEmpty = findViewById(R.id.tvEbookChaptersEmpty);
        containerItems = findViewById(R.id.containerEbookChapters);
        tvBottomCurrent = findViewById(R.id.tvBottomCurrentChapter);
        btnContinue = findViewById(R.id.btnContinueFromIndex);

        tabChapters.setOnClickListener(v -> switchTab(TAB_CHAPTERS));
        tabBookmarks.setOnClickListener(v -> switchTab(TAB_BOOKMARKS));
        tabNotes.setOnClickListener(v -> switchTab(TAB_NOTES));

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
        updateTabButtonsAppearance();
        renderActiveTab();
    }

    private void updateTabButtonsAppearance() {
        styleTabButton(tabChapters, selectedTab == TAB_CHAPTERS);
        styleTabButton(tabBookmarks, selectedTab == TAB_BOOKMARKS);
        styleTabButton(tabNotes, selectedTab == TAB_NOTES);
    }

    private void styleTabButton(@NonNull MaterialButton button, boolean active) {
        if (active) {
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

    private void loadAndRender() {
        Title loaded = titleDao.getById(titleId);
        if (!(loaded instanceof Book)) {
            finish();
            return;
        }
        book = (Book) loaded;

        String headerSub = book.getAuthor().isEmpty()
                ? book.getTitle()
                : book.getTitle() + " — " + book.getAuthor();
        tvHeaderSub.setText(headerSub);

        UiSessionHelper.loadCover(ivCover, book.getCoverUri());
        tvSummaryTitle.setText(book.getTitle());
        tvSummaryMeta.setText(book.getAuthor());
        tvSummaryMeta.setVisibility(book.getAuthor().isEmpty() ? View.GONE : View.VISIBLE);
        tvSummaryPercent.setText(getString(R.string.format_percent_completed, book.getProgressPercent()));
        tvSummaryPages.setText(getString(R.string.format_pages_of, book.getCurrentPage(), book.getTotalPages()));
        progressSummary.setProgress(book.getProgressPercent());

        List<Chapter> allChapters = chapterDao.getChapterSummariesForTitle(book.getId());
        rootChapters = ChapterDao.buildTreeFromFlat(allChapters);
        if (Title.STATUS_IN_PROGRESS.equals(book.getStatus())) {
            expandedChapterIndices.addAll(
                    ChapterTreeHelper.collectAncestorIndices(rootChapters, resolveTargetChapterIndex())
            );
        }

        int bmCount = bookmarkNoteDao.countByTitleAndType(book.getId(), BookmarkNote.TYPE_BOOKMARK);
        int noteCount = bookmarkNoteDao.countByTitleAndType(book.getId(), BookmarkNote.TYPE_NOTE);

        tabChapters.setText(getString(R.string.tab_chapters_fmt, allChapters.size()));
        tabBookmarks.setText(getString(R.string.tab_bookmarks_fmt, bmCount));
        tabNotes.setText(getString(R.string.tab_notes_fmt, noteCount));

        updateTabButtonsAppearance();
        bindActiveChapterAndFooter(allChapters);
        renderActiveTab();
    }

    private int resolveTargetChapterIndex() {
        int targetChapterIndex = ChapterTreeHelper.resolveNearestLeafIndex(rootChapters, book.getCurrentChapter());
        if (book.isCurrentChapterCompleted()) {
            int nextLeaf = ChapterTreeHelper.findNextLeafIndex(rootChapters, targetChapterIndex);
            if (nextLeaf > 0) {
                targetChapterIndex = nextLeaf;
            }
        }
        return targetChapterIndex;
    }

    private void bindActiveChapterAndFooter(@NonNull List<Chapter> allChapters) {
        int targetChapterIndex = resolveTargetChapterIndex();
        Chapter currentCh = null;
        for (Chapter c : allChapters) {
            if (c.getChapterIndex() == targetChapterIndex) {
                currentCh = c;
                break;
            }
        }
        if (currentCh == null && !allChapters.isEmpty()) {
            currentCh = allChapters.get(0);
        }

        if (currentCh != null && Title.STATUS_IN_PROGRESS.equals(book.getStatus())) {
            cardActiveChapter.setVisibility(View.VISIBLE);
            String pageStr = getString(R.string.format_pages_of, book.getCurrentPage(), book.getTotalPages());
            tvActiveStatus.setText(getString(R.string.chapter_active_status_fmt, pageStr));
            tvActiveBadge.setText(getString(R.string.chapter_active_badge_fmt, currentCh.getChapterIndex()));
            tvActiveTitle.setText(currentCh.getChapterTitle());
            tvActiveProgress.setText(getString(R.string.format_percent, currentCh.getProgressPercent()));
            progressActive.setProgress(currentCh.getProgressPercent());
            final int chIndex = currentCh.getChapterIndex();
            View.OnClickListener resumeListener = v -> openChapterInReader(chIndex);
            cardActiveChapter.setOnClickListener(resumeListener);
            btnResumeChapter.setOnClickListener(resumeListener);
        } else {
            cardActiveChapter.setVisibility(View.GONE);
        }

        final int continueIndex = currentCh != null ? currentCh.getChapterIndex() : 1;
        tvBottomCurrent.setText(currentCh != null
                ? getString(R.string.chapter_active_badge_fmt, continueIndex) + " — " + currentCh.getChapterTitle()
                : getString(R.string.format_chapter_n, continueIndex));
        btnContinue.setOnClickListener(v -> openChapterInReader(continueIndex));
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
                RecursiveChapterTreeView treeView = new RecursiveChapterTreeView(this);
                treeView.renderTree(
                        displayTree,
                        book,
                        expandedChapterIndices,
                        allExpandable,
                        filtering,
                        () -> UiSessionHelper.updateExpandAllButtonLabel(
                                btnToggleExpandAll, allExpandable, expandedChapterIndices),
                        ch -> openChapterInReader(ch.getChapterIndex())
                );
                containerItems.addView(treeView);
            }
            return;
        }

        layoutTreeControls.setVisibility(View.GONE);
        String entryType = selectedTab == TAB_BOOKMARKS
                ? BookmarkNote.TYPE_BOOKMARK
                : BookmarkNote.TYPE_NOTE;
        List<BookmarkNote> all = bookmarkNoteDao.getByTitleAndType(book.getId(), entryType);
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
            View row = inflater.inflate(R.layout.item_chapter_ebook, containerItems, false);
            MaterialCardView cardRow = row.findViewById(R.id.cardChapterRow);
            ImageView ivStatus = row.findViewById(R.id.ivChapterStatus);
            TextView tvTitle = row.findViewById(R.id.tvChapterTitle);
            TextView tvSubtitle = row.findViewById(R.id.tvChapterSubtitle);
            TextView tvPage = row.findViewById(R.id.tvChapterPage);

            ivStatus.setImageResource(selectedTab == TAB_BOOKMARKS ? R.drawable.ic_bookmark_filled : R.drawable.ic_note);
            tvTitle.setText(item.getNoteText().isEmpty()
                    ? getString(R.string.format_chapter_n, item.getChapterIndex())
                    : item.getNoteText());
            tvSubtitle.setText(fmt.format(new Date(item.getCreatedAt())));
            tvPage.setText(item.getPositionLabel());

            cardRow.setOnClickListener(v -> openChapterInReader(item.getChapterIndex()));
            cardRow.setOnLongClickListener(v -> {
                confirmDeleteEntry(item);
                return true;
            });
            containerItems.addView(row);
        }
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

    private void openChapterInReader(int chapterIndex) {
        boolean switchingChapter = chapterIndex != book.getCurrentChapter();
        if (switchingChapter) {
            book.setScrollY(0);
            book.setScrollMaxHeight(0);
            book.setCharOffset(0);
            book.setScrollPermille(0);
            Chapter targetChapter = chapterDao.getChapter(book.getId(), chapterIndex);
            if (targetChapter != null && targetChapter.getAudioStartMs() > 0) {
                book.setCurrentPage((int) targetChapter.getAudioStartMs());
            }
        }
        book.setCurrentChapter(chapterIndex);
        book.setCurrentChapterCompleted(false);
        book.setStatus(Title.STATUS_IN_PROGRESS);
        book.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(book);
        NavigationFlowHelper.openReaderOrPlayer(this, book, chapterIndex);
        finish();
    }
}
