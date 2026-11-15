package com.logininc.ereader.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.screen.AudiobooksActivity;
import com.logininc.ereader.screen.LibraryActivity;
import com.logininc.ereader.screen.ProfileSettingsActivity;
import com.logininc.ereader.service.PlaybackSessionManager;

import java.io.File;

/**
 * Shared UI binding helpers for the floating Mini-Player bar, bottom navigation, and cover images.
 */
public final class UiSessionHelper {

    private UiSessionHelper() {
    }

    public static void bindBottomNavigation(
            @NonNull Activity activity,
            @NonNull BottomNavigationView bottomNav,
            int selectedItemId
    ) {
        bottomNav.setSelectedItemId(selectedItemId);
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == selectedItemId) {
                return true;
            }
            Class<?> target = null;
            if (itemId == R.id.nav_libreria) {
                target = LibraryActivity.class;
            } else if (itemId == R.id.nav_audiolibri) {
                target = AudiobooksActivity.class;
            } else if (itemId == R.id.nav_profilo) {
                target = ProfileSettingsActivity.class;
            }
            if (target != null) {
                Intent intent = new Intent(activity, target);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                return true;
            }
            return false;
        });
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
                    NavigationFlowHelper.openReaderOrPlayer(activity, active, active.getCurrentChapter());
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
            if (path != null && new File(path).exists()) {
                imageView.setImageURI(null);
                imageView.setImageURI(coverUri);
                return;
            }
        }
        imageView.setImageDrawable(null);
    }
}
