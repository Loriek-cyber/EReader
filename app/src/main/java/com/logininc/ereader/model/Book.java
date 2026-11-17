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
    private int scrollY = 0;
    private int scrollMaxHeight = 0;
    private int viewportHeight = 0;
    private int textSizeSp = 18;
    private int charOffset = 0;
    private int scrollPermille = 0;
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

    public int getScrollY() {
        return scrollY;
    }

    public void setScrollY(int scrollY) {
        this.scrollY = Math.max(0, scrollY);
    }

    public int getScrollMaxHeight() {
        return scrollMaxHeight;
    }

    public void setScrollMaxHeight(int scrollMaxHeight) {
        this.scrollMaxHeight = Math.max(0, scrollMaxHeight);
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = Math.max(0, viewportHeight);
    }

    public int getTextSizeSp() {
        return textSizeSp;
    }

    public void setTextSizeSp(int textSizeSp) {
        this.textSizeSp = Math.max(8, textSizeSp);
    }

    public int getCharOffset() {
        return charOffset;
    }

    public void setCharOffset(int charOffset) {
        this.charOffset = Math.max(0, charOffset);
    }

    public int getScrollPermille() {
        return scrollPermille;
    }

    public void setScrollPermille(int scrollPermille) {
        this.scrollPermille = Math.max(0, Math.min(10000, scrollPermille));
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
