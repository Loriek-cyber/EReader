package com.logininc.ereader.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence/UI record for a chapter or nested section of a book or audiobook.
 */
public class Chapter {

    private int id;
    private int titleId;
    private int parentChapterId;
    private int chapterIndex;
    private int level;
    private int sortOrder;
    private String chapterTitle;
    private String subtitle;
    private String href;
    private String fragmentId;
    private String pageOrDurationLabel;
    private String contentText;
    private String rawHtml;
    private long audioStartMs;
    private long audioDurationMs;
    private int progressPercent;
    private boolean completed;
    private final List<Chapter> children = new ArrayList<>();

    public Chapter(
            int id,
            int titleId,
            int parentChapterId,
            int chapterIndex,
            int level,
            int sortOrder,
            @Nullable String chapterTitle,
            @Nullable String subtitle,
            @Nullable String href,
            @Nullable String fragmentId,
            @Nullable String pageOrDurationLabel,
            @Nullable String contentText,
            @Nullable String rawHtml,
            long audioStartMs,
            long audioDurationMs,
            int progressPercent,
            boolean completed
    ) {
        this.id = id;
        this.titleId = titleId;
        this.parentChapterId = parentChapterId;
        this.chapterIndex = chapterIndex;
        this.level = level;
        this.sortOrder = sortOrder;
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
        this.subtitle = subtitle != null ? subtitle : "";
        this.href = href != null ? href : "";
        this.fragmentId = fragmentId != null ? fragmentId : "";
        this.pageOrDurationLabel = pageOrDurationLabel != null ? pageOrDurationLabel : "";
        this.contentText = contentText != null ? contentText : "";
        this.rawHtml = rawHtml != null ? rawHtml : "";
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

    public int getParentChapterId() {
        return parentChapterId;
    }

    public void setParentChapterId(int parentChapterId) {
        this.parentChapterId = parentChapterId;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    @NonNull
    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(@Nullable String chapterTitle) {
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
    }

    @NonNull
    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(@Nullable String subtitle) {
        this.subtitle = subtitle != null ? subtitle : "";
    }

    @NonNull
    public String getHref() {
        return href;
    }

    public void setHref(@Nullable String href) {
        this.href = href != null ? href : "";
    }

    @NonNull
    public String getFragmentId() {
        return fragmentId;
    }

    public void setFragmentId(@Nullable String fragmentId) {
        this.fragmentId = fragmentId != null ? fragmentId : "";
    }

    @NonNull
    public String getPageOrDurationLabel() {
        return pageOrDurationLabel;
    }

    public void setPageOrDurationLabel(@Nullable String pageOrDurationLabel) {
        this.pageOrDurationLabel = pageOrDurationLabel != null ? pageOrDurationLabel : "";
    }

    @NonNull
    public String getContentText() {
        return contentText;
    }

    public void setContentText(@Nullable String contentText) {
        this.contentText = contentText != null ? contentText : "";
    }

    @NonNull
    public String getRawHtml() {
        return rawHtml;
    }

    public void setRawHtml(@Nullable String rawHtml) {
        this.rawHtml = rawHtml != null ? rawHtml : "";
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

    @NonNull
    public List<Chapter> getChildren() {
        return children;
    }

    public void addChild(@NonNull Chapter child) {
        children.add(child);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    @NonNull
    public static List<Chapter> flattenTree(@NonNull List<Chapter> roots) {
        List<Chapter> out = new ArrayList<>();
        flattenRecursive(roots, out);
        return out;
    }

    private static void flattenRecursive(@NonNull List<Chapter> nodes, @NonNull List<Chapter> out) {
        for (Chapter node : nodes) {
            out.add(node);
            if (!node.children.isEmpty()) {
                flattenRecursive(node.children, out);
            }
        }
    }
}
