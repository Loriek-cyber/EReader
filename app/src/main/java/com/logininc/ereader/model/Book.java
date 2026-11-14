package com.logininc.ereader.model;

import android.net.Uri;

/**
 * Represents an eBook entry persisted in SQLite and backed by a local document URI.
 */
public class Book extends Title {

    private int currentPage;
    private int totalPages;

    public Book(int id, String title, int lenChapters, Uri contentUri) {
        super(id, title, lenChapters, contentUri);
        this.currentPage = 1;
        this.totalPages = 1;
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
}
