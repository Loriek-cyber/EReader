package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.TitleDao;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.domain.Spine;
import io.documentnode.epub4j.domain.TOCReference;
import io.documentnode.epub4j.domain.TableOfContents;
import io.documentnode.epub4j.epub.EpubReader;

/**
 * Parses EPUB documents via epub4j and converts them into the project's
 * {@link com.logininc.ereader.model.Book} and hierarchical
 * {@link com.logininc.ereader.model.Chapter} models, with SQLite persistence helpers.
 */
public class EbookParserV2 {

    private static final Pattern HEADING_TAG_PATTERN = Pattern.compile("^h([1-6])$");

    public static class Chapter {
        public final int index;
        public final String title;
        public final String href;
        public final String rawHtml;
        public final String plainText;

        Chapter(int index, String title, String href, String rawHtml, String plainText) {
            this.index = index;
            this.title = title;
            this.href = href;
            this.rawHtml = rawHtml;
            this.plainText = plainText;
        }
    }

    public static class ParsedBook {
        public final String title;
        public final String author;
        public final byte[] coverBytes; // può essere null
        public final List<Chapter> chapters;

        ParsedBook(String title, String author, byte[] coverBytes, List<Chapter> chapters) {
            this.title = title;
            this.author = author;
            this.coverBytes = coverBytes;
            this.chapters = chapters;
        }
    }

    private static final class ParsedResource {
        static final ParsedResource EMPTY = new ParsedResource("", Jsoup.parse(""));
        final String rawHtml;
        final Document document;
        final String bodyText;

        ParsedResource(@NonNull String rawHtml, @NonNull Document document) {
            this.rawHtml = rawHtml;
            this.document = document;
            Element body = document.body();
            this.bodyText = body != null ? body.text() : document.text();
        }
    }

    private static final class ExtractedContent {
        final String rawHtml;
        final String plainText;

        ExtractedContent(@NonNull String rawHtml, @NonNull String plainText) {
            this.rawHtml = rawHtml;
            this.plainText = plainText;
        }
    }

