package com.logininc.ereader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.service.SleepTimerController;
import com.logininc.ereader.util.EbookParser;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.M4bParser;
import com.logininc.ereader.util.NavigationFlowHelper;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests verifying the core EReader navigation flows, session switching rules,
 * nocturnal anti-sleep window evaluation, and file classification/formatting helpers.
 */
public class CoreFlowsUnitTest {

    @Test
    public void newBookOrConcludedChapterRequiresChapterSelector() {
        Book newBook = new Book(1, "I Promessi Sposi", 12, null);
        assertEquals(Title.STATUS_TO_START, newBook.getStatus());
        assertTrue(newBook.requiresChapterSelector());
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(newBook));

        Book concludedChapterBook = new Book(2, "1984", 10, null);
        concludedChapterBook.setStatus(Title.STATUS_IN_PROGRESS);
        concludedChapterBook.setCurrentChapter(3);
        concludedChapterBook.setCurrentChapterCompleted(true);
        assertTrue(concludedChapterBook.requiresChapterSelector());
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(concludedChapterBook));

        Book completedBook = new Book(3, "Dune", 20, null);
        completedBook.setStatus(Title.STATUS_COMPLETED);
        completedBook.setCurrentChapterCompleted(false);
        assertTrue(completedBook.requiresChapterSelector());
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(completedBook));
    }

    @Test
    public void inProgressBookWithUnfinishedChapterOpensReaderDirectly() {
        Book inProgress = new Book(4, "Il Nome della Rosa", 15, null);
        inProgress.setStatus(Title.STATUS_IN_PROGRESS);
        inProgress.setCurrentChapter(4);
        inProgress.setProgressPercent(35);
        inProgress.setCurrentChapterCompleted(false);

        assertFalse(inProgress.requiresChapterSelector());
        assertFalse(NavigationFlowHelper.shouldOpenChapterSelector(inProgress));
    }

    @Test
    public void audioBookRoutingFollowsSameChapterSelectorRules() {
        AudioBook newAudio = new AudioBook(10, "Project Hail Mary", 18, null);
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(newAudio));

        AudioBook inProgressAudio = new AudioBook(11, "Project Hail Mary", 18, null);
        inProgressAudio.setStatus(Title.STATUS_IN_PROGRESS);
        inProgressAudio.setCurrentChapter(5);
        inProgressAudio.setProgressPercent(40);
        inProgressAudio.setCurrentChapterCompleted(false);
        assertFalse(NavigationFlowHelper.shouldOpenChapterSelector(inProgressAudio));

        inProgressAudio.setCurrentChapterCompleted(true);
        assertTrue(NavigationFlowHelper.shouldOpenChapterSelector(inProgressAudio));
    }

    @Test
    public void sessionSwitchDetectsDifferentTitleOnly() {
        assertFalse(PlaybackSessionManager.isNewTitleSwitch(-1, 5));
        assertFalse(PlaybackSessionManager.isNewTitleSwitch(0, 5));
        assertFalse(PlaybackSessionManager.isNewTitleSwitch(5, 5));
        assertTrue(PlaybackSessionManager.isNewTitleSwitch(5, 8));
    }

    @Test
    public void nightWindowHandlesOvernightAndDaytimeRanges() {
        int start2230 = 22 * 60 + 30;
        int end0700 = 7 * 60;

        assertTrue(SleepTimerController.isWithinNightWindow(22 * 60 + 30, start2230, end0700));
        assertTrue(SleepTimerController.isWithinNightWindow(23 * 60 + 59, start2230, end0700));
        assertTrue(SleepTimerController.isWithinNightWindow(0, start2230, end0700));
        assertTrue(SleepTimerController.isWithinNightWindow(6 * 60 + 59, start2230, end0700));

        assertFalse(SleepTimerController.isWithinNightWindow(7 * 60, start2230, end0700));
        assertFalse(SleepTimerController.isWithinNightWindow(14 * 60, start2230, end0700));
        assertFalse(SleepTimerController.isWithinNightWindow(22 * 60 + 29, start2230, end0700));

        // Daytime range 09:00 -> 17:00
        int start0900 = 9 * 60;
        int end1700 = 17 * 60;
        assertTrue(SleepTimerController.isWithinNightWindow(12 * 60, start0900, end1700));
        assertFalse(SleepTimerController.isWithinNightWindow(18 * 60, start0900, end1700));
    }

    @Test
    public void shouldScheduleTimerRespectsManualArmAndNightSetting() {
        int start2230 = 22 * 60 + 30;
        int end0700 = 7 * 60;
        int noon = 12 * 60;
        int midnight = 0;

        assertFalse(SleepTimerController.shouldScheduleTimer(true, noon, start2230, end0700, false));
        assertTrue(SleepTimerController.shouldScheduleTimer(true, midnight, start2230, end0700, false));
        assertFalse(SleepTimerController.shouldScheduleTimer(false, midnight, start2230, end0700, false));
        assertTrue(SleepTimerController.shouldScheduleTimer(false, noon, start2230, end0700, true));
    }

    @Test
    public void fileScannerClassifiesSupportedExtensionsAndFormatsDuration() {
        assertTrue(FileScanner.isEbookFile("libro.epub"));
        assertTrue(FileScanner.isEbookFile("DOCUMENTO.PDF"));
        assertTrue(FileScanner.isEbookFile("appunti.txt"));
        assertFalse(FileScanner.isEbookFile("traccia.mp3"));

        assertTrue(FileScanner.isAudioFile("audiolibro.m4b"));
        assertTrue(FileScanner.isAudioFile("TRACK.MP3"));
        assertTrue(FileScanner.isAudioFile("voce.ogg"));
        assertFalse(FileScanner.isAudioFile("romanzo.epub"));

        assertEquals("00:00", FileScanner.formatDuration(0L));
        assertEquals("05:09", FileScanner.formatDuration(309_000L));
        assertEquals("1:02:03", FileScanner.formatDuration(3723_000L));
    }

    @Test
    public void m4bMimeTypesAreRecognizedCorrectly() {
        assertTrue(M4bParser.isM4bOrAudioMime("audio/mp4"));
        assertTrue(M4bParser.isM4bOrAudioMime("audio/x-m4b"));
        assertTrue(M4bParser.isM4bOrAudioMime("audio/m4b"));
        assertTrue(M4bParser.isM4bOrAudioMime("video/mp4"));
        assertTrue(M4bParser.isM4bOrAudioMime("application/mp4"));
        assertFalse(M4bParser.isM4bOrAudioMime("application/pdf"));
        assertFalse(M4bParser.isM4bOrAudioMime(null));
    }

    @Test
    public void m4bParserExtractsNeroChaptersAndItunesMetadata() throws IOException {
        byte[] fakeJpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        byte[] ilst = box("ilst",
                ilstTextAtom(0xA96E616D, "Capitolo Unico"),        // ©nam
                ilstTextAtom(0xA9616C62, "Dune"),                  // ©alb
                ilstTextAtom(0xA9415254, "Frank Herbert"),         // ©ART
                ilstTextAtom(0xA96E7274, "Alessandro Rossi"),      // ©nrt
                ilstDataAtom(0x636F7672, 13, fakeJpg)              // covr
        );
        byte[] meta = box("meta", new byte[4], ilst);

        // Nero chpl v1: version(1)=1, flags(3)=0, reserved(4)=0, count(1)=2
        ByteArrayOutputStream chplPayload = new ByteArrayOutputStream();
        chplPayload.write(1);
        chplPayload.write(new byte[3]);
        chplPayload.write(new byte[4]);
        chplPayload.write(2);
        // Entry 1: start 0ms
        writeI64(chplPayload, 0L);
        byte[] ch1Name = "Atto I: Caladan".getBytes(StandardCharsets.UTF_8);
        chplPayload.write(ch1Name.length);
        chplPayload.write(ch1Name);
        // Entry 2: start 60_000ms -> 600_000_000 (100ns units)
        writeI64(chplPayload, 60_000L * 10_000L);
        byte[] ch2Name = "Atto II: Arrakis".getBytes(StandardCharsets.UTF_8);
        chplPayload.write(ch2Name.length);
        chplPayload.write(ch2Name);
        byte[] chpl = box("chpl", chplPayload.toByteArray());

        // mvhd v0: timescale 1000, duration 180_000ms
        byte[] mvhd = buildMvhdV0(1000, 180_000);
        byte[] udta = box("udta", meta, chpl);
        byte[] moov = box("moov", mvhd, udta);

        byte[] ftyp = box("ftyp", "M4B \0\0\0\0M4B ".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", new byte[1024]);
        byte[] fullFile = concat(ftyp, mdat, moov);

        M4bParser.M4bMetadata parsed = M4bParser.parse(new M4bParser.ByteArrayDataSource(fullFile));
        assertEquals("Dune", parsed.album);
        assertEquals("Capitolo Unico", parsed.title);
        assertEquals("Frank Herbert", parsed.author);
        assertEquals("Alessandro Rossi", parsed.narrator);
        assertEquals(180_000L, parsed.durationMs);
        assertNotNull(parsed.coverBytes);
        assertArrayEquals(fakeJpg, parsed.coverBytes);
        assertEquals(2, parsed.chapters.size());

        assertEquals("Atto I: Caladan", parsed.chapters.get(0).title);
        assertEquals(0L, parsed.chapters.get(0).startMs);
        assertEquals(60_000L, parsed.chapters.get(0).durationMs);

        assertEquals("Atto II: Arrakis", parsed.chapters.get(1).title);
        assertEquals(60_000L, parsed.chapters.get(1).startMs);
        assertEquals(120_000L, parsed.chapters.get(1).durationMs);
    }

    @Test
    public void m4bParserExtractsQuickTimeChapterTrack() throws IOException {
        byte[] sample1 = qtTextSample("Prologo");
        byte[] sample2 = qtTextSample("Parte Prima");
        byte[] ftyp = box("ftyp", "M4B \0\0\0\0M4B ".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", sample1, sample2);
        long firstChunkOffset = ftyp.length + 8L; // payload start inside mdat

        // Track 1 (audio) with tref/chap referencing Track 2
        byte[] audioTkhd = buildTkhdV0(1);
        byte[] chap = box("chap", i32Bytes(2));
        byte[] tref = box("tref", chap);
        byte[] audioTrak = box("trak", audioTkhd, tref);

        // Track 2 (chapter text track)
        byte[] textTkhd = buildTkhdV0(2);
        byte[] mdhd = buildMdhdV0(1000);
        ByteArrayOutputStream hdlrPayload = new ByteArrayOutputStream();
        hdlrPayload.write(new byte[8]); // version+flags+predefined
        hdlrPayload.write("text".getBytes(StandardCharsets.ISO_8859_1));
        hdlrPayload.write(new byte[12]);
        byte[] hdlr = box("hdlr", hdlrPayload.toByteArray());

        // stts: 2 chapters (45s and 95s at timescale 1000)
        ByteArrayOutputStream sttsPayload = new ByteArrayOutputStream();
        sttsPayload.write(new byte[4]); // version/flags
        writeI32(sttsPayload, 2);       // entry count
        writeI32(sttsPayload, 1);       // count 1
        writeI32(sttsPayload, 45_000);  // 45s
        writeI32(sttsPayload, 1);       // count 1
        writeI32(sttsPayload, 95_000);  // 95s
        byte[] stts = box("stts", sttsPayload.toByteArray());

        // stsc: chunk 1 holds 2 samples
        ByteArrayOutputStream stscPayload = new ByteArrayOutputStream();
        stscPayload.write(new byte[4]);
        writeI32(stscPayload, 1); // 1 entry
        writeI32(stscPayload, 1); // firstChunk=1
        writeI32(stscPayload, 2); // samplesPerChunk=2
        writeI32(stscPayload, 1); // sdi
        byte[] stsc = box("stsc", stscPayload.toByteArray());

        // stsz: variable sizes
        ByteArrayOutputStream stszPayload = new ByteArrayOutputStream();
        stszPayload.write(new byte[4]);
        writeI32(stszPayload, 0); // non-uniform
        writeI32(stszPayload, 2); // 2 samples
        writeI32(stszPayload, sample1.length);
        writeI32(stszPayload, sample2.length);
        byte[] stsz = box("stsz", stszPayload.toByteArray());

        // stco: 1 chunk pointing directly to mdat payload
        ByteArrayOutputStream stcoPayload = new ByteArrayOutputStream();
        stcoPayload.write(new byte[4]);
        writeI32(stcoPayload, 1);
        writeI32(stcoPayload, (int) firstChunkOffset);
        byte[] stco = box("stco", stcoPayload.toByteArray());

        byte[] stbl = box("stbl", stts, stsc, stsz, stco);
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        byte[] textTrak = box("trak", textTkhd, mdia);

        byte[] mvhd = buildMvhdV0(1000, 140_000);
        byte[] moov = box("moov", mvhd, audioTrak, textTrak);
        byte[] fullFile = concat(ftyp, mdat, moov);

        M4bParser.M4bMetadata parsed = M4bParser.parse(new M4bParser.ByteArrayDataSource(fullFile));
        assertEquals(140_000L, parsed.durationMs);
        assertEquals(2, parsed.chapters.size());
        assertEquals("Prologo", parsed.chapters.get(0).title);
        assertEquals(0L, parsed.chapters.get(0).startMs);
        assertEquals(45_000L, parsed.chapters.get(0).durationMs);
        assertEquals("Parte Prima", parsed.chapters.get(1).title);
        assertEquals(45_000L, parsed.chapters.get(1).startMs);
        assertEquals(95_000L, parsed.chapters.get(1).durationMs);
    }

    @Test
    public void ebookParserExtractsRealTestFileEpubMetadataCoverAndChapters() throws IOException {
        File epubFile = findTestEpubFile();
        assertNotNull("Test EPUB file must exist in test_file/", epubFile);

        EbookParser.EbookMetadata parsed = EbookParser.parseEpubFile(epubFile);
        assertEquals(
                "Claude AI Bible: The Complete Guide to Mastering Claude, Claude Code, Claude Cowork, MCP, AI Agents, Automation & Advanced Prompting",
                parsed.title
        );
        assertEquals("Stephen Clear", parsed.author);
        assertNotNull(parsed.coverBytes);
        assertEquals(104717, parsed.coverBytes.length);
        assertEquals(452, parsed.totalPages);
        assertEquals(33, parsed.chapters.size());

        assertEquals("Disclaimer", parsed.chapters.get(0).getChapterTitle());
        assertEquals("How to Use This Book", parsed.chapters.get(1).getChapterTitle());
        assertEquals("The 90 Percent You Haven't Touched", parsed.chapters.get(2).getChapterTitle());
        assertEquals("Chapter 1: The Machine With a Conscience", parsed.chapters.get(3).getChapterTitle());
        assertEquals("About the Author", parsed.chapters.get(32).getChapterTitle());

        for (Chapter ch : parsed.chapters) {
            String text = ch.getContentText();
            assertFalse("Chapter text should not be empty: " + ch.getChapterTitle(), text.trim().isEmpty());
            assertFalse("Chapter text must not contain HTML tags: " + ch.getChapterTitle(),
                    text.contains("<p") || text.contains("</p>") || text.contains("<h1"));
        }
        assertTrue(parsed.chapters.get(3).getContentText().contains("Most software does exactly what it is told"));
    }

    @Test
    public void ebookParserExtractsPdfMetadataOutlineAndTextWithPdfBox() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Test PDF EBook");
            info.setAuthor("Ada Lovelace");
            doc.setDocumentInformation(info);

            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("First PDF chapter content");
                cs.endText();
            }

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Second PDF chapter content");
                cs.endText();
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem item1 = new PDOutlineItem();
            item1.setTitle("Introduzione PDF");
            item1.setDestination(page1);
            outline.addLast(item1);

            PDOutlineItem item2 = new PDOutlineItem();
            item2.setTitle("Capitolo Finale PDF");
            item2.setDestination(page2);
            outline.addLast(item2);

            doc.save(out);
            pdfBytes = out.toByteArray();
        }

        EbookParser.EbookMetadata parsed = EbookParser.parsePdfStream(
                null,
                new ByteArrayInputStream(pdfBytes),
                "fallback"
        );
        assertEquals("Test PDF EBook", parsed.title);
        assertEquals("Ada Lovelace", parsed.author);
        assertEquals(2, parsed.totalPages);
        assertEquals(2, parsed.chapters.size());

        assertEquals("Introduzione PDF", parsed.chapters.get(0).getChapterTitle());
        assertEquals(1L, parsed.chapters.get(0).getAudioStartMs());
        assertEquals(1L, parsed.chapters.get(0).getAudioDurationMs());
        assertTrue(parsed.chapters.get(0).getContentText().contains("First PDF chapter content"));

        assertEquals("Capitolo Finale PDF", parsed.chapters.get(1).getChapterTitle());
        assertEquals(2L, parsed.chapters.get(1).getAudioStartMs());
        assertEquals(2L, parsed.chapters.get(1).getAudioDurationMs());
        assertTrue(parsed.chapters.get(1).getContentText().contains("Second PDF chapter content"));
    }

    private static File findTestEpubFile() {
        for (String dirPath : new String[]{"test_file", "../test_file"}) {
            File dir = new File(dirPath);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().toLowerCase().endsWith(".epub")) {
                            return f;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] qtTextSample(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + utf8.length];
        out[0] = (byte) ((utf8.length >>> 8) & 0xFF);
        out[1] = (byte) (utf8.length & 0xFF);
        System.arraycopy(utf8, 0, out, 2, utf8.length);
        return out;
    }

    private static byte[] buildMvhdV0(int timescale, int duration) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[12]); // version(0) + flags(3) + creation(4) + mod(4)
        writeI32(out, timescale);
        writeI32(out, duration);
        out.write(new byte[80]);
        return box("mvhd", out.toByteArray());
    }

    private static byte[] buildTkhdV0(int trackId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[12]);
        writeI32(out, trackId);
        out.write(new byte[68]);
        return box("tkhd", out.toByteArray());
    }

    private static byte[] buildMdhdV0(int timescale) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[12]);
        writeI32(out, timescale);
        out.write(new byte[16]);
        return box("mdhd", out.toByteArray());
    }

    private static byte[] ilstTextAtom(int fourCc, String text) throws IOException {
        return ilstDataAtom(fourCc, 1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] ilstDataAtom(int fourCc, int dataType, byte[] payload) throws IOException {
        ByteArrayOutputStream dataPayload = new ByteArrayOutputStream();
        writeI32(dataPayload, dataType);
        writeI32(dataPayload, 0);
        dataPayload.write(payload);
        byte[] dataBox = box("data", dataPayload.toByteArray());
        return boxInt(fourCc, dataBox);
    }

    private static byte[] box(String type, byte[]... parts) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        int fourCc = ((typeBytes[0] & 0xFF) << 24)
                | ((typeBytes[1] & 0xFF) << 16)
                | ((typeBytes[2] & 0xFF) << 8)
                | (typeBytes[3] & 0xFF);
        return boxInt(fourCc, parts);
    }

    private static byte[] boxInt(int fourCc, byte[]... parts) throws IOException {
        int payloadLen = 0;
        for (byte[] p : parts) {
            payloadLen += p.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeI32(out, 8 + payloadLen);
        writeI32(out, fourCc);
        for (byte[] p : parts) {
            out.write(p);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p);
        }
        return out.toByteArray();
    }

    private static byte[] i32Bytes(int v) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeI32(out, v);
        return out.toByteArray();
    }

    private static void writeI32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeI64(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((v >>> shift) & 0xFF));
        }
    }
}
