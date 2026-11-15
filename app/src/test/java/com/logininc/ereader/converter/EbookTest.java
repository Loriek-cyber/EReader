package com.logininc.ereader.converter;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.EReaderDbHelper;
import com.logininc.ereader.persistence.TitleDao;
import com.logininc.ereader.util.EbookParserV2;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.documentnode.epub4j.domain.TOCReference;

public class EbookTest {
    InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("claude.epub");

    @Test
    public void assertItLoads() {
        assertNotNull(is);
    }

    @Test
    public void assertItConvert() {
        EbookParserV2.ParsedBook book = null;
        try {
            book = EbookParserV2.parse(is);
            System.out.println(book.title);
            assertNotNull(book);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void assertChapterExist() {
        EbookParserV2.ParsedBook book = null;
        try {
            book = EbookParserV2.parse(is);

            for (EbookParserV2.Chapter chapter : book.chapters) {
                System.out.println(chapter.title);
            }

            assertNotNull(book);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testStructure() {
        try {
            var book = EbookParserV2.parse_non_project(is);
            System.out.println("Titolo: " + book.getTitle());
            System.out.println("Autori: " + book.getMetadata().getAuthors());
            System.out.println("\n--- INDICE (TOC) ---");

            var table = book.getTableOfContents();
            printTocRecursive(table.getTocReferences(), 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void toProjectBookPreservesClaudeEpubMetadataAndHierarchy() throws Exception {
        io.documentnode.epub4j.domain.Book epubBook;
        try (InputStream stream = openClaudeEpub()) {
            epubBook = EbookParserV2.parse_non_project(stream);
        }

        Book converted = EbookParserV2.toProjectBook(epubBook);

        assertNotNull(converted);
        assertEquals(epubBook.getTitle().trim(), converted.getTitle());
        assertEquals("Clear, Stephen", converted.getAuthor());
        assertNotNull(converted.getCoverBytes());
        assertTrue(converted.getCoverBytes().length > 0);
        assertEquals(Title.STATUS_TO_START, converted.getStatus());

        List<TOCReference> expectedRoots = epubBook.getTableOfContents().getTocReferences();
        List<Chapter> actualRoots = converted.getChapters();
        assertEquals(expectedRoots.size(), actualRoots.size());

        int[] expectedIndex = new int[]{1};
        assertTocMatchesHierarchy(expectedRoots, actualRoots, 0, expectedIndex);

        List<Chapter> flattened = converted.getFlattenedChapters();
        assertEquals(expectedIndex[0] - 1, flattened.size());
        assertEquals(flattened.size(), converted.getLenChapters());
        for (int i = 0; i < flattened.size(); i++) {
            Chapter node = flattened.get(i);
            assertEquals(i + 1, node.getChapterIndex());
            assertFalse(node.getHref().isEmpty());
            assertFalse(node.getRawHtml().isEmpty());
            assertFalse(node.getContentText().isEmpty());
        }

        // Spot-check Part I -> Chapter 1..4 nesting from claude.epub
        Chapter partOne = actualRoots.get(3);
        assertEquals("Part I: Foundations", partOne.getChapterTitle());
        assertEquals(0, partOne.getLevel());
        assertFalse(partOne.isSection());
        assertTrue(partOne.hasChildren());
        assertEquals(4, partOne.getChildren().size());
        assertEquals(1, partOne.getChildren().get(0).getLevel());
        assertTrue(partOne.getChildren().get(0).isSection());
    }

    @Test
    public void parseToBookHandlesNestedSectionsFragmentsAndMultipleAuthors() throws Exception {
        byte[] epubBytes = buildSyntheticEpub(true);
        Book book;
        try (InputStream stream = new ByteArrayInputStream(epubBytes)) {
            book = EbookParserV2.parseToBook(stream);
        }

        assertEquals("Synthetic Multi-Level Book", book.getTitle());
        assertEquals("Ada Lovelace, Alan Turing", book.getAuthor());
        assertEquals(1, book.getChapters().size());
        assertEquals(4, book.getLenChapters());

        Chapter root = book.getChapters().get(0);
        assertEquals("Chapter 1", root.getChapterTitle());
        assertEquals(1, root.getChapterIndex());
        assertEquals(0, root.getLevel());
        assertEquals(0, root.getSortOrder());
        assertEquals("ch1.xhtml", root.getHref());
        assertEquals("", root.getFragmentId());
        assertEquals(2, root.getChildren().size());

        Chapter sec1 = root.getChildren().get(0);
        assertEquals("Section 1.1", sec1.getChapterTitle());
        assertEquals(2, sec1.getChapterIndex());
        assertEquals(1, sec1.getLevel());
        assertEquals(0, sec1.getSortOrder());
        assertEquals("ch1.xhtml", sec1.getHref());
        assertEquals("sec1", sec1.getFragmentId());
        assertEquals("ch1.xhtml#sec1", sec1.getFullHref());
        assertTrue(sec1.getContentText().contains("Alpha section text"));
        assertFalse(sec1.getContentText().contains("Beta section text"));
        assertEquals(1, sec1.getChildren().size());

        Chapter sub1 = sec1.getChildren().get(0);
        assertEquals("Subsection 1.1.1", sub1.getChapterTitle());
        assertEquals(3, sub1.getChapterIndex());
        assertEquals(2, sub1.getLevel());
        assertEquals(0, sub1.getSortOrder());
        assertEquals("sub1", sub1.getFragmentId());
        assertTrue(sub1.getContentText().contains("Nested subsection detail"));
        assertFalse(sub1.getContentText().contains("Beta section text"));

        Chapter sec2 = root.getChildren().get(1);
        assertEquals("Section 1.2", sec2.getChapterTitle());
        assertEquals(4, sec2.getChapterIndex());
        assertEquals(1, sec2.getLevel());
        assertEquals(1, sec2.getSortOrder());
        assertEquals("sec2", sec2.getFragmentId());
        assertTrue(sec2.getContentText().contains("Beta section text"));
        assertFalse(sec2.getContentText().contains("Alpha section text"));
    }

    @Test
    public void parseToBookFallsBackToSpineWhenTocIsEmpty() throws Exception {
        byte[] epubBytes = buildSyntheticEpub(false);
        Book book;
        try (InputStream stream = new ByteArrayInputStream(epubBytes)) {
            book = EbookParserV2.parseToBook(stream);
        }

        assertEquals(1, book.getChapters().size());
        assertEquals(1, book.getLenChapters());
        Chapter ch = book.getChapters().get(0);
        assertEquals("Chapter One Heading", ch.getChapterTitle());
        assertEquals("ch1.xhtml", ch.getHref());
        assertEquals("", ch.getFragmentId());
        assertTrue(ch.getContentText().contains("Alpha section text"));
        assertTrue(ch.getContentText().contains("Beta section text"));
    }

    @Test
    public void saveBookPersistsHierarchyAndUpdatesExistingIdCleanly() throws Exception {
        Book book;
        try (InputStream stream = openClaudeEpub()) {
            book = EbookParserV2.parseToBook(stream);
        }

        InMemoryBookDao dao = new InMemoryBookDao();
        long savedId = EbookParserV2.saveBook(null, book, dao);

        assertTrue(savedId > 0);
        assertEquals(savedId, book.getId());

        Book reloaded = dao.getBookWithChapters((int) savedId);
        assertNotNull(reloaded);
        assertEquals(book.getTitle(), reloaded.getTitle());
        assertEquals(book.getAuthor(), reloaded.getAuthor());
        assertEquals(book.getLenChapters(), reloaded.getLenChapters());
        assertEquals(book.getChapters().size(), reloaded.getChapters().size());

        Chapter reloadedPartOne = reloaded.getChapters().get(3);
        assertTrue(reloadedPartOne.getId() > 0);
        assertEquals(0, reloadedPartOne.getParentChapterId());
        assertEquals(4, reloadedPartOne.getChildren().size());

        Chapter reloadedCh1 = reloadedPartOne.getChildren().get(0);
        assertTrue(reloadedCh1.getId() > 0);
        assertEquals(reloadedPartOne.getId(), reloadedCh1.getParentChapterId());
        assertEquals(1, reloadedCh1.getLevel());
        assertFalse(reloadedCh1.getRawHtml().isEmpty());
        assertFalse(reloadedCh1.getContentText().isEmpty());

        // Re-saving an already persisted book updates in-place without duplicating rows
        long resavedId = EbookParserV2.saveBook(null, book, dao);
        assertEquals(savedId, resavedId);
        assertEquals(1, dao.storedBooksCount());
    }

    private InputStream openClaudeEpub() {
        return Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("claude.epub"),
                "claude.epub test resource must exist"
        );
    }

    private void assertTocMatchesHierarchy(
            List<TOCReference> expectedRefs,
            List<Chapter> actualChapters,
            int expectedLevel,
            int[] expectedIndex
    ) {
        assertEquals(expectedRefs.size(), actualChapters.size());
        for (int i = 0; i < expectedRefs.size(); i++) {
            TOCReference ref = expectedRefs.get(i);
            Chapter ch = actualChapters.get(i);

            assertEquals(expectedIndex[0]++, ch.getChapterIndex());
            assertEquals(expectedLevel, ch.getLevel());
            assertEquals(i, ch.getSortOrder());
            assertEquals(ref.getTitle().trim(), ch.getChapterTitle());

            String expectedHref = ref.getResource() != null ? ref.getResource().getHref() : "";
            assertEquals(expectedHref, ch.getHref());
            String expectedFrag = ref.getFragmentId() != null ? ref.getFragmentId().trim() : "";
            assertEquals(expectedFrag, ch.getFragmentId());

            List<TOCReference> childRefs = ref.getChildren();
            if (childRefs != null && !childRefs.isEmpty()) {
                assertTocMatchesHierarchy(childRefs, ch.getChildren(), expectedLevel + 1, expectedIndex);
            } else {
                assertTrue(ch.getChildren().isEmpty());
            }
        }
    }

    private byte[] buildSyntheticEpub(boolean includeToc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            writeZipEntry(zos, "mimetype", "application/epub+zip");
            writeZipEntry(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                            + "  <rootfiles>\n"
                            + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
                            + "  </rootfiles>\n"
                            + "</container>");
            writeZipEntry(zos, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"2.0\">\n"
                            + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n"
                            + "    <dc:title>Synthetic Multi-Level Book</dc:title>\n"
                            + "    <dc:creator opf:file-as=\"Lovelace, Ada\">Ada Lovelace</dc:creator>\n"
                            + "    <dc:creator opf:file-as=\"Turing, Alan\">Alan Turing</dc:creator>\n"
                            + "    <dc:language>en</dc:language>\n"
                            + "    <dc:identifier id=\"BookId\">urn:uuid:12345</dc:identifier>\n"
                            + "  </metadata>\n"
                            + "  <manifest>\n"
                            + "    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n"
                            + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                            + "  </manifest>\n"
                            + "  <spine toc=\"ncx\">\n"
                            + "    <itemref idref=\"ch1\"/>\n"
                            + "  </spine>\n"
                            + "</package>");

            String navMap = includeToc
                    ? "    <navPoint id=\"np-1\" playOrder=\"1\">\n"
                    + "      <navLabel><text>Chapter 1</text></navLabel>\n"
                    + "      <content src=\"ch1.xhtml\"/>\n"
                    + "      <navPoint id=\"np-2\" playOrder=\"2\">\n"
                    + "        <navLabel><text>Section 1.1</text></navLabel>\n"
                    + "        <content src=\"ch1.xhtml#sec1\"/>\n"
                    + "        <navPoint id=\"np-3\" playOrder=\"3\">\n"
                    + "          <navLabel><text>Subsection 1.1.1</text></navLabel>\n"
                    + "          <content src=\"ch1.xhtml#sub1\"/>\n"
                    + "        </navPoint>\n"
                    + "      </navPoint>\n"
                    + "      <navPoint id=\"np-4\" playOrder=\"4\">\n"
                    + "        <navLabel><text>Section 1.2</text></navLabel>\n"
                    + "        <content src=\"ch1.xhtml#sec2\"/>\n"
                    + "      </navPoint>\n"
                    + "    </navPoint>\n"
                    : "";

            writeZipEntry(zos, "OEBPS/toc.ncx",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                            + "  <head><meta name=\"dtb:uid\" content=\"urn:uuid:12345\"/></head>\n"
                            + "  <docTitle><text>Synthetic Multi-Level Book</text></docTitle>\n"
                            + "  <navMap>\n"
                            + navMap
                            + "  </navMap>\n"
                            + "</ncx>");

            writeZipEntry(zos, "OEBPS/ch1.xhtml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                            + "<head><title>Chapter One</title></head>\n"
                            + "<body>\n"
                            + "  <h1>Chapter One Heading</h1>\n"
                            + "  <p>Chapter intro text.</p>\n"
                            + "  <h2 id=\"sec1\">Section 1.1</h2>\n"
                            + "  <p>Alpha section text.</p>\n"
                            + "  <h3 id=\"sub1\">Subsection 1.1.1</h3>\n"
                            + "  <p>Nested subsection detail.</p>\n"
                            + "  <h2 id=\"sec2\">Section 1.2</h2>\n"
                            + "  <p>Beta section text.</p>\n"
                            + "</body>\n"
                            + "</html>");
        }
        return baos.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void printTocRecursive(List<TOCReference> references, int level) {
        if (references == null || references.isEmpty()) {
            return;
        }

        String indent = "  ".repeat(level); // 2 spazi per ogni livello di profondità

        for (TOCReference ref : references) {
            String title = ref.getTitle();
            String href = (ref.getResource() != null) ? ref.getResource().getHref() : "nessun file";
            String fragment = (ref.getFragmentId() != null && !ref.getFragmentId().isEmpty())
                    ? "#" + ref.getFragmentId()
                    : "";

            System.out.println(indent + "├─ " + title + "  [" + href + fragment + "]");

            // Chiamata ricorsiva per stampare section / subsection figli
            printTocRecursive(ref.getChildren(), level + 1);
        }
    }

    /**
     * In-memory {@link TitleDao} test double simulating atomic book + hierarchical chapter tree
     * persistence and reconstruction without requiring Android's native SQLite driver.
     */
    private static final class InMemoryBookDao extends TitleDao {
        private final Map<Integer, Book> books = new HashMap<>();
        private final Map<Integer, List<Chapter>> flatRowsByBookId = new HashMap<>();
        private int nextBookId = 1;
        private int nextChapterId = 1;

        InMemoryBookDao() {
            super((EReaderDbHelper) null);
        }

        @Override
        public long saveBookWithChapters(@NonNull Book book) {
            List<Chapter> flattened = book.getFlattenedChapters();
            if (!flattened.isEmpty()) {
                book.setLenChapters(flattened.size());
            }
            if (book.getId() <= 0) {
                book.setId(nextBookId++);
            }

            Book storedCopy = new Book(book.getId(), book.getTitle(), book.getLenChapters(), book.getContentUri());
            storedCopy.setAuthor(book.getAuthor());
            storedCopy.setCurrentPage(book.getCurrentPage());
            storedCopy.setTotalPages(book.getTotalPages());
            storedCopy.setStatus(book.getStatus());
            books.put(book.getId(), storedCopy);

            List<Chapter> persistedRows = new ArrayList<>();
            assignIdsRecursive(book.getId(), 0, 0, book.getChapters(), persistedRows);
            flatRowsByBookId.put(book.getId(), persistedRows);
            return book.getId();
        }

        private void assignIdsRecursive(
                int titleId,
                int parentChapterId,
                int depth,
                List<Chapter> nodes,
                List<Chapter> outRows
        ) {
            for (int i = 0; i < nodes.size(); i++) {
                Chapter node = nodes.get(i);
                int id = nextChapterId++;
                node.setId(id);
                node.setTitleId(titleId);
                node.setParentChapterId(parentChapterId);
                node.setLevel(parentChapterId > 0 ? depth : Math.max(depth, node.getLevel()));
                node.setSortOrder(i);

                Chapter rowCopy = new Chapter(
                        id,
                        titleId,
                        parentChapterId,
                        node.getChapterIndex(),
                        node.getLevel(),
                        node.getSortOrder(),
                        node.getChapterTitle(),
                        node.getSubtitle(),
                        node.getHref(),
                        node.getFragmentId(),
                        node.getPageOrDurationLabel(),
                        node.getContentText(),
                        node.getRawHtml(),
                        node.getAudioStartMs(),
                        node.getAudioDurationMs(),
                        node.getProgressPercent(),
                        node.isCompleted()
                );
                outRows.add(rowCopy);
                if (node.hasChildren()) {
                    assignIdsRecursive(titleId, id, node.getLevel() + 1, node.getChildren(), outRows);
                }
            }
        }

        @Nullable
        @Override
        public Book getBookWithChapters(int bookId) {
            Book stored = books.get(bookId);
            if (stored == null) {
                return null;
            }
            Book copy = new Book(stored.getId(), stored.getTitle(), stored.getLenChapters(), stored.getContentUri());
            copy.setAuthor(stored.getAuthor());
            copy.setCurrentPage(stored.getCurrentPage());
            copy.setTotalPages(stored.getTotalPages());
            copy.setStatus(stored.getStatus());

            List<Chapter> rows = flatRowsByBookId.getOrDefault(bookId, List.of());
            Map<Integer, Chapter> byId = new HashMap<>();
            List<Chapter> cloned = new ArrayList<>(rows.size());
            for (Chapter r : rows) {
                Chapter c = new Chapter(
                        r.getId(), r.getTitleId(), r.getParentChapterId(), r.getChapterIndex(),
                        r.getLevel(), r.getSortOrder(), r.getChapterTitle(), r.getSubtitle(),
                        r.getHref(), r.getFragmentId(), r.getPageOrDurationLabel(),
                        r.getContentText(), r.getRawHtml(), r.getAudioStartMs(),
                        r.getAudioDurationMs(), r.getProgressPercent(), r.isCompleted()
                );
                byId.put(c.getId(), c);
                cloned.add(c);
            }
            List<Chapter> roots = new ArrayList<>();
            for (Chapter c : cloned) {
                Chapter parent = c.getParentChapterId() > 0 ? byId.get(c.getParentChapterId()) : null;
                if (parent != null) {
                    parent.addChild(c);
                } else {
                    roots.add(c);
                }
            }
            copy.setChapters(roots);
            return copy;
        }

        @Nullable
        @Override
        public Title findByContentUri(@NonNull Uri uri) {
            for (Book b : books.values()) {
                if (uri.equals(b.getContentUri())) {
                    return b;
                }
            }
            return null;
        }

        int storedBooksCount() {
            return books.size();
        }
    }
}
