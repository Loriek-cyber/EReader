package com.logininc.ereader.util;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.screen.AudioChaptersActivity;
import com.logininc.ereader.screen.AudioPlayerActivity;
import com.logininc.ereader.screen.EbookChaptersActivity;
import com.logininc.ereader.screen.EbookReaderActivity;

/**
 * Implements the core navigation flows:
 * 1) New title or concluded chapter -> Chapter Selector -> Reader/Player
 * 2) In-progress title (chapter not concluded) -> Reader/Player directly
 */
public final class NavigationFlowHelper {

    public static final String EXTRA_TITLE_ID = "extra_title_id";
    public static final String EXTRA_CHAPTER_INDEX = "extra_chapter_index";
    public static final String EXTRA_AUTO_PLAY = "extra_auto_play";
    public static final String EXTRA_SKIP_RESUME_REWIND = "extra_skip_resume_rewind";

    private NavigationFlowHelper() {
    }

    /**
     * Pure decision rule for routing:
     * returns true when the title must go through the chapter selector first.
     */
    public static boolean shouldOpenChapterSelector(@NonNull Title title) {
        return title.requiresChapterSelector();
    }

    public static void openTitle(@NonNull Context context, @NonNull Title title) {
        if (shouldOpenChapterSelector(title)) {
            openChapterSelector(context, title);
        } else {
            openReaderOrPlayer(context, title, title.getCurrentChapter());
        }
    }

    public static void openChapterSelector(@NonNull Context context, @NonNull Title title) {
        Class<?> target = (title instanceof AudioBook)
                ? AudioChaptersActivity.class
                : EbookChaptersActivity.class;
        Intent intent = new Intent(context, target);
        intent.putExtra(EXTRA_TITLE_ID, title.getId());
        context.startActivity(intent);
    }

    public static void openReaderOrPlayer(@NonNull Context context, @NonNull Title title, int chapterIndex) {
        Class<?> target = (title instanceof AudioBook)
                ? AudioPlayerActivity.class
                : EbookReaderActivity.class;
        Intent intent = new Intent(context, target);
        intent.putExtra(EXTRA_TITLE_ID, title.getId());
        intent.putExtra(EXTRA_CHAPTER_INDEX, Math.max(1, chapterIndex));
        context.startActivity(intent);
    }
}
