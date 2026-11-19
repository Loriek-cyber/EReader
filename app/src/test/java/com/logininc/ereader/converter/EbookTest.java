package com.logininc.ereader.converter;

import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.EbookParser;
import com.logininc.ereader.util.FileScanner;

import org.junit.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import io.documentnode.epub4j.domain.Book;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EbookTest {
    InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("claude.epub");

    @Test
    public void assertItLoads() {
        assertNotNull(is);
    }

    @Test
    public void assertItTheParserWorks() {
        Book book = EbookParser.parse(is);
        assertNotNull(book);
        assertNotNull(book.getTitle());
        assertFalse(book.getTitle().trim().isEmpty());
        assertNotNull(book.getSpine());
        assertFalse(book.getSpine().getSpineReferences().isEmpty());
    }

    @Test
    public void parsesClaudeEpubHierarchyWithoutContainerDuplication() {
        Book epubBook = EbookParser.parse(is);
        assertNotNull(epubBook);

        List<Chapter> roots = FileScanner.buildEpubChapters(epubBook);
        assertTrue("Expected at least 10 root TOC entries in claude.epub", roots.size() >= 10);

        Chapter part1 = null;
        Chapter appendices = null;
        for (Chapter root : roots) {
            if (root.getChapterTitle().startsWith("Part I")) {
                part1 = root;
            } else if ("Appendices".equals(root.getChapterTitle())) {
                appendices = root;
            }
        }
        assertNotNull("Part I should exist", part1);
        assertEquals(4, part1.getChildren().size());
        assertEquals("", part1.getContentText());

        Chapter ch1 = part1.getChildren().get(0);
        assertTrue(ch1.getChapterTitle().startsWith("Chapter 1"));
        assertFalse(ch1.getContentText().isEmpty());
        assertTrue("Chapter text should preserve paragraph breaks", ch1.getContentText().contains("\n\n"));

        assertNotNull("Appendices should exist", appendices);
        assertTrue(appendices.getChildren().size() >= 5);
        assertEquals("Container node must not duplicate Appendix A text", "", appendices.getContentText());
        assertFalse(appendices.getChildren().get(0).getContentText().isEmpty());

        List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
        assertTrue("Expected > 25 leaf chapters in claude.epub", leaves.size() > 25);
        int estimatedPages = FileScanner.estimateEpubTotalPages(roots);
        assertTrue("Expected realistic page estimate > 50, got " + estimatedPages, estimatedPages > 50);

        // Resolving Part I container jumps directly to Chapter 1 leaf
        assertEquals(ch1.getChapterIndex(), ChapterTreeHelper.resolveNearestLeafIndex(roots, part1.getChapterIndex()));
    }
}
