package com.logininc.ereader.model;

import android.net.Uri;

import java.util.List;

/**
 * Represents an eBook entry persisted in SQLite and backed by a local document URI.
 */
public class Book extends Title {

    private int currentPage;
    private int totalPages;
    private byte[] coverBytes;

    public Book(int id, String title, int lenChapters, Uri contentUri) {
        super(id, title, lenChapters, contentUri);
        this.currentPage = 1;
        this.totalPages = 1;
        this.coverBytes = null;
    }

    /**
     * Convenience constructor for creating a parsed {@link Book} with metadata and hierarchical chapters.
     */
    public Book(
            int id,
            String title,
            String author,
            Uri contentUri,
            byte[] coverBytes,
            List<Chapter> chapters
    ) {
        super(
                id,
                title,
                Math.max(1, Chapter.flattenTree(chapters).size()),
                contentUri
        );
        this.currentPage = 1;
        this.totalPages = 1;
        this.coverBytes = coverBytes;
        setAuthor(author);
        setChapters(chapters);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = Math.max(1, currentPage);
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = Math.max(1, totalPages);
    }

    public byte[] getCoverBytes() {
        return coverBytes;
    }

    public void setCoverBytes(byte[] coverBytes) {
        this.coverBytes = coverBytes;
    }
}
