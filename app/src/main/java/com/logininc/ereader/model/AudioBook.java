package com.logininc.ereader.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Persistence/UI record for an audiobook (M4B/audio) in the library.
 */
public class AudioBook extends Title {

    private String narrator = "";
    private long currentPositionMs = 0L;
    private long totalDurationMs = 0L;

    public AudioBook(int id, @NonNull String title, int lenChapters, @NonNull Uri contentUri) {
        super(id, title, lenChapters, contentUri);
    }

    @NonNull
    public String getNarrator() {
        return narrator != null ? narrator : "";
    }

    public void setNarrator(@Nullable String narrator) {
        this.narrator = narrator != null ? narrator : "";
    }

    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    public void setCurrentPositionMs(long currentPositionMs) {
        this.currentPositionMs = Math.max(0L, currentPositionMs);
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long totalDurationMs) {
        this.totalDurationMs = Math.max(0L, totalDurationMs);
    }
}
