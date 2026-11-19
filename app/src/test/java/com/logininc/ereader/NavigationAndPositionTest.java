package com.logininc.ereader;

import android.net.Uri;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.service.SleepTimerController;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.NavigationFlowHelper;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NavigationAndPositionTest {

    @Test
    public void multiChapterToStartRequiresChapterSelector() {
        Book book = new Book(1, "Test Book", 5, Uri.EMPTY);
        book.setStatus(Title.STATUS_TO_START);
        assertTrue(book.requiresChapterSelector());
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(book));

        AudioBook audioBook = new AudioBook(2, "Test Audio", 10, Uri.EMPTY);
        audioBook.setStatus(Title.STATUS_TO_START);
        assertTrue(audioBook.requiresChapterSelector());
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(audioBook));
    }

    @Test
    public void singleChapterToStartDoesNotRequireChapterSelector() {
        Book book = new Book(1, "Short Story", 1, Uri.EMPTY);
        book.setStatus(Title.STATUS_TO_START);
        assertFalse(book.requiresChapterSelector());
        assertFalse(NavigationFlowHelper.shouldOpenChapterSelector(book));
    }

    @Test
    public void inProgressTitleResumesDirectlyWithoutChapterSelector() {
        Book book = new Book(1, "Test Book", 12, Uri.EMPTY);
        book.setStatus(Title.STATUS_IN_PROGRESS);
        book.setCurrentChapter(3);
        book.setScrollY(1450);
        assertFalse(book.requiresChapterSelector());
        assertFalse(NavigationFlowHelper.shouldOpenChapterSelector(book));

        AudioBook audioBook = new AudioBook(2, "Test Audio", 8, Uri.EMPTY);
        audioBook.setStatus(Title.STATUS_IN_PROGRESS);
        audioBook.setCurrentChapter(4);
        audioBook.setCurrentPositionMs(95_000L);
        assertFalse(audioBook.requiresChapterSelector());
        assertFalse(NavigationFlowHelper.shouldOpenChapterSelector(audioBook));
    }

    @Test
    public void completedTitleDoesNotForceChapterSelector() {
        Book book = new Book(1, "Done Book", 6, Uri.EMPTY);
        book.setStatus(Title.STATUS_COMPLETED);
        assertFalse(book.requiresChapterSelector());
    }

    @Test
    public void bookAndAudioBookPositionBoundsArePreserved() {
        Book book = new Book(1, "Test Book", 5, Uri.EMPTY);
        book.setScrollY(1200);
        book.setScrollMaxHeight(4800);
        book.setViewportHeight(800);
        book.setCharOffset(345);
        book.setScrollPermille(3000);
        assertEquals(1200, book.getScrollY());
        assertEquals(4800, book.getScrollMaxHeight());
        assertEquals(800, book.getViewportHeight());
        assertEquals(345, book.getCharOffset());
        assertEquals(3000, book.getScrollPermille());

        AudioBook audioBook = new AudioBook(2, "Test Audio", 5, Uri.EMPTY);
        audioBook.setCurrentPositionMs(123_456L);
        audioBook.setTotalDurationMs(600_000L);
        assertEquals(123_456L, audioBook.getCurrentPositionMs());
        assertEquals(600_000L, audioBook.getTotalDurationMs());
    }

    @Test
    public void extractReadableTextPreservesParagraphBreaks() {
        String html = "<html><body><h1>Capitolo 1</h1><p>Primo paragrafo.</p><p>Secondo paragrafo.</p></body></html>";
        String extracted = FileScanner.extractReadableText(html, null);
        assertTrue(extracted.contains("Capitolo 1"));
        assertTrue(extracted.contains("Primo paragrafo."));
        assertTrue(extracted.contains("Secondo paragrafo."));
        assertTrue("Expected paragraph breaks in extracted text", extracted.contains("\n\n"));
    }

    @Test
    public void extractReadableTextScopesToFragmentId() {
        String html = "<html><body>"
                + "<h2 id=\"s1\">Sezione 1</h2><p>Testo uno.</p>"
                + "<h2 id=\"s2\">Sezione 2</h2><p>Testo due.</p>"
                + "</body></html>";
        String sec1 = FileScanner.extractReadableText(html, "s1");
        String sec2 = FileScanner.extractReadableText(html, "s2");
        assertTrue(sec1.contains("Sezione 1"));
        assertTrue(sec1.contains("Testo uno."));
        assertFalse(sec1.contains("Testo due."));
        assertTrue(sec2.contains("Sezione 2"));
        assertTrue(sec2.contains("Testo due."));
        assertFalse(sec2.contains("Testo uno."));
    }

    @Test
    public void sleepTimerOvernightWindowWorks() {
        // 22:30 (1350) -> 07:00 (420)
        assertTrue(SleepTimerController.isWithinNightWindow(1400, 1350, 420));
        assertTrue(SleepTimerController.isWithinNightWindow(120, 1350, 420));
        assertFalse(SleepTimerController.isWithinNightWindow(600, 1350, 420));
        assertTrue(SleepTimerController.shouldScheduleTimer(false, 600, 1350, 420, true));
        assertFalse(SleepTimerController.shouldScheduleTimer(false, 1400, 1350, 420, false));
    }

    @Test
    public void audioProgressPercentNeverSticksAtZeroOnceStarted() {
        assertEquals(0, PlaybackSessionManager.computeProgressPercent(0L, 36_000_000L));
        assertEquals(1, PlaybackSessionManager.computeProgressPercent(15_000L, 36_000_000L));
        assertEquals(50, PlaybackSessionManager.computeProgressPercent(18_000_000L, 36_000_000L));
        assertEquals(99, PlaybackSessionManager.computeProgressPercent(35_999_000L, 36_000_000L));
        assertEquals(100, PlaybackSessionManager.computeProgressPercent(36_000_000L, 36_000_000L));
    }

    @Test
    public void chapterTreeLeafNavigationSkipsSectionContainers() {
        Chapter part1 = new Chapter(1, 1, 0, 1, 0, 0, "Parte 1", "", "", "", "", "", "", 0, 0, 0, false);
        Chapter sec11 = new Chapter(2, 1, 1, 2, 1, 0, "Sezione 1.1", "", "", "", "", "", "", 0, 0, 0, false);
        Chapter leaf111 = new Chapter(3, 1, 2, 3, 2, 0, "Foglia 1.1.1", "", "", "", "", "Testo A", "", 0, 0, 0, false);
        Chapter leaf112 = new Chapter(4, 1, 2, 4, 2, 1, "Foglia 1.1.2", "", "", "", "", "Testo B", "", 0, 0, 0, false);
        sec11.addChild(leaf111);
        sec11.addChild(leaf112);
        part1.addChild(sec11);

        Chapter part2 = new Chapter(5, 1, 0, 5, 0, 1, "Parte 2", "", "", "", "", "", "", 0, 0, 0, false);
        Chapter leaf21 = new Chapter(6, 1, 5, 6, 1, 0, "Foglia 2.1", "", "", "", "", "Testo C", "", 0, 0, 0, false);
        part2.addChild(leaf21);

        List<Chapter> roots = Arrays.asList(part1, part2);

        List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
        assertEquals(3, leaves.size());
        assertEquals(3, leaves.get(0).getChapterIndex());
        assertEquals(4, leaves.get(1).getChapterIndex());
        assertEquals(6, leaves.get(2).getChapterIndex());

        // Clicking/resolving a container descends to its first leaf
        assertEquals(3, ChapterTreeHelper.resolveNearestLeafIndex(roots, 1));
        assertEquals(3, ChapterTreeHelper.resolveNearestLeafIndex(roots, 2));
        assertEquals(6, ChapterTreeHelper.resolveNearestLeafIndex(roots, 5));

        // Next/Previous leaf skips container index 5 (Parte 2)
        assertEquals(4, ChapterTreeHelper.findNextLeafIndex(roots, 3));
        assertEquals(6, ChapterTreeHelper.findNextLeafIndex(roots, 4));
        assertEquals(-1, ChapterTreeHelper.findNextLeafIndex(roots, 6));

        assertEquals(4, ChapterTreeHelper.findPreviousLeafIndex(roots, 6));
        assertEquals(3, ChapterTreeHelper.findPreviousLeafIndex(roots, 4));
        assertEquals(-1, ChapterTreeHelper.findPreviousLeafIndex(roots, 3));
    }

    @Test
    public void attachHtmlSectionsExtractsNestedSectionsRecursively() {
        String html = "<html><body>"
                + "<section id=\"s1\"><h2>Sezione 1</h2>"
                + "  <section id=\"s1a\"><h3>Sottosezione 1A</h3><p>Contenuto 1A</p></section>"
                + "  <section id=\"s1b\"><h3>Sottosezione 1B</h3><p>Contenuto 1B</p></section>"
                + "</section>"
                + "<section id=\"s2\"><h2>Sezione 2</h2><p>Contenuto 2</p></section>"
                + "</body></html>";

        Chapter root = new Chapter(0, 0, 0, 1, 0, 0, "Capitolo 1", "", "ch1.xhtml", "", "", "", "", 0, 0, 0, false);
        int[] counter = {2};
        FileScanner.attachHtmlSectionsIfPresent(root, html, "ch1.xhtml", 0, counter);

        assertEquals(2, root.getChildren().size());
        Chapter s1 = root.getChildren().get(0);
        Chapter s2 = root.getChildren().get(1);
        assertEquals("Sezione 1", s1.getChapterTitle());
        assertEquals(2, s1.getChildren().size());
        assertEquals("Sottosezione 1A", s1.getChildren().get(0).getChapterTitle());
        assertTrue(s1.getChildren().get(0).getContentText().contains("Contenuto 1A"));
        assertEquals("Sottosezione 1B", s1.getChildren().get(1).getChapterTitle());
        assertEquals("Sezione 2", s2.getChapterTitle());
        assertTrue(s2.getContentText().contains("Contenuto 2"));
    }
}