    public static ParsedBook parse(Context context, Uri epubUri) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(epubUri)) {
            if (is == null) {
                throw new IllegalArgumentException("Impossibile aprire lo stream per URI: " + epubUri);
            }
            Book book = new EpubReader().readEpub(is);
            return buildLegacyParsedBook(book);
        }
    }

    public static ParsedBook parse(InputStream is) throws Exception {
        try {
            if (is == null) {
                throw new IllegalArgumentException("Problema con il sistema");
            }
            Book book = new EpubReader().readEpub(is);
            return buildLegacyParsedBook(book);
        } catch (IllegalArgumentException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Book parse_non_project(InputStream is) {
        try {
            if (is == null) {
                throw new IllegalArgumentException("Errore con le risorse");
            }
            return new EpubReader().readEpub(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses an EPUB stream and converts it into a project {@link com.logininc.ereader.model.Book}
     * with a hierarchical {@link com.logininc.ereader.model.Chapter} tree.
     */
    @NonNull
    public static com.logininc.ereader.model.Book parseToBook(@NonNull InputStream is) throws Exception {
        return parseToBook(is, null, null);
    }

    /**
     * Parses an EPUB stream and converts it into a project {@link com.logininc.ereader.model.Book}
     * bound to {@code contentUri}.
     */
    @NonNull
    public static com.logininc.ereader.model.Book parseToBook(
            @NonNull InputStream is,
            @Nullable Uri contentUri
    ) throws Exception {
        return parseToBook(is, contentUri, null);
    }

    /**
     * Parses an EPUB stream and converts it into a project {@link com.logininc.ereader.model.Book}
     * bound to {@code contentUri} with an optional fallback title.
     */
    @NonNull
    public static com.logininc.ereader.model.Book parseToBook(
            @NonNull InputStream is,
            @Nullable Uri contentUri,
            @Nullable String fallbackTitle
    ) throws Exception {
        Objects.requireNonNull(is, "InputStream must not be null");
        Book epubBook = new EpubReader().readEpub(is);
        return toProjectBook(epubBook, contentUri, fallbackTitle);
    }

    /**
     * Parses an EPUB document at {@code epubUri} into a project {@link com.logininc.ereader.model.Book}.
     */
    @NonNull
    public static com.logininc.ereader.model.Book parseToBook(
            @NonNull Context context,
            @NonNull Uri epubUri
    ) throws Exception {
        return parseToBook(context, epubUri, null);
    }

    /**
     * Parses an EPUB document at {@code epubUri} into a project {@link com.logininc.ereader.model.Book}
     * with an optional fallback title.
     */
    @NonNull
    public static com.logininc.ereader.model.Book parseToBook(
            @NonNull Context context,
            @NonNull Uri epubUri,
            @Nullable String fallbackTitle
    ) throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(epubUri, "epubUri must not be null");
        try (InputStream is = context.getContentResolver().openInputStream(epubUri)) {
            if (is == null) {
                throw new IllegalArgumentException("Impossibile aprire lo stream per URI: " + epubUri);
            }
            return parseToBook(is, epubUri, fallbackTitle);
        }
    }

    /**
     * Converts an epub4j {@link Book} into the project's {@link com.logininc.ereader.model.Book}
     * model, preserving the recursive TOC hierarchy (chapters, sections, subsections).
     */
    @NonNull
    public static com.logininc.ereader.model.Book toProjectBook(@NonNull Book epubBook) {
        return toProjectBook(epubBook, null, null);
    }

    /**
     * Converts an epub4j {@link Book} into the project's {@link com.logininc.ereader.model.Book}
     * model with the given document URI.
     */
    @NonNull
    public static com.logininc.ereader.model.Book toProjectBook(
            @NonNull Book epubBook,
            @Nullable Uri contentUri
    ) {
        return toProjectBook(epubBook, contentUri, null);
    }

    /**
     * Converts an epub4j {@link Book} into the project's {@link com.logininc.ereader.model.Book}
     * model, extracting metadata, cover bytes, and the full chapter/section/subsection tree.
     */
    @NonNull
    public static com.logininc.ereader.model.Book toProjectBook(
            @NonNull Book epubBook,
            @Nullable Uri contentUri,
            @Nullable String fallbackTitle
    ) {
        Objects.requireNonNull(epubBook, "epubBook must not be null");

        String title = extractBookTitle(epubBook, fallbackTitle);
        String author = extractBookAuthors(epubBook);
        byte[] coverBytes = extractCoverBytes(epubBook);

        Map<String, ParsedResource> resourceCache = new HashMap<>();
        List<com.logininc.ereader.model.Chapter> rootChapters = new ArrayList<>();

        TableOfContents toc = epubBook.getTableOfContents();
        List<TOCReference> tocRefs = toc != null ? toc.getTocReferences() : null;
        if (tocRefs != null && !tocRefs.isEmpty()) {
            Map<String, Set<String>> fragmentsByHref = new HashMap<>();
            collectFragmentIds(tocRefs, fragmentsByHref);
            int[] nextChapterIndex = new int[]{1};
            convertTocReferences(
                    epubBook,
                    tocRefs,
                    0,
                    rootChapters,
                    nextChapterIndex,
                    resourceCache,
                    fragmentsByHref
            );
        } else {
            buildChaptersFromSpine(epubBook, rootChapters, resourceCache);
        }

        com.logininc.ereader.model.Book projectBook = new com.logininc.ereader.model.Book(
                0,
                title,
                author,
                contentUri,
                coverBytes,
                rootChapters
        );
        int totalNodes = Math.max(1, projectBook.getFlattenedChapters().size());
        projectBook.setLenChapters(totalNodes);
        projectBook.setTotalPages(totalNodes);
        projectBook.setCurrentPage(1);
        projectBook.setStatus(Title.STATUS_TO_START);
        return projectBook;
    }

    /**
     * Persists a converted {@link com.logininc.ereader.model.Book} (cover image if available,
     * metadata, and hierarchical chapters/sections) into SQLite via {@link TitleDao}.
     *
     * @return the persisted book row ID, or {@code -1} if saving failed.
     */
    public static long saveBook(
            @Nullable Context context,
            @NonNull com.logininc.ereader.model.Book book,
            @NonNull TitleDao titleDao
    ) {
        Objects.requireNonNull(book, "book must not be null");
        Objects.requireNonNull(titleDao, "titleDao must not be null");

        Uri contentUri = book.getContentUri();
        if (book.getId() <= 0 && contentUri != null && !contentUri.equals(Uri.EMPTY)) {
            Title existing = titleDao.findByContentUri(contentUri);
            if (existing != null) {
                book.setId(existing.getId());
            }
        }

        if (context != null
                && book.getCoverUri() == null
                && book.getCoverBytes() != null
                && book.getCoverBytes().length > 0) {
            int hash = contentUri != null && !contentUri.equals(Uri.EMPTY)
                    ? contentUri.toString().hashCode()
                    : (book.getTitle() != null ? book.getTitle().hashCode() : 0);
            Uri coverUri = FileScanner.saveCoverBytes(context, hash, book.getCoverBytes());
            if (coverUri != null) {
                book.setCoverUri(coverUri);
            }
        }

        return titleDao.saveBookWithChapters(book);
    }

    /**
     * Convenience overload using a default {@link TitleDao} constructed from {@code context}.
     */
    public static long saveBook(
            @NonNull Context context,
            @NonNull com.logininc.ereader.model.Book book
    ) {
        Objects.requireNonNull(context, "context must not be null");
        return saveBook(context, book, new TitleDao(context));
    }

    /**
     * Parses an EPUB at {@code epubUri} and saves the resulting {@link com.logininc.ereader.model.Book}
     * and its hierarchical chapter/section tree into SQLite.
     */
    @Nullable
    public static com.logininc.ereader.model.Book parseAndSaveBook(
            @NonNull Context context,
            @NonNull Uri epubUri
    ) throws Exception {
        return parseAndSaveBook(context, epubUri, new TitleDao(context));
    }

    /**
     * Parses an EPUB at {@code epubUri} and persists it using the supplied {@link TitleDao}.
     */
    @Nullable
    public static com.logininc.ereader.model.Book parseAndSaveBook(
            @NonNull Context context,
            @NonNull Uri epubUri,
            @NonNull TitleDao titleDao
    ) throws Exception {
        com.logininc.ereader.model.Book book = parseToBook(context, epubUri);
        long savedId = saveBook(context, book, titleDao);
        return savedId > 0 ? book : null;
    }

    private static ParsedBook buildLegacyParsedBook(@NonNull Book book) throws IOException {
        Map<String, String> tocTitles = new HashMap<>();
        if (book.getTableOfContents() != null) {
            collectTocTitles(book.getTableOfContents().getTocReferences(), tocTitles);
        }

        List<Chapter> chapters = new ArrayList<>();
        Spine spine = book.getSpine();
        if (spine != null) {
            for (int i = 0; i < spine.size(); i++) {
                Resource res = spine.getResource(i);
                if (res == null) {
                    continue;
                }
                String rawHtml = new String(res.getData(), resolveCharset(res));
                Document doc = Jsoup.parse(rawHtml);
                String title = chapterTitle(res.getHref(), doc, tocTitles, chapters.size() + 1);
                String bodyText = doc.body() != null ? doc.body().text() : doc.text();
                chapters.add(new Chapter(chapters.size(), title, res.getHref(), rawHtml, bodyText));
            }
        }

        byte[] cover = extractCoverBytes(book);
        return new ParsedBook(bookTitle(book), bookAuthor(book), cover, chapters);
    }

    private static void convertTocReferences(
            @NonNull Book epubBook,
            @Nullable List<TOCReference> references,
            int level,
            @NonNull List<com.logininc.ereader.model.Chapter> targetList,
            @NonNull int[] nextChapterIndex,
            @NonNull Map<String, ParsedResource> resourceCache,
            @NonNull Map<String, Set<String>> fragmentsByHref
    ) {
        if (references == null || references.isEmpty()) {
            return;
        }

        for (TOCReference ref : references) {
            if (ref == null) {
                continue;
            }
            int chapterIndex = nextChapterIndex[0]++;
            int sortOrder = targetList.size();

            Resource res = resolveResource(epubBook, ref);
            String href = resolveHref(ref, res);
            String fragmentId = resolveFragmentId(ref);

            ParsedResource parsed = getParsedResource(res, href, resourceCache);
            ExtractedContent content = extractContentForNode(
                    parsed,
                    fragmentId,
                    fragmentsByHref.get(href)
            );
            String chapterTitle = resolveNodeTitle(ref, parsed, level, chapterIndex);

            com.logininc.ereader.model.Chapter chapterNode = new com.logininc.ereader.model.Chapter(
                    chapterIndex,
                    level,
                    sortOrder,
                    chapterTitle,
                    href,
                    fragmentId,
                    content.plainText,
                    content.rawHtml
            );

            List<TOCReference> childRefs = ref.getChildren();
            if (childRefs != null && !childRefs.isEmpty()) {
                List<com.logininc.ereader.model.Chapter> childNodes = new ArrayList<>(childRefs.size());
                convertTocReferences(
                        epubBook,
                        childRefs,
                        level + 1,
                        childNodes,
                        nextChapterIndex,
                        resourceCache,
                        fragmentsByHref
                );
                chapterNode.setChildren(childNodes);
            }

            targetList.add(chapterNode);
        }
    }

    private static void buildChaptersFromSpine(
            @NonNull Book epubBook,
            @NonNull List<com.logininc.ereader.model.Chapter> rootChapters,
            @NonNull Map<String, ParsedResource> resourceCache
    ) {
        Spine spine = epubBook.getSpine();
        if (spine == null) {
            return;
        }
        int nextIndex = 1;
        for (int i = 0; i < spine.size(); i++) {
            Resource res = spine.getResource(i);
            if (res == null) {
                continue;
            }
            String href = stripFragment(res.getHref());
            ParsedResource parsed = getParsedResource(res, href, resourceCache);
            String title = extractDocumentTitle(parsed, "Capitolo " + nextIndex);
            com.logininc.ereader.model.Chapter node = new com.logininc.ereader.model.Chapter(
                    nextIndex,
                    0,
                    rootChapters.size(),
                    title,
                    href,
                    "",
                    parsed.bodyText,
                    parsed.rawHtml
            );
            rootChapters.add(node);
            nextIndex++;
        }
    }

    private static void collectFragmentIds(
            @Nullable List<TOCReference> refs,
            @NonNull Map<String, Set<String>> fragmentsByHref
    ) {
        if (refs == null) {
            return;
        }
        for (TOCReference ref : refs) {
            if (ref == null) {
                continue;
            }
            String href = resolveHref(ref, ref.getResource());
            String fragmentId = resolveFragmentId(ref);
            if (!href.isEmpty() && !fragmentId.isEmpty()) {
                fragmentsByHref
                        .computeIfAbsent(href, k -> new LinkedHashSet<>())
                        .add(fragmentId);
            }
            collectFragmentIds(ref.getChildren(), fragmentsByHref);
        }
    }

    @Nullable
    private static Resource resolveResource(@NonNull Book epubBook, @NonNull TOCReference ref) {
        if (ref.getResource() != null) {
            return ref.getResource();
        }
        String baseHref = extractBaseHref(ref.getCompleteHref());
        if (!baseHref.isEmpty() && epubBook.getResources() != null) {
            return epubBook.getResources().getByHref(baseHref);
        }
        return null;
    }

    @NonNull
    private static String resolveHref(@NonNull TOCReference ref, @Nullable Resource res) {
        if (res != null && !isBlank(res.getHref())) {
            return stripFragment(res.getHref());
        }
        return extractBaseHref(ref.getCompleteHref());
    }

    @NonNull
    private static String resolveFragmentId(@NonNull TOCReference ref) {
        if (!isBlank(ref.getFragmentId())) {
            return ref.getFragmentId().trim();
        }
        return extractFragmentId(ref.getCompleteHref());
    }

    @NonNull
    private static ParsedResource getParsedResource(
            @Nullable Resource res,
            @NonNull String href,
            @NonNull Map<String, ParsedResource> resourceCache
    ) {
        if (res == null) {
            return ParsedResource.EMPTY;
        }
        if (!href.isEmpty()) {
            ParsedResource cached = resourceCache.get(href);
            if (cached != null) {
                return cached;
            }
        }
        try {
            byte[] data = res.getData();
            if (data == null) {
                return ParsedResource.EMPTY;
            }
            String rawHtml = new String(data, resolveCharset(res));
            Document doc = Jsoup.parse(rawHtml);
            ParsedResource parsed = new ParsedResource(rawHtml, doc);
            if (!href.isEmpty()) {
                resourceCache.put(href, parsed);
            }
            return parsed;
        } catch (IOException e) {
            return ParsedResource.EMPTY;
        }
    }

    @NonNull
    private static ExtractedContent extractContentForNode(
            @NonNull ParsedResource parsed,
            @NonNull String fragmentId,
            @Nullable Set<String> allFragmentsInHref
    ) {
        if (parsed == ParsedResource.EMPTY) {
            return new ExtractedContent("", "");
        }
        if (fragmentId.isEmpty()) {
            return new ExtractedContent(parsed.rawHtml, parsed.bodyText);
        }

        Element target = findFragmentElement(parsed.document, fragmentId);
        if (target == null) {
            return new ExtractedContent(parsed.rawHtml, parsed.bodyText);
        }

        // Promote inline empty anchors inside headings (e.g. <h2><a id="s1"/>Title</h2>)
        Element startElement = target;
        String targetTag = target.tagName().toLowerCase(Locale.ROOT);
        if (("a".equals(targetTag) || "span".equals(targetTag))
                && isBlank(target.text())
                && target.parent() != null
                && isHeadingTag(target.parent().tagName())) {
            startElement = target.parent();
        }

        String startTag = startElement.tagName().toLowerCase(Locale.ROOT);
        if (!isHeadingTag(startTag) && !"a".equals(startTag) && !"span".equals(startTag)
                && !isBlank(startElement.text())) {
            return new ExtractedContent(startElement.outerHtml(), startElement.text());
        }

        int startHeadingRank = headingRank(startTag);
        StringBuilder htmlBuilder = new StringBuilder();
        StringBuilder textBuilder = new StringBuilder();
        Element cursor = startElement;
        while (cursor != null) {
            if (cursor != startElement) {
                if (containsOtherFragmentId(cursor, fragmentId, allFragmentsInHref)) {
                    break;
                }
                if (startHeadingRank > 0) {
                    int cursorRank = headingRank(cursor.tagName());
                    if (cursorRank > 0 && cursorRank <= startHeadingRank) {
                        break;
                    }
                }
            }
            if (htmlBuilder.length() > 0) {
                htmlBuilder.append('\n');
            }
            htmlBuilder.append(cursor.outerHtml());

            String chunkText = cursor.text().trim();
            if (!chunkText.isEmpty()) {
                if (textBuilder.length() > 0) {
                    textBuilder.append('\n');
                }
                textBuilder.append(chunkText);
            }
            cursor = cursor.nextElementSibling();
        }

        String extractedHtml = htmlBuilder.toString();
        String extractedText = textBuilder.toString();
        if (extractedHtml.isEmpty() && extractedText.isEmpty()) {
            return new ExtractedContent(parsed.rawHtml, parsed.bodyText);
        }
        return new ExtractedContent(extractedHtml, extractedText);
    }

    @Nullable
    private static Element findFragmentElement(@NonNull Document doc, @NonNull String fragmentId) {
        Element byId = doc.getElementById(fragmentId);
        if (byId != null) {
            return byId;
        }
        String escaped = fragmentId.replace("'", "\\'");
        return doc.selectFirst("[name='" + escaped + "']");
    }

    private static boolean containsOtherFragmentId(
            @NonNull Element element,
            @NonNull String currentFragmentId,
            @Nullable Set<String> allFragmentsInHref
    ) {
        if (allFragmentsInHref == null || allFragmentsInHref.isEmpty()) {
            return false;
        }
        for (String otherId : allFragmentsInHref) {
            if (otherId.equals(currentFragmentId)) {
                continue;
            }
            if (otherId.equals(element.id())) {
                return true;
            }
            if (element.getElementById(otherId) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeadingTag(@Nullable String tagName) {
        return headingRank(tagName) > 0;
    }

    private static int headingRank(@Nullable String tagName) {
        if (tagName == null) {
            return 0;
        }
        Matcher m = HEADING_TAG_PATTERN.matcher(tagName.toLowerCase(Locale.ROOT));
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    @NonNull
    private static String resolveNodeTitle(
            @NonNull TOCReference ref,
            @NonNull ParsedResource parsed,
            int level,
            int chapterIndex
    ) {
        if (!isBlank(ref.getTitle())) {
            return ref.getTitle().trim();
        }
        String fallback = (level > 0 ? "Sezione " : "Capitolo ") + chapterIndex;
        return extractDocumentTitle(parsed, fallback);
    }

    @NonNull
    private static String extractDocumentTitle(@NonNull ParsedResource parsed, @NonNull String fallback) {
        if (parsed != ParsedResource.EMPTY) {
            Element heading = parsed.document.selectFirst("h1, h2, h3");
            if (heading != null && !isBlank(heading.text())) {
                return heading.text().trim();
            }
            if (!isBlank(parsed.document.title())) {
                return parsed.document.title().trim();
            }
        }
        return fallback;
    }

    @NonNull
    private static String extractBookTitle(@NonNull Book epubBook, @Nullable String fallbackTitle) {
        if (!isBlank(epubBook.getTitle())) {
            return epubBook.getTitle().trim();
        }
        if (epubBook.getMetadata() != null && epubBook.getMetadata().getTitles() != null) {
            for (String candidate : epubBook.getMetadata().getTitles()) {
                if (!isBlank(candidate)) {
                    return candidate.trim();
                }
            }
        }
        if (!isBlank(fallbackTitle)) {
            return fallbackTitle.trim();
        }
        return "Titolo sconosciuto";
    }

    @NonNull
    private static String extractBookAuthors(@NonNull Book epubBook) {
        if (epubBook.getMetadata() == null || epubBook.getMetadata().getAuthors() == null) {
            return "Autore sconosciuto";
        }
        StringBuilder sb = new StringBuilder();
        for (Author a : epubBook.getMetadata().getAuthors()) {
            if (a == null) {
                continue;
            }
            String first = a.getFirstname() != null ? a.getFirstname().trim() : "";
            String last = a.getLastname() != null ? a.getLastname().trim() : "";
            String name = (first + " " + last).trim();
            if (!name.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(name);
            }
        }
        return sb.length() > 0 ? sb.toString() : "Autore sconosciuto";
    }

    @Nullable
    private static byte[] extractCoverBytes(@NonNull Book epubBook) {
        Resource coverImg = epubBook.getCoverImage();
        if (coverImg == null) {
            return null;
        }
        try {
            byte[] data = coverImg.getData();
            return (data != null && data.length > 0) ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    private static Charset resolveCharset(@Nullable Resource res) {
        if (res != null && !isBlank(res.getInputEncoding())) {
            try {
                return Charset.forName(res.getInputEncoding().trim());
            } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    @NonNull
    private static String stripFragment(@Nullable String href) {
        return extractBaseHref(href);
    }

    @NonNull
    private static String extractBaseHref(@Nullable String completeHref) {
        if (isBlank(completeHref)) {
            return "";
        }
        int hash = completeHref.indexOf('#');
        String base = hash >= 0 ? completeHref.substring(0, hash) : completeHref;
        return base.trim();
    }

    @NonNull
    private static String extractFragmentId(@Nullable String completeHref) {
        if (isBlank(completeHref)) {
            return "";
        }
        int hash = completeHref.indexOf('#');
        if (hash >= 0 && hash + 1 < completeHref.length()) {
            return completeHref.substring(hash + 1).trim();
        }
        return "";
    }

    private static String bookTitle(Book book) throws IOException {
        String title = book.getTitle();
        if (!isBlank(title)) {
            return title.trim();
        }
        throw new IOException("Errore durante la lettura");
    }

    private static String bookAuthor(Book book) {
        return extractBookAuthors(book);
    }

    private static void collectTocTitles(List<TOCReference> refs, Map<String, String> map) {
        if (refs == null) {
            return;
        }
        for (TOCReference ref : refs) {
            if (ref.getResource() != null && !isBlank(ref.getTitle())) {
                map.putIfAbsent(ref.getResource().getHref(), ref.getTitle().trim());
            }
            collectTocTitles(ref.getChildren(), map);
        }
    }

    private static String chapterTitle(String href, Document doc, Map<String, String> toc, int number) {
        String title = toc.get(href);
        if (title != null) {
            return title;
        }

        Element heading = doc.selectFirst("h1, h2, h3");
        if (heading != null && !isBlank(heading.text())) {
            return heading.text().trim();
        }

        if (!isBlank(doc.title())) {
            return doc.title().trim();
        }

        return "Capitolo " + number;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }
}
