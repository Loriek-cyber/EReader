package com.logininc.ereader;

import android.net.Uri;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.service.SleepTimerController;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.NavigationFlowHelper;

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
}
