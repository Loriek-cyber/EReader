package com.logininc.ereader.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Base persistence/UI record for a library item (ebook or audiobook).
 */
public abstract class Title {

    public static final String STATUS_TO_START = "TO_START";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private int id;
    private String title;
    private String author = "";
    private int lenChapters;
    private int currentChapter = 1;
    private boolean currentChapterCompleted = false;
    private int progressPercent = 0;
    private Uri contentUri;
    @Nullable
    private Uri coverUri;
    private String status = STATUS_TO_START;
    private long lastOpenedAt = 0L;

    protected Title(int id, @NonNull String title, int lenChapters, @NonNull Uri contentUri) {
        this.id = id;
        this.title = title;
        this.lenChapters = Math.max(1, lenChapters);
        this.contentUri = contentUri;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @NonNull
    public String getTitle() {
        return title != null ? title : "";
    }

    public void setTitle(@NonNull String title) {
        this.title = title;
    }

    @NonNull
    public String getAuthor() {
        return author != null ? author : "";
    }

    public void setAuthor(@Nullable String author) {
        this.author = author != null ? author : "";
    }

    public int getLenChapters() {
        return lenChapters;
    }

    public void setLenChapters(int lenChapters) {
        this.lenChapters = Math.max(1, lenChapters);
    }

    public int getCurrentChapter() {
        return currentChapter;
    }

    public void setCurrentChapter(int currentChapter) {
        this.currentChapter = Math.max(1, currentChapter);
    }

    public boolean isCurrentChapterCompleted() {
        return currentChapterCompleted;
    }

    public void setCurrentChapterCompleted(boolean currentChapterCompleted) {
        this.currentChapterCompleted = currentChapterCompleted;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
    }

    @NonNull
    public Uri getContentUri() {
        return contentUri != null ? contentUri : Uri.EMPTY;
    }

    public void setContentUri(@NonNull Uri contentUri) {
        this.contentUri = contentUri;
    }

    @Nullable
    public Uri getCoverUri() {
        return coverUri;
    }

    public void setCoverUri(@Nullable Uri coverUri) {
        this.coverUri = coverUri;
    }

    @NonNull
    public String getStatus() {
        return status != null ? status : STATUS_TO_START;
    }

    public void setStatus(@Nullable String status) {
        this.status = status != null && !status.isEmpty() ? status : STATUS_TO_START;
    }

    public long getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(long lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }

    public boolean requiresChapterSelector() {
        return STATUS_TO_START.equals(getStatus()) && lenChapters > 1;
    }
}
