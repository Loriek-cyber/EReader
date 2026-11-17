package com.logininc.ereader.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence/UI record for an ebook (EPUB or PDF) in the library.
 */
public class Book extends Title {

    private int currentPage = 1;
    private int totalPages = 1;
    private List<Chapter> chapters = new ArrayList<>();

    public Book(int id, @NonNull String title, int lenChapters, @NonNull Uri contentUri) {
        super(id, title, lenChapters, contentUri);
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

    @NonNull
    public List<Chapter> getChapters() {
        return chapters;
    }

    public void setChapters(@Nullable List<Chapter> chapters) {
        this.chapters = chapters != null ? chapters : new ArrayList<>();
    }

    @NonNull
    public List<Chapter> getFlattenedChapters() {
        List<Chapter> flattened = new ArrayList<>();
        flattenRecursive(chapters, flattened);
        return flattened;
    }

    private void flattenRecursive(@NonNull List<Chapter> nodes, @NonNull List<Chapter> out) {
        for (Chapter node : nodes) {
            out.add(node);
            if (!node.getChildren().isEmpty()) {
                flattenRecursive(node.getChildren(), out);
            }
        }
    }
}
