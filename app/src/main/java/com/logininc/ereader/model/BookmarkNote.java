package com.logininc.ereader.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Persistence/UI record for a bookmark or note attached to a title and chapter.
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
            @NonNull String entryType,
            @Nullable String positionLabel,
            @Nullable String noteText,
            long createdAt
    ) {
        this.id = id;
        this.titleId = titleId;
        this.chapterIndex = chapterIndex;
        this.entryType = entryType;
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

    public int getChapterIndex() {
        return chapterIndex;
    }

    @NonNull
    public String getEntryType() {
        return entryType;
    }

    @NonNull
    public String getPositionLabel() {
        return positionLabel;
    }

    @NonNull
    public String getNoteText() {
        return noteText;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
