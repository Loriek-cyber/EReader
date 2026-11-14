package com.logininc.ereader.model;

/**
 * Represents a saved bookmark or textual note linked to a {@link Title} and chapter.
 */
public class BookmarkNote {

    public static final String TYPE_BOOKMARK = "BOOKMARK";
    public static final String TYPE_NOTE = "NOTE";

    private int id;
    private int titleId;
    private int chapterIndex;
    private String entryType;
    private String positionLabel;
    private String noteText;
    private long createdAt;

    public BookmarkNote(
            int id,
            int titleId,
            int chapterIndex,
            String entryType,
            String positionLabel,
            String noteText,
            long createdAt
    ) {
        this.id = id;
        this.titleId = titleId;
        this.chapterIndex = Math.max(1, chapterIndex);
        this.entryType = entryType != null ? entryType : TYPE_BOOKMARK;
        this.positionLabel = positionLabel != null ? positionLabel : "";
        this.noteText = noteText != null ? noteText : "";
        this.createdAt = createdAt;
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

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType != null ? entryType : TYPE_BOOKMARK;
    }

    public String getPositionLabel() {
        return positionLabel;
    }

    public void setPositionLabel(String positionLabel) {
        this.positionLabel = positionLabel != null ? positionLabel : "";
    }

    public String getNoteText() {
        return noteText;
    }

    public void setNoteText(String noteText) {
        this.noteText = noteText != null ? noteText : "";
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
