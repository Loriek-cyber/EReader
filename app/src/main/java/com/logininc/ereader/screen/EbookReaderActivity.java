package com.logininc.ereader.screen;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class EbookReaderActivity extends AppCompatActivity {

    private static final String STATE_CHAPTER_INDEX = "state_chapter_index";
    private static final String STATE_PDF_PAGE = "state_pdf_page";
    private static final String STATE_SCROLL_Y = "state_scroll_y";
    private static final String STATE_HUD_VISIBLE = "state_hud_visible";

    private TitleDao titleDao;
    private ChapterDao chapterDao;
    private BookmarkNoteDao bookmarkNoteDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;

    private Book book;
    private Chapter currentChapter;
    private int currentChapterIndex = 1;
    private int currentPdfPageZeroBased = 0;
    private int pendingRestoreScrollY = -1;
    private int pendingRestorePdfPage = -1;
    private boolean isRestoringPosition = false;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedSaveRunnable = this::saveCurrentReadingPosition;
    private final ExecutorService pdfExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pdfRenderGeneration = new AtomicInteger(0);
    private int pdfTotalPages = 0;

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
        appliedThemeMode = settingsDao.getThemeMode();
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
        int savedBookChapter = book.getCurrentChapter();
        currentChapterIndex = getIntent().getIntExtra(
                NavigationFlowHelper.EXTRA_CHAPTER_INDEX, savedBookChapter);
        if (savedInstanceState != null) {
            currentChapterIndex = savedInstanceState.getInt(STATE_CHAPTER_INDEX, currentChapterIndex);
            pendingRestorePdfPage = savedInstanceState.getInt(STATE_PDF_PAGE, -1);
            pendingRestoreScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, -1);
        }
        boolean shouldRestoreSavedPosition = (currentChapterIndex == savedBookChapter)
                || pendingRestoreScrollY >= 0
                || pendingRestorePdfPage >= 0;

        bindViews();
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_HUD_VISIBLE, false)) {
            typographyHud.setVisibility(View.VISIBLE);
        }
        applyTypographyFromSettings();
        PlaybackSessionManager.getInstance().startOrSwitchSession(this, book, currentChapterIndex, true);
        loadChapter(currentChapterIndex, shouldRestoreSavedPosition);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsDao.getThemeMode().equals(appliedThemeMode)) {
            recreate();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        saveCurrentReadingPosition();
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CHAPTER_INDEX, currentChapterIndex);
        outState.putInt(STATE_PDF_PAGE, currentPdfPageZeroBased);
        outState.putInt(STATE_SCROLL_Y, scrollContent != null ? scrollContent.getScrollY() : 0);
        outState.putBoolean(STATE_HUD_VISIBLE, typographyHud != null && typographyHud.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentReadingPosition();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveCurrentReadingPosition();
        PlaybackSessionManager.getInstance().pauseEbookReadingSession(this);
    }

    @Override
    protected void onDestroy() {
        saveHandler.removeCallbacks(debouncedSaveRunnable);
        pdfRenderGeneration.incrementAndGet();
        pdfExecutor.execute(this::closePdfRenderer);
        pdfExecutor.shutdown();
        super.onDestroy();
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
            finish();
        };
        findViewById(R.id.btnChapterPill).setOnClickListener(openChaptersListener);
        findViewById(R.id.btnActionIndex).setOnClickListener(openChaptersListener);

        findViewById(R.id.btnActionTypography).setOnClickListener(v ->
                typographyHud.setVisibility(typographyHud.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        findViewById(R.id.btnFontDecrease).setOnClickListener(v -> {
            captureScrollStats(scrollContent);
            settingsDao.setReaderFontSizeSp(settingsDao.getReaderFontSizeSp() - 2);
            applyTypographyFromSettings();
            restoreProsePositionAfterLayout(true);
        });
        findViewById(R.id.btnFontIncrease).setOnClickListener(v -> {
            captureScrollStats(scrollContent);
            settingsDao.setReaderFontSizeSp(settingsDao.getReaderFontSizeSp() + 2);
            applyTypographyFromSettings();
            restoreProsePositionAfterLayout(true);
        });
        btnFontSerif.setOnClickListener(v -> {
            captureScrollStats(scrollContent);
            settingsDao.setReaderFontFamily("serif");
            applyTypographyFromSettings();
            restoreProsePositionAfterLayout(true);
        });
        btnFontSans.setOnClickListener(v -> {
            captureScrollStats(scrollContent);
            settingsDao.setReaderFontFamily("sans-serif");
            applyTypographyFromSettings();
            restoreProsePositionAfterLayout(true);
        });
        btnThemeOled.setOnClickListener(v -> {
            String next = SettingsDao.THEME_OLED.equals(settingsDao.getThemeMode())
                    ? SettingsDao.THEME_DARK
                    : SettingsDao.THEME_OLED;
            settingsDao.setThemeMode(next);
            saveCurrentReadingPosition();
            getIntent().putExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX, currentChapterIndex);
            recreate();
        });
        findViewById(R.id.btnActionTheme).setOnClickListener(v -> {
            ThemeHelper.cycleNextTheme(this);
            saveCurrentReadingPosition();
            getIntent().putExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX, currentChapterIndex);
            recreate();
        });
        findViewById(R.id.btnActionBookmark).setOnClickListener(v -> showSaveBookmarkNoteDialog());

        btnPrevChapter.setOnClickListener(v -> {
            if (currentChapterIndex > 1) {
                saveCurrentReadingPosition();
                loadChapter(currentChapterIndex - 1, false);
            }
        });
        btnCompleteChapter.setOnClickListener(v -> completeCurrentChapterAndAdvance());

        btnPdfPrev.setOnClickListener(v -> showPdfPage(currentPdfPageZeroBased - 1));
        btnPdfNext.setOnClickListener(v -> showPdfPage(currentPdfPageZeroBased + 1));

        scrollContent.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateScrollProgress(v));
    }

    private void loadChapter(int chapterIndex, boolean restoreSavedPosition) {
        saveHandler.removeCallbacks(debouncedSaveRunnable);
        isRestoringPosition = false;
        currentChapterIndex = Math.max(1, Math.min(book.getLenChapters(), chapterIndex));
        currentChapter = chapterDao.getChapter(book.getId(), currentChapterIndex);
        if (currentChapter == null) {
            List<Chapter> all = chapterDao.getChaptersForTitle(book.getId());
            if (!all.isEmpty()) {
                currentChapter = all.get(0);
                currentChapterIndex = currentChapter.getChapterIndex();
            }
        }

        getIntent().putExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX, currentChapterIndex);
        book.setCurrentChapter(currentChapterIndex);
        book.setCurrentChapterCompleted(false);
        book.setStatus(Title.STATUS_IN_PROGRESS);
        book.setLastOpenedAt(System.currentTimeMillis());
        if (!restoreSavedPosition) {
            book.setScrollY(0);
            book.setScrollMaxHeight(0);
            book.setCharOffset(0);
            book.setScrollPermille(0);
        }
        titleDao.update(book);
        PlaybackSessionManager.getInstance().syncActiveTitle(book);

        updateChapterHeaderUi();

        String displayName = FileScanner.queryDisplayName(this, book.getContentUri()).toLowerCase(Locale.ROOT);
        boolean hasProseText = currentChapter != null && !currentChapter.getContentText().trim().isEmpty();
        boolean isPdf = !hasProseText && (displayName.endsWith(".pdf")
                || (currentChapter != null && currentChapter.getAudioStartMs() > 0));

        if (isPdf) {
            tvProseBody.setVisibility(View.GONE);
            layoutPdfContainer.setVisibility(View.VISIBLE);
            int startPageOneBased = currentChapter != null && currentChapter.getAudioStartMs() > 0
                    ? (int) currentChapter.getAudioStartMs()
                    : 1;
            int endPageOneBased = currentChapter != null && currentChapter.getAudioDurationMs() > 0
                    ? (int) currentChapter.getAudioDurationMs()
                    : Integer.MAX_VALUE;
            boolean pageInCurrentChapter = book.getCurrentPage() >= startPageOneBased
                    && book.getCurrentPage() <= endPageOneBased;
            int targetZeroBased;
            if (pendingRestorePdfPage >= 0) {
                targetZeroBased = pendingRestorePdfPage;
                pendingRestorePdfPage = -1;
            } else if (restoreSavedPosition && pageInCurrentChapter && book.getCurrentPage() > 1) {
                targetZeroBased = book.getCurrentPage() - 1;
            } else {
                targetZeroBased = Math.max(0, startPageOneBased - 1);
            }
            openPdfAndShowPage(targetZeroBased);
        } else {
            layoutPdfContainer.setVisibility(View.GONE);
            tvProseBody.setVisibility(View.VISIBLE);
            String body = currentChapter != null ? currentChapter.getContentText() : "";
            tvProseBody.setText(body.isEmpty() ? getString(R.string.reader_no_text) : body);
            if (pendingRestoreScrollY >= 0) {
                final int restoreY = pendingRestoreScrollY;
                pendingRestoreScrollY = -1;
                restoreExplicitScrollYAfterLayout(restoreY);
            } else if (restoreSavedPosition
                    && (book.getScrollY() > 0 || book.getCharOffset() > 0 || book.getScrollPermille() > 0)) {
                restoreProsePositionAfterLayout(true);
            } else {
                isRestoringPosition = true;
                runAfterProseLayout(() -> {
                    scrollContent.scrollTo(0, 0);
                    isRestoringPosition = false;
                    updateScrollProgress(scrollContent);
                });
            }
        }
        updateProgressFooter();
    }

    private void updateChapterHeaderUi() {
        String chTitle = currentChapter != null
                ? currentChapter.getChapterTitle()
                : getString(R.string.format_chapter_n, currentChapterIndex);

        tvChapterPill.setText(book.getTitle() + " • " + getString(R.string.chapter_active_badge_fmt, currentChapterIndex));
        tvChapterLabel.setText(getString(R.string.reader_chapter_label, currentChapterIndex));
        tvChapterTitle.setText(chTitle);

        btnPrevChapter.setEnabled(currentChapterIndex > 1);
        btnPrevChapter.setAlpha(currentChapterIndex > 1 ? 1f : 0.4f);

        boolean hasNextChapter = currentChapterIndex < book.getLenChapters();
        btnCompleteChapter.setText(hasNextChapter ? R.string.reader_next_chapter_cta : R.string.reader_complete_book);
    }

    private void openPdfAndShowPage(int pageZeroBased) {
        final int gen = pdfRenderGeneration.incrementAndGet();
        final int maxWidthPx = Math.min(1600, Math.max(800, getResources().getDisplayMetrics().widthPixels));
        pdfExecutor.execute(() -> {
            try {
                if (pdfRenderer == null) {
                    pdfPfd = getContentResolver().openFileDescriptor(book.getContentUri(), "r");
                    if (pdfPfd == null) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(this, R.string.reader_file_error, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    pdfRenderer = new PdfRenderer(pdfPfd);
                    pdfTotalPages = Math.max(1, pdfRenderer.getPageCount());
                }
                renderPdfPageOnWorker(pageZeroBased, gen, maxWidthPx);
            } catch (IOException | SecurityException | IllegalStateException e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.reader_file_error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showPdfPage(int pageZeroBased) {
        final int gen = pdfRenderGeneration.incrementAndGet();
        final int maxWidthPx = Math.min(1600, Math.max(800, getResources().getDisplayMetrics().widthPixels));
        pdfExecutor.execute(() -> renderPdfPageOnWorker(pageZeroBased, gen, maxWidthPx));
    }

    private void renderPdfPageOnWorker(int pageZeroBased, int generation, int maxWidthPx) {
        if (pdfRenderer == null || generation != pdfRenderGeneration.get()) {
            return;
        }
        int total = pdfRenderer.getPageCount();
        if (total <= 0) {
            return;
        }
        int targetZeroBased = Math.max(0, Math.min(total - 1, pageZeroBased));
        Bitmap bmp;
        try (PdfRenderer.Page page = pdfRenderer.openPage(targetZeroBased)) {
            int height = Math.max(800, Math.round((float) maxWidthPx * page.getHeight() / Math.max(1, page.getWidth())));
            bmp = Bitmap.createBitmap(maxWidthPx, height, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(android.graphics.Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (IllegalStateException | OutOfMemoryError e) {
            return;
        }
        if (generation != pdfRenderGeneration.get() || isFinishing() || isDestroyed()) {
            bmp.recycle();
            return;
        }
        runOnUiThread(() -> {
            if (generation != pdfRenderGeneration.get() || isFinishing() || isDestroyed()) {
                bmp.recycle();
                return;
            }
            currentPdfPageZeroBased = targetZeroBased;
            ivPdfPage.setImageBitmap(bmp);
            int oneBased = currentPdfPageZeroBased + 1;
            book.setCurrentPage(oneBased);
            book.setTotalPages(total);
            int pct = (oneBased * 100) / Math.max(1, total);
            book.setProgressPercent(pct);
            syncPdfChapterForPage(oneBased);
            book.setLastOpenedAt(System.currentTimeMillis());
            titleDao.update(book);
            PlaybackSessionManager.getInstance().syncActiveTitle(book);
            tvPdfPageIndicator.setText(getString(R.string.format_pages_of, oneBased, total));
            btnPdfPrev.setEnabled(currentPdfPageZeroBased > 0);
            btnPdfNext.setEnabled(currentPdfPageZeroBased < total - 1);
            updateProgressFooter();
        });
    }

    private void syncPdfChapterForPage(int oneBasedPage) {
        if (book == null || book.getLenChapters() <= 1) {
            return;
        }
        List<Chapter> allChapters = chapterDao.getChapterSummariesForTitle(book.getId());
        Chapter matched = null;
        for (Chapter ch : allChapters) {
            long start = ch.getAudioStartMs();
            long end = ch.getAudioDurationMs();
            if (start > 0 && end >= start && oneBasedPage >= start && oneBasedPage <= end) {
                matched = ch;
                break;
            }
        }
        if (matched != null) {
            if (matched.getChapterIndex() != currentChapterIndex) {
                if (matched.getChapterIndex() > currentChapterIndex) {
                    chapterDao.updateChapterProgress(book.getId(), currentChapterIndex, 100, true);
                }
                currentChapter = matched;
                currentChapterIndex = matched.getChapterIndex();
                book.setCurrentChapter(currentChapterIndex);
                getIntent().putExtra(NavigationFlowHelper.EXTRA_CHAPTER_INDEX, currentChapterIndex);
                updateChapterHeaderUi();
            }
            long start = matched.getAudioStartMs();
            long end = matched.getAudioDurationMs();
            int chPct = end > start
                    ? (int) Math.min(100L, Math.max(0L, ((oneBasedPage - start + 1) * 100L) / (end - start + 1)))
                    : 100;
            currentChapter.setProgressPercent(Math.max(currentChapter.getProgressPercent(), chPct));
            chapterDao.updateChapterProgress(
                    book.getId(),
                    currentChapterIndex,
                    currentChapter.getProgressPercent(),
                    currentChapter.isCompleted()
            );
        }
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
        if (isRestoringPosition || layoutPdfContainer.getVisibility() == View.VISIBLE || scrollView.getChildCount() == 0) {
            return;
        }
        captureScrollStats(scrollView);

        View child = scrollView.getChildAt(0);
        int viewportH = Math.max(1, scrollView.getHeight());
        int maxPageH = Math.max(viewportH, child.getHeight());
        int maxScroll = Math.max(1, maxPageH - viewportH);
        int chPercent = Math.max(0, Math.min(100, (scrollView.getScrollY() * 100) / maxScroll));

        if (currentChapter != null) {
            currentChapter.setProgressPercent(Math.max(currentChapter.getProgressPercent(), chPercent));
        }

        int totalChapters = Math.max(1, book.getLenChapters());
        int overallPct = ((currentChapterIndex - 1) * 100 + chPercent) / totalChapters;
        book.setProgressPercent(Math.max(0, Math.min(100, overallPct)));

        int virtualPagesInChapter = Math.max(1, (int) Math.ceil((double) maxPageH / viewportH));
        int currentPageInChapter = Math.min(virtualPagesInChapter, 1 + (scrollView.getScrollY() / viewportH));
        if (totalChapters == 1) {
            book.setTotalPages(virtualPagesInChapter);
            book.setCurrentPage(currentPageInChapter);
        } else if (currentChapter != null
                && currentChapter.getAudioStartMs() > 0
                && currentChapter.getAudioDurationMs() >= currentChapter.getAudioStartMs()) {
            long startPage = currentChapter.getAudioStartMs();
            long endPage = currentChapter.getAudioDurationMs();
            book.setCurrentPage((int) (startPage + Math.round(((endPage - startPage) * chPercent) / 100.0)));
        } else {
            book.setCurrentPage(Math.max(1, (int) Math.ceil((overallPct / 100.0) * book.getTotalPages())));
        }
        updateProgressFooter();
        scheduleDebouncedSave();
    }

    private void scheduleDebouncedSave() {
        saveHandler.removeCallbacks(debouncedSaveRunnable);
        saveHandler.postDelayed(debouncedSaveRunnable, 1200L);
    }

    private void captureScrollStats(@Nullable NestedScrollView scrollView) {
        if (isRestoringPosition || book == null || scrollView == null
                || layoutPdfContainer.getVisibility() == View.VISIBLE
                || scrollView.getChildCount() == 0) {
            return;
        }
        View child = scrollView.getChildAt(0);
        int scrollY = Math.max(0, scrollView.getScrollY());
        int viewportH = Math.max(0, scrollView.getHeight());
        int maxPageH = Math.max(0, child.getHeight());
        if (viewportH <= 0 || maxPageH <= 0) {
            return;
        }
        int maxScroll = Math.max(1, maxPageH - viewportH);

        book.setScrollY(scrollY);
        book.setScrollMaxHeight(maxPageH);
        book.setViewportHeight(viewportH);
        book.setTextSizeSp(settingsDao.getReaderFontSizeSp());
        book.setScrollPermille((int) Math.min(10000L, (scrollY * 10000L) / maxScroll));

        if (tvProseBody != null && tvProseBody.getLayout() != null) {
            int localY = Math.max(0, scrollY - tvProseBody.getTop());
            int line = tvProseBody.getLayout().getLineForVertical(localY);
            book.setCharOffset(tvProseBody.getLayout().getLineStart(line));
        }
    }

    private void runAfterProseLayout(@NonNull Runnable action) {
        if (scrollContent == null) {
            return;
        }
        Runnable executeWhenReady = () -> {
            if (isFinishing() || isDestroyed() || scrollContent.getChildCount() == 0) {
                isRestoringPosition = false;
                return;
            }
            View child = scrollContent.getChildAt(0);
            boolean layoutPending = scrollContent.isLayoutRequested()
                    || (tvProseBody != null && tvProseBody.isLayoutRequested());
            if (!layoutPending && scrollContent.getHeight() > 0 && child.getHeight() > 0
                    && (tvProseBody == null || tvProseBody.getLayout() != null || tvProseBody.length() == 0)) {
                action.run();
                return;
            }
            ViewTreeObserver vto = scrollContent.getViewTreeObserver();
            if (!vto.isAlive()) {
                action.run();
                return;
            }
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (scrollContent.getViewTreeObserver().isAlive()) {
                        scrollContent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    if (!isFinishing() && !isDestroyed()) {
                        scrollContent.post(action);
                    } else {
                        isRestoringPosition = false;
                    }
                }
            });
        };
        scrollContent.post(executeWhenReady);
    }

    private void restoreExplicitScrollYAfterLayout(int restoreY) {
        isRestoringPosition = true;
        runAfterProseLayout(() -> {
            if (scrollContent.getChildCount() > 0) {
                View child = scrollContent.getChildAt(0);
                int maxScroll = Math.max(0, child.getHeight() - scrollContent.getHeight());
                scrollContent.scrollTo(0, Math.min(maxScroll, Math.max(0, restoreY)));
            }
            isRestoringPosition = false;
            updateScrollProgress(scrollContent);
        });
    }

    private void restoreProsePositionAfterLayout(boolean useSavedBookPosition) {
        if (scrollContent == null || book == null || !useSavedBookPosition) {
            return;
        }
        isRestoringPosition = true;
        runAfterProseLayout(() -> {
            if (scrollContent.getChildCount() == 0) {
                isRestoringPosition = false;
                return;
            }
            View child = scrollContent.getChildAt(0);
            int viewportH = Math.max(1, scrollContent.getHeight());
            int maxPageH = Math.max(viewportH, child.getHeight());
            int maxScroll = Math.max(0, maxPageH - viewportH);
            int currentSizeSp = settingsDao.getReaderFontSizeSp();

            int targetY;
            if (currentSizeSp == book.getTextSizeSp()
                    && book.getScrollMaxHeight() > 0
                    && Math.abs(maxPageH - book.getScrollMaxHeight()) <= 4) {
                targetY = Math.min(maxScroll, book.getScrollY());
            } else if (book.getCharOffset() > 0
                    && tvProseBody != null
                    && tvProseBody.getLayout() != null
                    && tvProseBody.getText() != null) {
                int clampedOffset = Math.min(book.getCharOffset(), Math.max(0, tvProseBody.getText().length() - 1));
                int line = tvProseBody.getLayout().getLineForOffset(clampedOffset);
                targetY = Math.min(maxScroll, Math.max(0, tvProseBody.getTop() + tvProseBody.getLayout().getLineTop(line)));
            } else {
                targetY = Math.min(maxScroll, (int) ((book.getScrollPermille() * (long) maxScroll) / 10000L));
            }
            scrollContent.scrollTo(0, targetY);
            isRestoringPosition = false;
            updateScrollProgress(scrollContent);
        });
    }

    private void updateProgressFooter() {
        tvPageCounter.setText(getString(R.string.reader_page_counter, book.getCurrentPage(), book.getTotalPages()));
        tvProgressPercent.setText(getString(R.string.format_percent, book.getProgressPercent()));
        progressReader.setProgress(book.getProgressPercent());
    }

    private void completeCurrentChapterAndAdvance() {
        saveHandler.removeCallbacks(debouncedSaveRunnable);
        chapterDao.updateChapterProgress(book.getId(), currentChapterIndex, 100, true);

        int totalChapters = Math.max(1, book.getLenChapters());
        if (currentChapterIndex < totalChapters) {
            int nextIndex = currentChapterIndex + 1;
            int overallPct = Math.min(100, (currentChapterIndex * 100) / totalChapters);
            book.setProgressPercent(overallPct);
            book.setCurrentChapterCompleted(false);
            book.setStatus(Title.STATUS_IN_PROGRESS);
            loadChapter(nextIndex, false);
            return;
        }

        // Last chapter finished -> mark book completed without forcing chapter selector
        book.setProgressPercent(100);
        book.setCurrentChapterCompleted(true);
        book.setStatus(Title.STATUS_COMPLETED);
        book.setLastOpenedAt(System.currentTimeMillis());
        titleDao.update(book);
        PlaybackSessionManager.getInstance().syncActiveTitle(book);
        Toast.makeText(this, R.string.reader_book_completed_toast, Toast.LENGTH_LONG).show();
        finish();
    }

    private void saveCurrentReadingPosition() {
        saveHandler.removeCallbacks(debouncedSaveRunnable);
        if (book == null) {
            return;
        }
        if (!isRestoringPosition) {
            captureScrollStats(scrollContent);
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
        PlaybackSessionManager.getInstance().syncActiveTitle(book);
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
