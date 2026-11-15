package com.logininc.ereader.screen;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
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
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private TextView tvEmpty;
    private LinearLayout containerItems;
    private TextView tvBottomCurrent;
    private MaterialButton btnContinue;

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
                renderActiveTab();
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

        List<Chapter> allChapters = chapterDao.getChaptersForTitle(book.getId());
        int bmCount = bookmarkNoteDao.countByTitleAndType(book.getId(), BookmarkNote.TYPE_BOOKMARK);
        int noteCount = bookmarkNoteDao.countByTitleAndType(book.getId(), BookmarkNote.TYPE_NOTE);

        tabChapters.setText(getString(R.string.tab_chapters_fmt, allChapters.size()));
        tabBookmarks.setText(getString(R.string.tab_bookmarks_fmt, bmCount));
        tabNotes.setText(getString(R.string.tab_notes_fmt, noteCount));

        updateTabButtonsAppearance();
        bindActiveChapterAndFooter(allChapters);
        renderActiveTab();
    }

    private void bindActiveChapterAndFooter(@NonNull List<Chapter> allChapters) {
        int targetChapterIndex = book.getCurrentChapter();
        if (book.isCurrentChapterCompleted() && targetChapterIndex < book.getLenChapters()) {
            targetChapterIndex = targetChapterIndex + 1;
        }
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
            btnResumeChapter.setOnClickListener(v -> openChapterInReader(chIndex));
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
            List<Chapter> chapters = query.isEmpty()
                    ? chapterDao.getChaptersForTitle(book.getId())
                    : chapterDao.searchChapters(book.getId(), query);
            tvEmpty.setVisibility(chapters.isEmpty() ? View.VISIBLE : View.GONE);
            for (Chapter ch : chapters) {
                View row = inflater.inflate(R.layout.item_chapter_ebook, containerItems, false);
                ImageView ivStatus = row.findViewById(R.id.ivChapterStatus);
                TextView tvTitle = row.findViewById(R.id.tvChapterTitle);
                TextView tvSubtitle = row.findViewById(R.id.tvChapterSubtitle);
                TextView tvPage = row.findViewById(R.id.tvChapterPage);

                tvTitle.setText(ch.getChapterTitle());
                tvSubtitle.setText(ch.getSubtitle().isEmpty()
                        ? (ch.isCompleted() ? getString(R.string.chapter_status_completed) : getString(R.string.chapter_status_to_start))
                        : ch.getSubtitle());
                tvPage.setText(ch.getPageOrDurationLabel());

                if (ch.isCompleted()) {
                    ivStatus.setImageResource(R.drawable.ic_check);
                } else if (ch.getChapterIndex() == book.getCurrentChapter()
                        && Title.STATUS_IN_PROGRESS.equals(book.getStatus())) {
                    ivStatus.setImageResource(R.drawable.ic_play);
                } else {
                    ivStatus.setImageResource(R.drawable.ic_radio_unchecked);
                }

                row.setOnClickListener(v -> openChapterInReader(ch.getChapterIndex()));
                containerItems.addView(row);
            }
            return;
        }

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

            row.setOnClickListener(v -> openChapterInReader(item.getChapterIndex()));
            row.setOnLongClickListener(v -> {
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
        book.setCurrentChapter(chapterIndex);
        book.setCurrentChapterCompleted(false);
        book.setStatus(Title.STATUS_IN_PROGRESS);
        book.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(book);
        NavigationFlowHelper.openReaderOrPlayer(this, book, chapterIndex);
        finish();
    }
}
