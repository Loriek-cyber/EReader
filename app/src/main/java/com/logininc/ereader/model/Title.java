package com.logininc.ereader.model;

import android.net.Uri;

/**
 * Base model representing a readable or playable library item (Book or AudioBook).
 */
public abstract class Title {

    public static final String STATUS_TO_START = "TO_START";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private int id;
    private String title;
    private String author;
    private int lenChapters;
    private int currentChapter;
    private boolean currentChapterCompleted;
    private int progressPercent;
    private Uri contentUri;
    private Uri coverUri;
    private String status;
    private long lastOpenedAt;

    public Title(int id, String title, int lenChapters, Uri contentUri) {
        this.id = id;
        this.title = title;
        this.author = "";
        this.lenChapters = Math.max(1, lenChapters);
        this.currentChapter = 1;
        this.currentChapterCompleted = false;
        this.progressPercent = 0;
        this.contentUri = contentUri;
        this.coverUri = null;
        this.status = STATUS_TO_START;
        this.lastOpenedAt = 0L;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
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

    public Uri getContentUri() {
        return contentUri;
    }

    public void setContentUri(Uri contentUri) {
        this.contentUri = contentUri;
    }

    public Uri getCoverUri() {
        return coverUri;
    }

    public void setCoverUri(Uri coverUri) {
        this.coverUri = coverUri;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : STATUS_TO_START;
    }

    public long getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(long lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }

    /**
     * Returns true when opening this title should go to the chapter selector first:
     * - New title (TO_START or progress == 0)
     * - Current chapter concluded or full title completed
     */
    public boolean requiresChapterSelector() {
        return progressPercent <= 0
                || STATUS_TO_START.equals(status)
                || currentChapterCompleted
                || STATUS_COMPLETED.equals(status);
    }
}
