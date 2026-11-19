package com.logininc.ereader.converter;

import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.M4BParser;
import com.logininc.ereader.util.Mp4ChapterParser;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AudiobookM4bTest {

    @Test
    public void parsesAllChaptersSubchaptersAndTreeFromTestAudiobookM4b() throws Exception {
        URL resourceUrl = getClass().getClassLoader().getResource("test_audiobook.m4b");
        assertNotNull("test_audiobook.m4b must exist in test resources", resourceUrl);
        File file = new File(resourceUrl.toURI());
        assertTrue(file.exists());

        List<Mp4ChapterParser.ParsedChapter> parsed;
        try (FileInputStream fis = new FileInputStream(file);
             FileChannel channel = fis.getChannel()) {
            parsed = Mp4ChapterParser.parseChapters(channel);
        }

        assertEquals(12, parsed.size());
        assertEquals("Parte 1 - L'Inizio", parsed.get(0).title);
        assertEquals(0, parsed.get(0).level);
        assertEquals(0L, parsed.get(0).startMs);

        assertEquals("1.1 Prologo nella foresta", parsed.get(1).title);
        assertEquals(1, parsed.get(1).level);
        assertEquals(2000L, parsed.get(1).startMs);

        assertEquals("1.2 L'incontro inaspettato", parsed.get(2).title);
        assertEquals(1, parsed.get(2).level);
        assertEquals(4000L, parsed.get(2).startMs);

        assertEquals("1.2.1 Un dettaglio nascosto", parsed.get(3).title);
        assertEquals(2, parsed.get(3).level);
        assertEquals(6000L, parsed.get(3).startMs);

        assertEquals("Parte 2 - Il Viaggio", parsed.get(4).title);
        assertEquals(0, parsed.get(4).level);

        assertEquals("Parte 3 - L'Epilogo", parsed.get(8).title);
        assertEquals(0, parsed.get(8).level);

        assertEquals("Appendice - Note dell'autore", parsed.get(11).title);
        assertEquals(0, parsed.get(11).level);
        assertEquals(22000L, parsed.get(11).startMs);

        // Convert to M4bChapter and build hierarchical Chapter tree
        List<M4BParser.M4bChapter> flat = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            Mp4ChapterParser.ParsedChapter p = parsed.get(i);
            long dur = p.durationMs > 0L
                    ? p.durationMs
                    : (i + 1 < parsed.size() ? parsed.get(i + 1).startMs - p.startMs : 2000L);
            flat.add(new M4BParser.M4bChapter(i + 1, p.title, p.startMs, dur, p.level));
        }

        List<Chapter> roots = FileScanner.buildAudiobookChapterTree(1, flat, 0L);
        assertEquals(4, roots.size());

        // Parte 1 -> [1.1, 1.2 -> [1.2.1]]
        Chapter parte1 = roots.get(0);
        assertEquals("Parte 1 - L'Inizio", parte1.getChapterTitle());
        assertEquals(2, parte1.getChildren().size());
        assertEquals("1.1 Prologo nella foresta", parte1.getChildren().get(0).getChapterTitle());
        Chapter ch12 = parte1.getChildren().get(1);
        assertEquals("1.2 L'incontro inaspettato", ch12.getChapterTitle());
        assertEquals(1, ch12.getChildren().size());
        assertEquals("1.2.1 Un dettaglio nascosto", ch12.getChildren().get(0).getChapterTitle());
        assertEquals(8000L, parte1.getAudioDurationMs());

        // Parte 2 -> [2.1, 2.2, 2.3]
        assertEquals(3, roots.get(1).getChildren().size());
        // Parte 3 -> [3.1, 3.2]
        assertEquals(2, roots.get(2).getChildren().size());
        // Appendice -> leaf
        assertEquals(0, roots.get(3).getChildren().size());

        // Leaf navigation skips container sections: 1.1 (2) -> 1.2.1 (4) -> 2.1 (6) -> ... -> Appendice (12)
        List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(roots);
        assertEquals(8, leaves.size());
        assertEquals(2, leaves.get(0).getChapterIndex());
        assertEquals(4, leaves.get(1).getChapterIndex());
        assertEquals(6, leaves.get(2).getChapterIndex());
        assertEquals(12, leaves.get(7).getChapterIndex());
        assertEquals(6, ChapterTreeHelper.findNextLeafIndex(roots, 4));
    }
}
