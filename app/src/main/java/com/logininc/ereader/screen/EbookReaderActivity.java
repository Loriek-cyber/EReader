package com.logininc.ereader.screen;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

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
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.ThemeHelper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class EbookReaderActivity extends AppCompatActivity {

    private TitleDao titleDao;
    private ChapterDao chapterDao;
    private BookmarkNoteDao bookmarkNoteDao;
    private SettingsDao settingsDao;

    private Book book;
    private Chapter currentChapter;
    private int currentChapterIndex = 1;
    private int currentPdfPageZeroBased = 0;

    @Nullable
    private ParcelFileDescriptor pdfPfd;
    @Nullable
    private PdfRenderer pdfRenderer;

    private TextView tvChapterPill;
    private TextView tvChapterLabel;
    private TextView tvChapterTitle;
    private TextView tvProseBody;
    private NestedScrollView scrollContent;
    private LinearLayout layoutPdfContainer;
    private ImageView ivPdfPage;
    private TextView tvPdfPageIndicator;
    private MaterialButton btnPdfPrev;
    private MaterialButton btnPdfNext;
    private MaterialButton btnPrevChapter;
    private MaterialButton btnCompleteChapter;

    private View typographyHud;
    private TextView tvFontSizeLabel;
    private MaterialButton btnFontSerif;
    private MaterialButton btnFontSans;
    private MaterialButton btnThemeOled;

    private TextView tvPageCounter;
    private TextView tvProgressPercent;
    private LinearProgressIndicator progressReader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        settingsDao = new SettingsDao(this);
        ThemeHelper.applyTheme(this, true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ebook_reader);

        titleDao = new TitleDao(this);
        chapterDao = new ChapterDao(this);
        bookmarkNoteDao = new BookmarkNoteDao(this);

        int titleId = getIntent().getIntExtra(NavigationFlowHelper.EXTRA_TITLE_ID, -1);
        Title loaded = titleDao.getById(titleId);
        if (!(loaded instanceof Book)) {
            finish();
            return;
        }
        book = (Book) loaded;
        currentChapterIndex = getIntent().getIntExtra(
                NavigationFlowHelper.EXTRA_CHAPTER_INDEX, book.getCurrentChapter());

        bindViews();
        PlaybackSessionManager.getInstance().startOrSwitchSession(this, book, currentChapterIndex, true);
        loadChapter(currentChapterIndex);
        applyTypographyFromSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentReadingPosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdfRenderer();
    }

    private void bindViews() {
        findViewById(R.id.btnReaderBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnReaderStop).setOnClickListener(v -> {
            saveCurrentReadingPosition();
            PlaybackSessionManager.getInstance().stopAndClearSession(this);
            finish();
        });

        tvChapterPill = findViewById(R.id.tvReaderChapterPill);
        tvChapterLabel = findViewById(R.id.tvReaderChapterLabel);
        tvChapterTitle = findViewById(R.id.tvReaderChapterTitle);
        tvProseBody = findViewById(R.id.tvReaderProseBody);
        scrollContent = findViewById(R.id.scrollReaderContent);
        layoutPdfContainer = findViewById(R.id.layoutPdfContainer);
        ivPdfPage = findViewById(R.id.ivPdfPage);
        tvPdfPageIndicator = findViewById(R.id.tvPdfPageIndicator);
        btnPdfPrev = findViewById(R.id.btnPdfPrevPage);
        btnPdfNext = findViewById(R.id.btnPdfNextPage);
        btnPrevChapter = findViewById(R.id.btnPrevChapterReader);
        btnCompleteChapter = findViewById(R.id.btnCompleteChapterReader);

        typographyHud = findViewById(R.id.typographyHud);
        tvFontSizeLabel = findViewById(R.id.tvFontSizeLabel);
        btnFontSerif = findViewById(R.id.btnFontSerif);
        btnFontSans = findViewById(R.id.btnFontSans);
        btnThemeOled = findViewById(R.id.btnThemeOled);

        tvPageCounter = findViewById(R.id.tvReaderPageCounter);
        tvProgressPercent = findViewById(R.id.tvReaderProgressPercent);
        progressReader = findViewById(R.id.progressReader);

        View.OnClickListener openChaptersListener = v -> {
            saveCurrentReadingPosition();
            NavigationFlowHelper.openChapterSelector(this, book);
        };
        findViewById(R.id.btnChapterPill).setOnClickListener(openChaptersListener);
        findViewById(R.id.btnActionIndex).setOnClickListener(openChaptersListener);

        findViewById(R.id.btnActionTypography).setOnClickListener(v ->
                typographyHud.setVisibility(typographyHud.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        findViewById(R.id.btnFontDecrease).setOnClickListener(v -> {
            settingsDao.setReaderFontSizeSp(settingsDao.getReaderFontSizeSp() - 2);
            applyTypographyFromSettings();
        });
        findViewById(R.id.btnFontIncrease).setOnClickListener(v -> {
            settingsDao.setReaderFontSizeSp(settingsDao.getReaderFontSizeSp() + 2);
            applyTypographyFromSettings();
        });
        btnFontSerif.setOnClickListener(v -> {
            settingsDao.setReaderFontFamily("serif");
            applyTypographyFromSettings();
        });
        btnFontSans.setOnClickListener(v -> {
            settingsDao.setReaderFontFamily("sans-serif");
            applyTypographyFromSettings();
        });
        btnThemeOled.setOnClickListener(v -> {
            String next = SettingsDao.THEME_OLED.equals(settingsDao.getThemeMode())
                    ? SettingsDao.THEME_DARK
                    : SettingsDao.THEME_OLED;
            settingsDao.setThemeMode(next);
            recreate();
        });
        findViewById(R.id.btnActionTheme).setOnClickListener(v -> {
            ThemeHelper.cycleNextTheme(this);
            recreate();
        });
        findViewById(R.id.btnActionBookmark).setOnClickListener(v -> showSaveBookmarkNoteDialog());

        btnPrevChapter.setOnClickListener(v -> {
            if (currentChapterIndex > 1) {
                saveCurrentReadingPosition();
                loadChapter(currentChapterIndex - 1);
            }
        });
        btnCompleteChapter.setOnClickListener(v -> completeCurrentChapterAndOpenSelector());

        btnPdfPrev.setOnClickListener(v -> showPdfPage(currentPdfPageZeroBased - 1));
        btnPdfNext.setOnClickListener(v -> showPdfPage(currentPdfPageZeroBased + 1));

        scrollContent.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateScrollProgress(v));
    }

    private void loadChapter(int chapterIndex) {
        currentChapterIndex = Math.max(1, Math.min(book.getLenChapters(), chapterIndex));
        currentChapter = chapterDao.getChapter(book.getId(), currentChapterIndex);
        if (currentChapter == null) {
            List<Chapter> all = chapterDao.getChaptersForTitle(book.getId());
            if (!all.isEmpty()) {
                currentChapter = all.get(0);
                currentChapterIndex = currentChapter.getChapterIndex();
            }
        }

        book.setCurrentChapter(currentChapterIndex);
        book.setCurrentChapterCompleted(false);
        book.setStatus(Title.STATUS_IN_PROGRESS);
        book.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(book);

        String chTitle = currentChapter != null
                ? currentChapter.getChapterTitle()
                : getString(R.string.format_chapter_n, currentChapterIndex);

        tvChapterPill.setText(book.getTitle() + " • " + getString(R.string.chapter_active_badge_fmt, currentChapterIndex));
        tvChapterLabel.setText(getString(R.string.reader_chapter_label, currentChapterIndex));
        tvChapterTitle.setText(chTitle);

        btnPrevChapter.setEnabled(currentChapterIndex > 1);
        btnPrevChapter.setAlpha(currentChapterIndex > 1 ? 1f : 0.4f);

        String displayName = FileScanner.queryDisplayName(this, book.getContentUri()).toLowerCase(Locale.ROOT);
        boolean isPdf = displayName.endsWith(".pdf")
                || (currentChapter != null && currentChapter.getContentText().isEmpty() && currentChapter.getAudioStartMs() > 0);

        if (isPdf) {
            tvProseBody.setVisibility(View.GONE);
            layoutPdfContainer.setVisibility(View.VISIBLE);
            int startPageOneBased = currentChapter != null && currentChapter.getAudioStartMs() > 0
                    ? (int) currentChapter.getAudioStartMs()
                    : 1;
            openPdfAndShowPage(Math.max(0, startPageOneBased - 1));
        } else {
            layoutPdfContainer.setVisibility(View.GONE);
            tvProseBody.setVisibility(View.VISIBLE);
            String body = currentChapter != null ? currentChapter.getContentText() : "";
            tvProseBody.setText(body.isEmpty() ? getString(R.string.reader_no_text) : body);
            scrollContent.post(() -> scrollContent.scrollTo(0, 0));
        }
        updateProgressFooter();
    }

    private void openPdfAndShowPage(int pageZeroBased) {
        try {
            if (pdfRenderer == null) {
                pdfPfd = getContentResolver().openFileDescriptor(book.getContentUri(), "r");
                if (pdfPfd == null) {
                    Toast.makeText(this, R.string.reader_file_error, Toast.LENGTH_LONG).show();
                    return;
                }
                pdfRenderer = new PdfRenderer(pdfPfd);
                book.setTotalPages(Math.max(1, pdfRenderer.getPageCount()));
            }
            showPdfPage(pageZeroBased);
        } catch (IOException | SecurityException e) {
            Toast.makeText(this, R.string.reader_file_error, Toast.LENGTH_LONG).show();
        }
    }

    private void showPdfPage(int pageZeroBased) {
        if (pdfRenderer == null) {
            return;
        }
        int total = pdfRenderer.getPageCount();
        if (total <= 0) {
            return;
        }
        currentPdfPageZeroBased = Math.max(0, Math.min(total - 1, pageZeroBased));
        try (PdfRenderer.Page page = pdfRenderer.openPage(currentPdfPageZeroBased)) {
            int width = Math.max(800, getResources().getDisplayMetrics().widthPixels);
            int height = Math.max(800, Math.round((float) width * page.getHeight() / Math.max(1, page.getWidth())));
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(android.graphics.Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ivPdfPage.setImageBitmap(bmp);
        }
        int oneBased = currentPdfPageZeroBased + 1;
        book.setCurrentPage(oneBased);
        book.setTotalPages(total);
        int pct = (oneBased * 100) / Math.max(1, total);
        book.setProgressPercent(pct);
        tvPdfPageIndicator.setText(getString(R.string.format_pages_of, oneBased, total));
        btnPdfPrev.setEnabled(currentPdfPageZeroBased > 0);
        btnPdfNext.setEnabled(currentPdfPageZeroBased < total - 1);
        updateProgressFooter();
    }

    private void closePdfRenderer() {
        if (pdfRenderer != null) {
            pdfRenderer.close();
            pdfRenderer = null;
        }
        if (pdfPfd != null) {
            try {
                pdfPfd.close();
            } catch (IOException ignored) {
            }
            pdfPfd = null;
        }
    }

    private void updateScrollProgress(@NonNull NestedScrollView scrollView) {
        if (layoutPdfContainer.getVisibility() == View.VISIBLE || scrollView.getChildCount() == 0) {
            return;
        }
        View child = scrollView.getChildAt(0);
        int maxScroll = Math.max(1, child.getHeight() - scrollView.getHeight());
        int chPercent = Math.max(0, Math.min(100, (scrollView.getScrollY() * 100) / maxScroll));

        if (currentChapter != null) {
            currentChapter.setProgressPercent(Math.max(currentChapter.getProgressPercent(), chPercent));
        }

        int totalChapters = Math.max(1, book.getLenChapters());
        int overallPct = ((currentChapterIndex - 1) * 100 + chPercent) / totalChapters;
        book.setProgressPercent(Math.max(book.getProgressPercent(), overallPct));
        int estPage = Math.max(1, (int) Math.ceil((book.getProgressPercent() / 100.0) * book.getTotalPages()));
        book.setCurrentPage(estPage);
        updateProgressFooter();
    }

    private void updateProgressFooter() {
        tvPageCounter.setText(getString(R.string.reader_page_counter, book.getCurrentPage(), book.getTotalPages()));
        tvProgressPercent.setText(getString(R.string.format_percent, book.getProgressPercent()));
        progressReader.setProgress(book.getProgressPercent());
    }

    private void completeCurrentChapterAndOpenSelector() {
        chapterDao.updateChapterProgress(book.getId(), currentChapterIndex, 100, true);

        List<Chapter> chapters = chapterDao.getChaptersForTitle(book.getId());
        int completedCount = 0;
        for (Chapter ch : chapters) {
            if (ch.isCompleted()) {
                completedCount++;
            }
        }
        int totalChapters = Math.max(1, book.getLenChapters());
        int overallPct = Math.min(100, (completedCount * 100) / totalChapters);
        book.setProgressPercent(overallPct);
        book.setCurrentChapterCompleted(true);
        if (currentChapterIndex >= totalChapters && completedCount >= totalChapters) {
            book.setStatus(Title.STATUS_COMPLETED);
            book.setProgressPercent(100);
        } else {
            book.setStatus(Title.STATUS_IN_PROGRESS);
        }
        titleDao.update(book);

        // Flow rule: (Libro nuovo o capitolo concluso) -> selettore di capitolo -> reader/player
        NavigationFlowHelper.openChapterSelector(this, book);
        finish();
    }

    private void saveCurrentReadingPosition() {
        if (book == null) {
            return;
        }
        if (currentChapter != null) {
            chapterDao.updateChapterProgress(
                    book.getId(),
                    currentChapterIndex,
                    currentChapter.getProgressPercent(),
                    currentChapter.isCompleted()
            );
        }
        book.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(book);

        if (settingsDao.isAutoBookmark()) {
            // Auto-update latest auto bookmark slot or save position cleanly in TitleDao
            PlaybackSessionManager.getInstance().persistCurrentState();
        }
    }

    private void applyTypographyFromSettings() {
        int sizeSp = settingsDao.getReaderFontSizeSp();
        String family = settingsDao.getReaderFontFamily();

        tvFontSizeLabel.setText(getString(R.string.reader_font_size_fmt, sizeSp));
        tvProseBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tvProseBody.setTypeface("sans-serif".equals(family) ? Typeface.SANS_SERIF : Typeface.SERIF);

        boolean isSerif = !"sans-serif".equals(family);
        btnFontSerif.setAlpha(isSerif ? 1f : 0.65f);
        btnFontSans.setAlpha(isSerif ? 0.65f : 1f);
        btnThemeOled.setAlpha(SettingsDao.THEME_OLED.equals(settingsDao.getThemeMode()) ? 1f : 0.65f);
    }

    private void showSaveBookmarkNoteDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.reader_note_hint);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_base);
        input.setPadding(pad, pad, pad, pad);

        String posLabel = getString(R.string.chapter_active_badge_fmt, currentChapterIndex)
                + " • " + getString(R.string.format_pages_of, book.getCurrentPage(), book.getTotalPages());

        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_note_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.reader_save_note, (d, w) -> {
                    String noteText = input.getText() != null ? input.getText().toString().trim() : "";
                    String type = noteText.isEmpty() ? BookmarkNote.TYPE_BOOKMARK : BookmarkNote.TYPE_NOTE;
                    bookmarkNoteDao.insert(new BookmarkNote(
                            0, book.getId(), currentChapterIndex, type, posLabel,
                            noteText.isEmpty() ? posLabel : noteText,
                            System.currentTimeMillis()
                    ));
                    Toast.makeText(this, R.string.reader_bookmark_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.reader_save_bookmark_only, (d, w) -> {
                    bookmarkNoteDao.insert(new BookmarkNote(
                            0, book.getId(), currentChapterIndex, BookmarkNote.TYPE_BOOKMARK, posLabel,
                            posLabel, System.currentTimeMillis()
                    ));
                    Toast.makeText(this, R.string.reader_bookmark_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
