package com.logininc.ereader.screen;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.logininc.ereader.R;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.DatabaseContract.TitleEntry;
import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.M4bParser;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.logininc.ereader.util.PermissionHelper;
import com.logininc.ereader.util.ThemeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LibraryActivity extends AppCompatActivity {

    private static boolean permissionPromptShown = false;

    private TitleDao titleDao;
    private SettingsDao settingsDao;
    private String appliedThemeMode;
    private String appliedLanguageTag;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private EditText etSearch;
    private View sectionInProgress;
    private View cardContinueReading;
    private ImageView ivHeroCover;
    private TextView tvHeroChapterBadge;
    private TextView tvHeroPageCount;
    private TextView tvHeroTitle;
    private TextView tvHeroAuthor;
    private TextView tvHeroPercent;
    private LinearProgressIndicator progressHero;
    private MaterialButton btnHeroIndex;
    private MaterialButton btnHeroResume;
    private TextView tvLibraryCount;
    private View layoutEmpty;
    private LinearLayout gridBooks;
    private PlaybackSessionManager.SessionListener miniPlayerListener;

    private final ActivityResultLauncher<String[]> openEbookLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importPickedEbook(uri);
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
        setContentView(R.layout.activity_library);

        titleDao = new TitleDao(this);
        bindViews();
        requestStoragePermissionsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsDao.getThemeMode().equals(appliedThemeMode)
                || !settingsDao.getLanguageTag().equals(appliedLanguageTag)) {
            recreate();
            return;
        }
        UiSessionHelper.syncBottomNavigation(this, R.id.nav_libreria);
        miniPlayerListener = UiSessionHelper.attachMiniPlayer(this);
        refreshLibrary();
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
        etSearch = findViewById(R.id.etLibrarySearch);
        sectionInProgress = findViewById(R.id.sectionLibraryInProgress);
        cardContinueReading = findViewById(R.id.cardContinueReading);
        ivHeroCover = findViewById(R.id.ivHeroCover);
        tvHeroChapterBadge = findViewById(R.id.tvHeroChapterBadge);
        tvHeroPageCount = findViewById(R.id.tvHeroPageCount);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroAuthor = findViewById(R.id.tvHeroAuthor);
        tvHeroPercent = findViewById(R.id.tvHeroPercent);
        progressHero = findViewById(R.id.progressHero);
        btnHeroIndex = findViewById(R.id.btnHeroIndex);
        btnHeroResume = findViewById(R.id.btnHeroResume);
        tvLibraryCount = findViewById(R.id.tvLibraryCount);
        layoutEmpty = findViewById(R.id.layoutLibraryEmpty);
        gridBooks = findViewById(R.id.gridLibraryBooks);

        View.OnClickListener importListener = v -> launchEbookPicker();
        findViewById(R.id.btnImportEbook).setOnClickListener(importListener);
        findViewById(R.id.btnEmptyImportEbook).setOnClickListener(importListener);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshLibrary();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        UiSessionHelper.bindBottomNavigation(this, bottomNav, R.id.nav_libreria);
    }

    private void requestStoragePermissionsIfNeeded() {
        if (!permissionPromptShown && !PermissionHelper.hasStorageOrAudioPermission(this)) {
            permissionPromptShown = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_rationale_title)
                    .setMessage(R.string.permission_rationale_message)
                    .setPositiveButton(R.string.permission_grant, (d, w) ->
                            PermissionHelper.requestMissingPermissions(this))
                    .setNegativeButton(R.string.permission_later, null)
                    .show();
        }
    }

    private void launchEbookPicker() {
        openEbookLauncher.launch(new String[]{
                "application/epub+zip",
                "application/pdf",
                "text/plain",
                "*/*"
        });
    }

    private void importPickedEbook(@NonNull Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        String name = FileScanner.queryDisplayName(this, uri);
        String mime = getContentResolver().getType(uri);
        if (!FileScanner.isEbookFile(name) && !FileScanner.isAudioFile(name) && !M4bParser.isM4bOrAudioMime(mime)) {
            Toast.makeText(this, R.string.settings_ebook_formats, Toast.LENGTH_SHORT).show();
            return;
        }
        ioExecutor.execute(() -> {
            Title imported = FileScanner.importSingleUri(getApplicationContext(), uri);
            runOnUiThread(() -> {
                if (imported != null) {
                    refreshLibrary();
                    NavigationFlowHelper.openTitle(this, imported);
                } else {
                    Toast.makeText(this, R.string.reader_file_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshLibrary() {
        String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";

        Book hero = query.isEmpty() ? titleDao.getMostRecentInProgressBook() : null;
        bindHeroBook(hero);

        List<Book> books;
        if (query.isEmpty()) {
            books = titleDao.getAllBooks();
        } else {
            List<Title> matched = titleDao.searchByTypeAndQuery(TitleEntry.TYPE_BOOK, query);
            books = new java.util.ArrayList<>(matched.size());
            for (Title t : matched) {
                if (t instanceof Book) {
                    books.add((Book) t);
                }
            }
        }

        tvLibraryCount.setText(getString(R.string.format_titles_count, books.size()));
        renderBooksGrid(books);
    }

    private void bindHeroBook(Book hero) {
        if (hero == null) {
            sectionInProgress.setVisibility(View.GONE);
            return;
        }
        sectionInProgress.setVisibility(View.VISIBLE);
        UiSessionHelper.loadCover(ivHeroCover, hero.getCoverUri());
        tvHeroChapterBadge.setText(getString(R.string.format_chapter_n, hero.getCurrentChapter()));
        tvHeroPageCount.setText(getString(R.string.format_pages_of, hero.getCurrentPage(), hero.getTotalPages()));
        tvHeroTitle.setText(hero.getTitle());
        tvHeroAuthor.setText(hero.getAuthor());
        tvHeroAuthor.setVisibility(hero.getAuthor().isEmpty() ? View.GONE : View.VISIBLE);
        tvHeroPercent.setText(getString(R.string.format_percent, hero.getProgressPercent()));
        progressHero.setProgress(hero.getProgressPercent());

        btnHeroIndex.setOnClickListener(v -> NavigationFlowHelper.openChapterSelector(this, hero));
        View.OnClickListener openFlow = v -> NavigationFlowHelper.openTitle(this, hero);
        btnHeroResume.setOnClickListener(openFlow);
        cardContinueReading.setOnClickListener(openFlow);
    }

    private void renderBooksGrid(@NonNull List<Book> books) {
        gridBooks.removeAllViews();
        if (books.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        int gapPx = getResources().getDimensionPixelSize(R.dimen.space_md);

        for (int i = 0; i < books.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = gapPx;
            row.setLayoutParams(rowParams);

            View leftCard = createBookCardView(inflater, row, books.get(i));
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            leftParams.setMarginEnd(gapPx / 2);
            row.addView(leftCard, leftParams);

            if (i + 1 < books.size()) {
                View rightCard = createBookCardView(inflater, row, books.get(i + 1));
                LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                rightParams.setMarginStart(gapPx / 2);
                row.addView(rightCard, rightParams);
            } else {
                Space placeholder = new Space(this);
                LinearLayout.LayoutParams spaceParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                spaceParams.setMarginStart(gapPx / 2);
                row.addView(placeholder, spaceParams);
            }
            gridBooks.addView(row);
        }
    }

    @NonNull
    private View createBookCardView(@NonNull LayoutInflater inflater, @NonNull LinearLayout parent, @NonNull Book book) {
        View card = inflater.inflate(R.layout.item_book_grid, parent, false);
        ImageView ivCover = card.findViewById(R.id.ivBookCover);
        TextView tvTitle = card.findViewById(R.id.tvBookTitle);
        TextView tvAuthor = card.findViewById(R.id.tvBookAuthor);
        TextView tvStatusLabel = card.findViewById(R.id.tvBookStatusLabel);
        TextView tvStatusValue = card.findViewById(R.id.tvBookStatusValue);
        LinearProgressIndicator progressBar = card.findViewById(R.id.progressBook);
        MaterialButton btnAction = card.findViewById(R.id.btnBookAction);

        UiSessionHelper.loadCover(ivCover, book.getCoverUri());
        tvTitle.setText(book.getTitle());
        tvAuthor.setText(book.getAuthor());
        tvAuthor.setVisibility(book.getAuthor().isEmpty() ? View.GONE : View.VISIBLE);

        tvStatusLabel.setText(R.string.label_status);
        progressBar.setProgress(book.getProgressPercent());

        if (Title.STATUS_COMPLETED.equals(book.getStatus())) {
            tvStatusValue.setText(R.string.status_completed);
            btnAction.setText(R.string.action_reread);
        } else if (book.requiresChapterSelector()) {
            tvStatusValue.setText(R.string.status_to_start);
            btnAction.setText(R.string.action_start_reading);
        } else {
            tvStatusValue.setText(getString(R.string.format_percent, book.getProgressPercent()));
            btnAction.setText(R.string.action_resume);
        }

        View.OnClickListener openFlow = v -> NavigationFlowHelper.openTitle(this, book);
        card.setOnClickListener(openFlow);
        btnAction.setOnClickListener(openFlow);
        card.setOnLongClickListener(v -> {
            confirmDeleteBook(book);
            return true;
        });
        return card;
    }

    private void confirmDeleteBook(@NonNull Book book) {
        new AlertDialog.Builder(this)
                .setTitle(book.getTitle())
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    if (PlaybackSessionManager.getInstance().getActiveTitle() != null
                            && PlaybackSessionManager.getInstance().getActiveTitle().getId() == book.getId()) {
                        PlaybackSessionManager.getInstance().stopAndClearSession(this);
                    }
                    titleDao.deleteById(book.getId());
                    refreshLibrary();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
