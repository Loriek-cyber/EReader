package com.logininc.ereader.model;

/**
 * Represents a chapter belonging to either a {@link Book} or an {@link AudioBook}.
 */
public class Chapter {

    private int id;
    private int titleId;
    private int chapterIndex;
    private String chapterTitle;
    private String subtitle;
    private String pageOrDurationLabel;
    private String contentText;
    private long audioStartMs;
    private long audioDurationMs;
    private int progressPercent;
    private boolean completed;

    public Chapter(
            int id,
            int titleId,
            int chapterIndex,
            String chapterTitle,
            String subtitle,
            String pageOrDurationLabel,
            String contentText,
            long audioStartMs,
            long audioDurationMs,
            int progressPercent,
            boolean completed
    ) {
        this.id = id;
        this.titleId = titleId;
        this.chapterIndex = Math.max(1, chapterIndex);
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
        this.subtitle = subtitle != null ? subtitle : "";
        this.pageOrDurationLabel = pageOrDurationLabel != null ? pageOrDurationLabel : "";
        this.contentText = contentText != null ? contentText : "";
        this.audioStartMs = Math.max(0L, audioStartMs);
        this.audioDurationMs = Math.max(0L, audioDurationMs);
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
        this.completed = completed;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTitleId() {
        return titleId;
    }

    public void setTitleId(int titleId) {
        this.titleId = titleId;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = Math.max(1, chapterIndex);
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle != null ? subtitle : "";
    }

    public String getPageOrDurationLabel() {
        return pageOrDurationLabel;
    }

    public void setPageOrDurationLabel(String pageOrDurationLabel) {
        this.pageOrDurationLabel = pageOrDurationLabel != null ? pageOrDurationLabel : "";
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText != null ? contentText : "";
    }

    public long getAudioStartMs() {
        return audioStartMs;
    }

    public void setAudioStartMs(long audioStartMs) {
        this.audioStartMs = Math.max(0L, audioStartMs);
    }

    public long getAudioDurationMs() {
        return audioDurationMs;
    }

    public void setAudioDurationMs(long audioDurationMs) {
        this.audioDurationMs = Math.max(0L, audioDurationMs);
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
