package com.logininc.ereader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a chapter or nested section/subsection belonging to either a {@link Book}
 * or an {@link AudioBook}. Mirrors the recursive TOC hierarchy (title, href, fragmentId,
 * level, children) while keeping a 1-based sequential {@code chapterIndex} for linear navigation.
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
    private final List<Chapter> children;

    /**
     * Backwards-compatible constructor for flat chapter creation.
     */
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
        this(
                id,
                titleId,
                0,
                chapterIndex,
                0,
                Math.max(0, chapterIndex - 1),
                chapterTitle,
                subtitle,
                "",
                "",
                pageOrDurationLabel,
                contentText,
                "",
                audioStartMs,
                audioDurationMs,
                progressPercent,
                completed
        );
    }

    /**
     * Convenience constructor for hierarchical EPUB TOC chapters and nested sections.
     */
    public Chapter(
            int chapterIndex,
            int level,
            int sortOrder,
            String chapterTitle,
            String href,
            String fragmentId,
            String contentText,
            String rawHtml
    ) {
        this(
                0,
                0,
                0,
                chapterIndex,
                level,
                sortOrder,
                chapterTitle,
                "",
                href,
                fragmentId,
                "",
                contentText,
                rawHtml,
                0L,
                0L,
                0,
                false
        );
    }

    /**
     * Full constructor including hierarchical parent linkage, depth level, and EPUB TOC metadata.
     */
    public Chapter(
            int id,
            int titleId,
            int parentChapterId,
            int chapterIndex,
            int level,
            int sortOrder,
            String chapterTitle,
            String subtitle,
            String href,
            String fragmentId,
            String pageOrDurationLabel,
            String contentText,
            String rawHtml,
            long audioStartMs,
            long audioDurationMs,
            int progressPercent,
            boolean completed
    ) {
        this.id = id;
        this.titleId = titleId;
        this.parentChapterId = Math.max(0, parentChapterId);
        this.chapterIndex = Math.max(1, chapterIndex);
        this.level = Math.max(0, level);
        this.sortOrder = Math.max(0, sortOrder);
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
        this.children = new ArrayList<>();
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
        this.parentChapterId = Math.max(0, parentChapterId);
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = Math.max(1, chapterIndex);
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(0, level);
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = Math.max(0, sortOrder);
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

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href != null ? href : "";
    }

    public String getFragmentId() {
        return fragmentId;
    }

    public void setFragmentId(String fragmentId) {
        this.fragmentId = fragmentId != null ? fragmentId : "";
    }

    /**
     * Returns {@code href} combined with {@code #fragmentId} when a fragment is present.
     */
    public String getFullHref() {
        if (href.isEmpty()) {
            return "";
        }
        return fragmentId.isEmpty() ? href : href + "#" + fragmentId;
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

    public String getRawHtml() {
        return rawHtml;
    }

    public void setRawHtml(String rawHtml) {
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

    public List<Chapter> getChildren() {
        return children;
    }

    public void setChildren(List<Chapter> children) {
        this.children.clear();
        if (children != null) {
            for (Chapter child : children) {
                addChild(child);
            }
        }
    }

    public void addChild(Chapter child) {
        if (child != null) {
            this.children.add(child);
        }
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public boolean isSection() {
        return level > 0 || parentChapterId > 0;
    }

    /**
     * Returns this node followed by all descendants in pre-order traversal.
     */
    public List<Chapter> flatten() {
        List<Chapter> result = new ArrayList<>();
        collectPreOrder(this, result);
        return result;
    }

    /**
     * Flattens a forest of root chapters and their nested children in pre-order traversal.
     */
    public static List<Chapter> flattenTree(List<Chapter> roots) {
        List<Chapter> result = new ArrayList<>();
        if (roots != null) {
            for (Chapter root : roots) {
                if (root != null) {
                    collectPreOrder(root, result);
                }
            }
        }
        return result;
    }

    private static void collectPreOrder(Chapter node, List<Chapter> out) {
        out.add(node);
        for (Chapter child : node.children) {
            if (child != null) {
                collectPreOrder(child, out);
            }
        }
    }
}
