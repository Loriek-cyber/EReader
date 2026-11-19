package com.logininc.ereader.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.screen.AudioChaptersActivity;
import com.logininc.ereader.screen.AudiobooksActivity;
import com.logininc.ereader.screen.EbookChaptersActivity;
import com.logininc.ereader.screen.LibraryActivity;
import com.logininc.ereader.screen.ProfileSettingsActivity;
import com.logininc.ereader.service.PlaybackSessionManager;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared UI binding helpers for the floating Mini-Player bar, bottom navigation, and cover images.
 */
public final class UiSessionHelper {

    private static final int COVER_MAX_DECODE_PX = 512;
    private static final ExecutorService COVER_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> COVER_CACHE;

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheSizeKb = Math.max(4096, Math.min(24 * 1024, maxKb / 8));
        COVER_CACHE = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    private UiSessionHelper() {
    }

    public static void bindBottomNavigation(
            @NonNull Activity activity,
            @NonNull BottomNavigationView bottomNav,
            int selectedItemId
    ) {
        bottomNav.setSaveEnabled(false);
        bottomNav.setOnItemSelectedListener(null);
        bottomNav.setOnItemReselectedListener(null);
        bottomNav.setSelectedItemId(selectedItemId);
        bottomNav.setOnItemReselectedListener(item -> {
            // No-op when reselecting the current tab
        });
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == selectedItemId) {
                return true;
            }
            Class<?> target = resolveBottomNavTarget(itemId);
            if (target != null) {
                navigateToBottomNavTarget(activity, target);
            }
            return false;
        });
    }

    public static void syncBottomNavigation(@NonNull Activity activity, int selectedItemId) {
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottomNavigation);
        if (bottomNav != null && bottomNav.getSelectedItemId() != selectedItemId) {
            bindBottomNavigation(activity, bottomNav, selectedItemId);
        }
    }

    @Nullable
    private static Class<?> resolveBottomNavTarget(int itemId) {
        if (itemId == R.id.nav_libreria) {
            return LibraryActivity.class;
        } else if (itemId == R.id.nav_audiolibri) {
            return AudiobooksActivity.class;
        } else if (itemId == R.id.nav_profilo) {
            return ProfileSettingsActivity.class;
        }
        return null;
    }

    public static void navigateToBottomNavTarget(@NonNull Activity activity, @NonNull Class<?> target) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Intent intent = new Intent(activity, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
        if (!activity.getClass().equals(target)) {
            activity.finish();
            activity.overridePendingTransition(0, 0);
        }
    }

    @NonNull
    public static PlaybackSessionManager.SessionListener attachMiniPlayer(@NonNull Activity activity) {
        View miniBar = activity.findViewById(R.id.miniPlayerBar);
        ImageView ivCover = activity.findViewById(R.id.ivMiniCover);
        ImageView ivTypeIcon = activity.findViewById(R.id.ivMiniTypeIcon);
        TextView tvTitle = activity.findViewById(R.id.tvMiniTitle);
        TextView tvSubtitle = activity.findViewById(R.id.tvMiniSubtitle);
        ImageButton btnRewind = activity.findViewById(R.id.btnMiniRewind);
        ImageButton btnPlayPause = activity.findViewById(R.id.btnMiniPlayPause);
        ImageButton btnStop = activity.findViewById(R.id.btnMiniStop);

        PlaybackSessionManager sessionManager = PlaybackSessionManager.getInstance();
        sessionManager.restoreIfNeeded(activity);

        if (miniBar != null) {
            miniBar.setOnClickListener(v -> {
                Title active = sessionManager.getActiveTitle();
                if (active != null) {
                    Title fresh = new TitleDao(activity).getById(active.getId());
                    Title targetTitle = fresh != null ? fresh : active;
                    NavigationFlowHelper.openReaderOrPlayer(activity, targetTitle, targetTitle.getCurrentChapter());
                    if (activity instanceof EbookChaptersActivity || activity instanceof AudioChaptersActivity) {
                        activity.finish();
                    }
                }
            });
        }
        if (btnRewind != null) {
            btnRewind.setOnClickListener(v -> sessionManager.seekByMs(-15_000L));
        }
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(v -> sessionManager.togglePlayPause(activity));
        }
        if (btnStop != null) {
            btnStop.setOnClickListener(v -> sessionManager.stopAndClearSession(activity));
        }

        PlaybackSessionManager.SessionListener listener = (activeTitle, isPlaying, positionMs, durationMs) ->
                activity.runOnUiThread(() -> updateMiniPlayerViews(
                        activity, miniBar, ivCover, ivTypeIcon, tvTitle, tvSubtitle,
                        btnRewind, btnPlayPause, activeTitle, isPlaying
                ));

        sessionManager.addListener(listener);
        updateMiniPlayerViews(
                activity, miniBar, ivCover, ivTypeIcon, tvTitle, tvSubtitle,
                btnRewind, btnPlayPause,
                sessionManager.getActiveTitle(),
                sessionManager.isPlaying()
        );
        return listener;
    }

    private static void updateMiniPlayerViews(
            @NonNull Activity activity,
            @Nullable View miniBar,
            @Nullable ImageView ivCover,
            @Nullable ImageView ivTypeIcon,
            @Nullable TextView tvTitle,
            @Nullable TextView tvSubtitle,
            @Nullable ImageButton btnRewind,
            @Nullable ImageButton btnPlayPause,
            @Nullable Title activeTitle,
            boolean isPlaying
    ) {
        if (miniBar == null) {
            return;
        }
        if (activeTitle == null) {
            miniBar.setVisibility(View.GONE);
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        boolean isAudio = activeTitle instanceof AudioBook;

        if (tvTitle != null) {
            tvTitle.setText(activeTitle.getTitle());
        }
        if (tvSubtitle != null) {
            int ch = activeTitle.getCurrentChapter();
            if (!isPlaying) {
                tvSubtitle.setText(activity.getString(R.string.mini_player_paused, ch));
            } else if (isAudio) {
                tvSubtitle.setText(activity.getString(R.string.mini_player_listening, ch));
            } else {
                tvSubtitle.setText(activity.getString(R.string.mini_player_reading, ch));
            }
        }
        if (ivTypeIcon != null) {
            ivTypeIcon.setImageResource(isAudio ? R.drawable.ic_equalizer : R.drawable.ic_book_open);
        }
        if (btnRewind != null) {
            btnRewind.setVisibility(isAudio ? View.VISIBLE : View.GONE);
        }
        if (btnPlayPause != null) {
            btnPlayPause.setVisibility(isAudio ? View.VISIBLE : View.GONE);
            btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        if (ivCover != null) {
            loadCover(ivCover, activeTitle.getCoverUri());
        }
    }

    public static void loadCover(@NonNull ImageView imageView, @Nullable Uri coverUri) {
        if (coverUri != null && "file".equals(coverUri.getScheme())) {
            String path = coverUri.getPath();
            if (path != null && !path.isEmpty()) {
                String uriKey = coverUri.toString();
                imageView.setTag(R.id.ivMiniCover, uriKey);
                Bitmap cached = COVER_CACHE.get(uriKey);
                if (cached != null && !cached.isRecycled()) {
                    imageView.setImageBitmap(cached);
                    return;
                }
                imageView.setImageDrawable(null);
                COVER_EXECUTOR.execute(() -> {
                    File file = new File(path);
                    if (!file.exists()) {
                        return;
                    }
                    Bitmap decoded = decodeSampledBitmap(path, COVER_MAX_DECODE_PX);
                    if (decoded != null) {
                        COVER_CACHE.put(uriKey, decoded);
                        MAIN_HANDLER.post(() -> {
                            if (uriKey.equals(imageView.getTag(R.id.ivMiniCover))) {
                                imageView.setImageBitmap(decoded);
                            }
                        });
                    }
                });
                return;
            }
        }
        imageView.setTag(R.id.ivMiniCover, null);
        imageView.setImageDrawable(null);
    }

    @Nullable
    private static Bitmap decodeSampledBitmap(@NonNull String path, int maxSidePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int inSampleSize = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while ((maxDim / inSampleSize) > maxSidePx) {
                inSampleSize *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = inSampleSize;
            decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, decodeOpts);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    public interface SubtreeInflater {
        void inflateChildren(
                @NonNull List<ChapterTreeHelper.TreeNode> children,
                @NonNull LinearLayout container,
                int nextDepth
        );
    }

    /**
     * Binds the hierarchical index summary and Expand/Collapse All toggle button.
     */
    public static void bindChapterTreeControls(
            @NonNull Activity activity,
            @NonNull List<Chapter> roots,
            boolean showControls,
            @NonNull View layoutControls,
            @NonNull TextView tvSummary,
            @NonNull MaterialButton btnToggleExpandAll,
            @NonNull Set<Integer> expandedIndices,
            @NonNull Runnable onToggled
    ) {
        if (!showControls || !ChapterTreeHelper.hasNestedChapters(roots)) {
            layoutControls.setVisibility(View.GONE);
            return;
        }
        layoutControls.setVisibility(View.VISIBLE);
        int nestedCount = ChapterTreeHelper.countNestedNodes(roots);
        tvSummary.setText(activity.getString(R.string.chapters_tree_summary_fmt, roots.size(), nestedCount));

        Set<Integer> expandable = ChapterTreeHelper.collectExpandableIndices(roots);
        updateExpandAllButtonLabel(btnToggleExpandAll, expandable, expandedIndices);
        btnToggleExpandAll.setOnClickListener(v -> {
            boolean allExpanded = !expandable.isEmpty() && expandedIndices.containsAll(expandable);
            if (allExpanded) {
                expandedIndices.clear();
            } else {
                expandedIndices.addAll(expandable);
            }
            updateExpandAllButtonLabel(btnToggleExpandAll, expandable, expandedIndices);
            onToggled.run();
        });
    }

    public static void updateExpandAllButtonLabel(
            @NonNull MaterialButton btnToggleExpandAll,
            @NonNull Set<Integer> expandableIndices,
            @NonNull Set<Integer> expandedIndices
    ) {
        boolean allExpanded = !expandableIndices.isEmpty() && expandedIndices.containsAll(expandableIndices);
        btnToggleExpandAll.setText(allExpanded ? R.string.chapters_collapse_all : R.string.chapters_expand_all);
    }

    /**
     * Binds expand/collapse controls and recursive subtree inflation for a chapter/section row.
     *
     * @return a {@link Runnable} that toggles expansion for this section, or {@code null} if this
     *         node is a leaf without children.
     */
    @Nullable
    public static Runnable bindRecursiveChapterChildren(
            @NonNull ChapterTreeHelper.TreeNode node,
            int depth,
            boolean filtering,
            @NonNull Set<Integer> expandedIndices,
            @NonNull ImageButton btnExpand,
            @Nullable View trailingLeafView,
            @NonNull View layoutChildrenWrapper,
            @NonNull View viewTreeGuide,
            @NonNull LinearLayout containerChildren,
            @Nullable Runnable onExpansionChanged,
            @NonNull SubtreeInflater subtreeInflater
    ) {
        if (node.children.isEmpty()) {
            btnExpand.setVisibility(View.GONE);
            if (trailingLeafView != null) {
                trailingLeafView.setVisibility(View.VISIBLE);
            }
            layoutChildrenWrapper.setVisibility(View.GONE);
            return null;
        }

        if (trailingLeafView != null) {
            trailingLeafView.setVisibility(View.GONE);
        }
        btnExpand.setVisibility(View.VISIBLE);

        if (depth >= 4) {
            int compactMargin = Math.round(4f * viewTreeGuide.getResources().getDisplayMetrics().density);
            setMarginStart(viewTreeGuide, compactMargin);
            setMarginStart(containerChildren, compactMargin);
        }

        int key = node.chapter.getChapterIndex();
        boolean expanded = filtering ? node.forceExpand : expandedIndices.contains(key);
        btnExpand.setRotation(expanded ? 0f : -90f);

        if (expanded) {
            layoutChildrenWrapper.setVisibility(View.VISIBLE);
            subtreeInflater.inflateChildren(node.children, containerChildren, depth + 1);
        } else {
            layoutChildrenWrapper.setVisibility(View.GONE);
        }

        Runnable toggleAction = () -> {
            boolean nowExpanded = layoutChildrenWrapper.getVisibility() != View.VISIBLE;
            if (!filtering) {
                if (nowExpanded) {
                    expandedIndices.add(key);
                } else {
                    expandedIndices.remove(key);
                }
                if (onExpansionChanged != null) {
                    onExpansionChanged.run();
                }
            }
            if (nowExpanded && containerChildren.getChildCount() == 0) {
                subtreeInflater.inflateChildren(node.children, containerChildren, depth + 1);
            }
            layoutChildrenWrapper.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            btnExpand.animate().rotation(nowExpanded ? 0f : -90f).setDuration(150).start();
        };

        btnExpand.setOnClickListener(v -> toggleAction.run());
        return toggleAction;
    }

    private static void setMarginStart(@NonNull View view, int marginPx) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
            lp.setMarginStart(marginPx);
            view.setLayoutParams(lp);
        }
    }

    /**
     * Hides the bottom navigation / mini-player dock while the soft keyboard (IME) is open so it
     * never jumps up over search results, and restores it cleanly when the keyboard closes.
     */
    public static void attachImeSafeBottomDock(
            @NonNull View rootView,
            @Nullable View bottomDock,
            @Nullable View scrollView
    ) {
        final int initialPadBottom = scrollView != null ? scrollView.getPaddingBottom() : 0;
        final int compactPadBottom = Math.round(24f * rootView.getResources().getDisplayMetrics().density);

        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (bottomDock != null) {
                bottomDock.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            }
            if (scrollView != null) {
                int targetBottom = imeVisible ? compactPadBottom : initialPadBottom;
                if (scrollView.getPaddingBottom() != targetBottom) {
                    scrollView.setPadding(
                            scrollView.getPaddingLeft(),
                            scrollView.getPaddingTop(),
                            scrollView.getPaddingRight(),
                            targetBottom
                    );
                }
            }
            return insets;
        });
    }

    /**
     * Wires a clear ("X") button and IME search action (dismiss keyboard) for a search {@link EditText}.
     */
    public static void bindSearchClearAndImeAction(
            @NonNull EditText editText,
            @Nullable View clearButton
    ) {
        if (clearButton != null) {
            CharSequence initial = editText.getText();
            clearButton.setVisibility(initial != null && initial.length() > 0 ? View.VISIBLE : View.GONE);
            clearButton.setOnClickListener(v -> {
                editText.setText("");
                editText.clearFocus();
                hideKeyboard(editText);
            });
        }
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus();
                hideKeyboard(v);
                return true;
            }
            return false;
        });
    }

    public static void hideKeyboard(@NonNull View view) {
        InputMethodManager imm = (InputMethodManager) view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
