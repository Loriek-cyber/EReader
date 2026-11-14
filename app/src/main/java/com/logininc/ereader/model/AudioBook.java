package com.logininc.ereader.model;

import android.net.Uri;

/**
 * Represents an AudioBook entry persisted in SQLite and backed by a local audio URI.
 */
public class AudioBook extends Title {

    private String narrator;
    private long currentPositionMs;
    private long totalDurationMs;

    public AudioBook(int id, String title, int lenChapters, Uri contentUri) {
        super(id, title, lenChapters, contentUri);
        this.narrator = "";
        this.currentPositionMs = 0L;
        this.totalDurationMs = 0L;
    }

    public String getNarrator() {
        return narrator;
    }

    public void setNarrator(String narrator) {
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
