package com.logininc.ereader.converter;

import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.util.AudnexusClient;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.M4BParser;
import com.logininc.ereader.util.MetadataJsonParser;
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

    @Test
    public void metadataJsonParserSupportsNestedAndAudiobookshelfFormats() {
        String nestedJson = "{\n"
                + "  \"title\": \"Il Nome della Rosa\",\n"
                + "  \"author\": \"Umberto Eco\",\n"
                + "  \"narrator\": \"Tommaso Ragno\",\n"
                + "  \"chapters\": [\n"
                + "    {\n"
                + "      \"title\": \"Primo Giorno\",\n"
                + "      \"start\": \"00:00:00\",\n"
                + "      \"children\": [\n"
                + "        { \"title\": \"Prima\", \"start\": \"00:00:00\", \"end\": \"00:10:00\" },\n"
                + "        { \"title\": \"Terza\", \"start\": \"00:10:00\", \"end\": \"00:25:00\" }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        MetadataJsonParser.ParsedMetadata parsed = MetadataJsonParser.parseJson(nestedJson);
        assertEquals("Il Nome della Rosa", parsed.title);
        assertEquals("Umberto Eco", parsed.author);
        assertEquals("Tommaso Ragno", parsed.narrator);

        List<Chapter> roots = MetadataJsonParser.buildAudioChapterTree(1, parsed.chapters, 1_500_000L, 0L);
        assertEquals(1, roots.size());
        assertEquals("Primo Giorno", roots.get(0).getChapterTitle());
        assertEquals(2, roots.get(0).getChildren().size());
        assertEquals(1_500_000L, roots.get(0).getAudioDurationMs());
        assertEquals(600_000L, roots.get(0).getChildren().get(0).getAudioDurationMs());
        assertEquals(900_000L, roots.get(0).getChildren().get(1).getAudioDurationMs());

        String flatAbsJson = "{\n"
                + "  \"title\": \"Test ABS\",\n"
                + "  \"authors\": [\"Autore A\", \"Autore B\"],\n"
                + "  \"narrators\": [\"Narratore X\"],\n"
                + "  \"chapters\": [\n"
                + "    { \"start\": 0.0, \"end\": 30.0, \"title\": \"Parte 1\" },\n"
                + "    { \"start\": 30.0, \"end\": 90.0, \"title\": \"  1.1 Introduzione\" },\n"
                + "    { \"start\": 90.0, \"end\": 150.0, \"title\": \"  1.2 Sviluppo\" }\n"
                + "  ]\n"
                + "}";

        MetadataJsonParser.ParsedMetadata absParsed = MetadataJsonParser.parseJson(flatAbsJson);
        assertEquals("Autore A, Autore B", absParsed.author);
        assertEquals("Narratore X", absParsed.narrator);
        List<Chapter> absRoots = MetadataJsonParser.buildAudioChapterTree(2, absParsed.chapters, 150_000L, 0L);
        assertEquals(1, absRoots.size());
        assertEquals(2, absRoots.get(0).getChildren().size());
        assertEquals("1.1 Introduzione", absRoots.get(0).getChildren().get(0).getChapterTitle());
        assertEquals(60_000L, absRoots.get(0).getChildren().get(0).getAudioDurationMs());
    }

    @Test
    public void audnexusClientParsesAsinCatalogBookAndChapters() {
        assertTrue(AudnexusClient.isAsin("B08G9PRS1K"));
        assertTrue(AudnexusClient.isAsin("b08g9prs1k"));
        assertEquals("it", AudnexusClient.normalizeRegion("IT"));
        assertEquals("us", AudnexusClient.normalizeRegion("invalid"));

        String catalogJson = "{ \"products\": ["
                + "{ \"asin\": \"B08G9PRS1K\", \"title\": \"Project Hail Mary\", "
                + "\"authors\": [{\"name\": \"Andy Weir\"}], \"narrators\": [{\"name\": \"Ray Porter\"}] }"
                + "] }";
        List<AudnexusClient.SearchCandidate> results = AudnexusClient.parseAudibleSearchResponse(catalogJson);
        assertEquals(1, results.size());
        assertEquals("B08G9PRS1K", results.get(0).asin);
        assertEquals("Project Hail Mary", results.get(0).title);
        assertEquals("Andy Weir", results.get(0).author);
        assertEquals("Ray Porter", results.get(0).narrator);

        String bookJson = "{ \"asin\": \"B08G9PRS1K\", \"title\": \"Project Hail Mary\", "
                + "\"authors\": [{\"name\": \"Andy Weir\"}], \"narrators\": [{\"name\": \"Ray Porter\"}], "
                + "\"image\": \"https://m.media-amazon.com/images/I/cover.jpg\", \"runtimeLengthMin\": 970 }";
        AudnexusClient.AudnexusBookDetail detail = AudnexusClient.parseAudnexusBookResponse("B08G9PRS1K", bookJson);
        assertEquals("Project Hail Mary", detail.title);
        assertEquals("Andy Weir", detail.author);
        assertEquals("Ray Porter", detail.narrator);
        assertEquals("https://m.media-amazon.com/images/I/cover.jpg", detail.imageUrl);
        assertEquals(970L * 60_000L, detail.runtimeLengthMs);

        String chaptersJson = "{ \"runtimeLengthMs\": 180000, \"chapters\": ["
                + "{ \"startOffsetMs\": 0, \"lengthMs\": 60000, \"title\": \"Opening Credits\" },"
                + "{ \"startOffsetMs\": 60000, \"lengthMs\": 120000, \"title\": \"Chapter 1\" }"
                + "] }";
        AudnexusClient.AudnexusChaptersDetail chDetail = AudnexusClient.parseAudnexusChaptersResponse(chaptersJson);
        assertEquals(180_000L, chDetail.runtimeLengthMs);
        assertEquals(2, chDetail.chapters.size());
        assertEquals("Opening Credits", chDetail.chapters.get(0).title);
        assertEquals(60_000L, chDetail.chapters.get(1).startMs);
        assertEquals(120_000L, chDetail.chapters.get(1).durationMs);
    }
}
