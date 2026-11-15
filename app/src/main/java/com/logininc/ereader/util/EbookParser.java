package com.logininc.ereader.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Chapter;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageTree;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.GuideReference;
import io.documentnode.epub4j.domain.MediaTypes;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.domain.SpineReference;
import io.documentnode.epub4j.domain.TOCReference;
import io.documentnode.epub4j.epub.EpubReader;
import io.documentnode.epub4j.util.ResourceUtil;

/**
 * Parses EPUB (via {@code epub4j-core}), PDF (via {@code pdfbox-android}), and plain-text eBooks
 * into structured metadata, cover artwork, page counts, and ordered chapters.
 */
public final class EbookParser {

    /**
     * Parsed metadata, cover image bytes/URI, page count, and ordered chapter list for an eBook.
     */
    public static final class EbookMetadata {
        @NonNull
        public String title;
        @NonNull
        public String author = "";
        @Nullable
        public byte[] coverBytes = null;
        @Nullable
        public Uri coverUri = null;
        public int totalPages = 1;
        @NonNull
        public final List<Chapter> chapters = new ArrayList<>();

        public EbookMetadata(@NonNull String fallbackTitle) {
            this.title = fallbackTitle;
        }
    }

    private static final int CHARS_PER_ESTIMATED_PAGE = 1600;
    private static final int PART_DIVIDER_MAX_CHARS = 120;

    private static final Pattern CHAPTER_SPLIT_PATTERN =
            Pattern.compile("(?im)^\\s*(capitolo|chapter|parte|part)\\s+[\\divxlc0-9]+.*$");
    private static final Pattern HTML_HEAD_STYLE_SCRIPT_PATTERN =
            Pattern.compile("(?is)<(head|style|script|svg)[^>]*>.*?</\\1>");
    private static final Pattern HTML_COMMENT_PATTERN =
            Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern HTML_LI_OPEN_PATTERN =
            Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern HTML_BR_PATTERN =
            Pattern.compile("(?i)<br\\b[^>]*/?>");
    private static final Pattern HTML_HR_PATTERN =
            Pattern.compile("(?i)<hr\\b[^>]*/?>");
    private static final Pattern HTML_CELL_PATTERN =
            Pattern.compile("(?i)</?(td|th)\\b[^>]*>");
    private static final Pattern HTML_BLOCK_PATTERN =
            Pattern.compile("(?i)</?(p|div|h1|h2|h3|h4|h5|h6|blockquote|pre|tr|section|article|header|footer|aside|figure|figcaption|dt|dd|table|ul|ol)\\b[^>]*>");
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("<[^>]+>");
    private static final Pattern IMAGE_SRC_PATTERN =
            Pattern.compile("(?is)<(?:img|image)\\b[^>]*(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern NUMERIC_ENTITY_PATTERN =
            Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern DECORATIVE_LINE_PATTERN =
            Pattern.compile("^[\\s✦✧★☆•·*_\\-=~─—–]+$");
    private static final Pattern HEADING_TAG_PATTERN =
            Pattern.compile("(?is)<(h1|h2|title)\\b[^>]*>(.*?)</\\1>");

    private EbookParser() {
    }

    /**
     * Parses a document URI using SAF {@link Context}, dispatching by filename extension or MIME type,
     * persisting any extracted cover artwork into internal storage, and ensuring at least one chapter.
     */
    @NonNull
    public static EbookMetadata parse(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName
    ) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        String mimeType = context.getContentResolver().getType(uri);
        String lowerMime = mimeType != null ? mimeType.toLowerCase(Locale.ROOT) : "";
        String fallbackTitle = stripExtension(displayName);

        boolean isEpub = lower.endsWith(".epub") || lowerMime.contains("epub");
        boolean isPdf = lower.endsWith(".pdf") || lowerMime.contains("pdf");

        EbookMetadata metadata = new EbookMetadata(fallbackTitle);
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                if (isEpub) {
                    metadata = parseEpubStream(is, fallbackTitle);
                } else if (isPdf) {
                    metadata = parsePdfStream(context, is, fallbackTitle);
                } else {
                    String rawText = readStreamAsUtf8(is);
                    metadata = parseTextContent(rawText, fallbackTitle);
                }
            }
        } catch (IOException | SecurityException ignored) {
        }

        if (isPdf && (metadata.coverBytes == null || metadata.coverBytes.length == 0)) {
            metadata.coverBytes = renderPdfFirstPageCover(context, uri);
        }

        if (metadata.coverBytes != null && metadata.coverBytes.length > 0) {
            metadata.coverUri = FileScanner.saveCoverBytes(
                    context, uri.toString().hashCode(), metadata.coverBytes);
        }

        ensureFallbackChapter(metadata);
        return metadata;
    }

    /**
     * Parses an EPUB file from disk (usable in both Android and JVM unit tests).
     */
    @NonNull
    public static EbookMetadata parseEpubFile(@NonNull File file) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            return parseEpubStream(is, stripExtension(file.getName()));
        }
    }

    /**
     * Parses an EPUB stream using {@code epub4j-core}.
     */
    @NonNull
    public static EbookMetadata parseEpubStream(
            @NonNull InputStream inputStream,
            @NonNull String fallbackTitle
    ) {
        EbookMetadata result = new EbookMetadata(fallbackTitle);
        try {
            EpubReader epubReader = new EpubReader();
            Book epubBook = epubReader.readEpub(inputStream, "UTF-8");
            if (epubBook == null) {
                return result;
            }

            result.title = extractEpubTitle(epubBook, fallbackTitle);
            result.author = extractEpubAuthor(epubBook);
            result.coverBytes = extractEpubCoverBytes(epubBook);

            PageListInfo pageListInfo = extractEpubPageList(epubBook);
            Set<String> structuralHrefs = collectStructuralHrefs(epubBook);
            List<SpineItem> spineItems = buildSpineItems(epubBook);

            List<TocEntry> tocEntries = collectTocEntries(epubBook, structuralHrefs);
            if (!tocEntries.isEmpty()) {
                buildChaptersFromToc(
                        epubBook,
                        tocEntries,
                        spineItems,
                        structuralHrefs,
                        pageListInfo,
                        result
                );
            } else {
                buildChaptersFromSpine(spineItems, structuralHrefs, pageListInfo, result);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    /**
     * Parses a PDF file from disk using {@code pdfbox-android} (usable in JVM unit tests).
     */
    @NonNull
    public static EbookMetadata parsePdfFile(@NonNull File file) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            return parsePdfStream(null, is, stripExtension(file.getName()));
        }
    }

    /**
     * Parses a PDF stream using {@code pdfbox-android}, extracting metadata, document outline chapters
     * (or balanced page sections when no outline is present), and page text via {@link PDFTextStripper}.
     */
    @NonNull
    public static EbookMetadata parsePdfStream(
            @Nullable Context context,
            @NonNull InputStream inputStream,
            @NonNull String fallbackTitle
    ) {
        EbookMetadata result = new EbookMetadata(fallbackTitle);
        if (context != null && !PDFBoxResourceLoader.isReady()) {
            try {
                PDFBoxResourceLoader.init(context.getApplicationContext());
            } catch (RuntimeException ignored) {
            }
        }

        try (PDDocument document = PDDocument.load(inputStream)) {
            int totalPages = Math.max(1, document.getNumberOfPages());
            result.totalPages = totalPages;

            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                String docTitle = safeTrim(info.getTitle());
                if (!docTitle.isEmpty()) {
                    result.title = docTitle;
                }
                String docAuthor = normalizeAuthorName(safeTrim(info.getAuthor()));
                if (!docAuthor.isEmpty()) {
                    result.author = docAuthor;
                }
            }

            List<PdfSection> sections = extractPdfOutlineSections(document, totalPages);
            if (sections.isEmpty()) {
                sections = buildBalancedPdfSections(totalPages);
            }

            PDFTextStripper stripper = null;
            try {
                stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
            } catch (IOException | RuntimeException ignored) {
            }

            for (int i = 0; i < sections.size(); i++) {
                PdfSection sec = sections.get(i);
                String extractedText = extractPdfRangeText(document, stripper, sec.startPage, sec.endPage);
                String rangeLabel = sec.startPage == sec.endPage
                        ? "Pag. " + sec.startPage
                        : "Pag. " + sec.startPage + "–" + sec.endPage;
                String subtitle = extractedText.isEmpty() ? rangeLabel : firstLinePreview(extractedText);

                result.chapters.add(new Chapter(
                        0,
                        0,
                        i + 1,
                        sec.title,
                        subtitle,
                        "Pag. " + sec.startPage,
                        extractedText,
                        sec.startPage,
                        sec.endPage,
                        0,
                        false
                ));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    /**
     * Parses plain-text eBooks into chapters using heading heuristics or balanced chunks.
     */
    @NonNull
    public static EbookMetadata parseTextContent(
            @NonNull String rawText,
            @NonNull String fallbackTitle
    ) {
        EbookMetadata result = new EbookMetadata(fallbackTitle);
        result.totalPages = Math.max(1, rawText.length() / CHARS_PER_ESTIMATED_PAGE + 1);
        if (rawText.trim().isEmpty()) {
            return result;
        }

        Matcher matcher = CHAPTER_SPLIT_PATTERN.matcher(rawText);
        List<Integer> startIndices = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            startIndices.add(matcher.start());
            titles.add(matcher.group().trim());
        }

        if (startIndices.size() >= 2) {
            for (int i = 0; i < startIndices.size(); i++) {
                int start = startIndices.get(i);
                int end = (i + 1 < startIndices.size()) ? startIndices.get(i + 1) : rawText.length();
                String body = rawText.substring(start, end).trim();
                int estPage = Math.max(1, start / CHARS_PER_ESTIMATED_PAGE + 1);
                result.chapters.add(new Chapter(
                        0,
                        0,
                        i + 1,
                        titles.get(i),
                        firstLinePreview(body),
                        "Pag. " + estPage,
                        body,
                        0L,
                        0L,
                        0,
                        false
                ));
            }
            return result;
        }

        int chunkSize = 3500;
        int idx = 1;
        for (int start = 0; start < rawText.length(); start += chunkSize) {
            int end = Math.min(rawText.length(), start + chunkSize);
            if (end < rawText.length()) {
                int nextNewLine = rawText.indexOf('\n', end);
                if (nextNewLine != -1 && nextNewLine - end < 400) {
                    end = nextNewLine;
                }
            }
            String chunk = rawText.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                int estPage = Math.max(1, start / CHARS_PER_ESTIMATED_PAGE + 1);
                result.chapters.add(new Chapter(
                        0,
                        0,
                        idx,
                        "Parte " + idx,
                        firstLinePreview(chunk),
                        "Pag. " + estPage,
                        chunk,
                        0L,
                        0L,
                        0,
                        false
                ));
                idx++;
            }
            start = end - chunkSize;
            if (end >= rawText.length()) {
                break;
            }
        }
        return result;
    }

    @NonNull
    private static String extractEpubTitle(@NonNull Book epubBook, @NonNull String fallbackTitle) {
        String title = decodeHtmlEntities(safeTrim(epubBook.getTitle()));
        if (!title.isEmpty()) {
            return title;
        }
        if (epubBook.getMetadata() != null && epubBook.getMetadata().getTitles() != null) {
            for (String candidate : epubBook.getMetadata().getTitles()) {
                String clean = decodeHtmlEntities(safeTrim(candidate));
                if (!clean.isEmpty()) {
                    return clean;
                }
            }
        }
        return fallbackTitle;
    }

    @NonNull
    private static String extractEpubAuthor(@NonNull Book epubBook) {
        if (epubBook.getMetadata() == null) {
            return "";
        }
        String joined = joinAuthors(epubBook.getMetadata().getAuthors());
        if (!joined.isEmpty()) {
            return joined;
        }
        return joinAuthors(epubBook.getMetadata().getContributors());
    }

    @NonNull
    private static String joinAuthors(@Nullable List<Author> authors) {
        if (authors == null || authors.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(authors.size());
        for (Author author : authors) {
            if (author == null) {
                continue;
            }
            // PackageDocumentMetadataReader splits at lastIndexOf(' '); reconstruct raw string first.
            String first = safeTrim(author.getFirstname());
            String last = safeTrim(author.getLastname());
            String combined;
            if (first.isEmpty()) {
                combined = last;
            } else if (last.isEmpty()) {
                combined = first;
            } else {
                combined = first + " " + last;
            }
            String normalized = normalizeAuthorName(decodeHtmlEntities(combined));
            if (!normalized.isEmpty() && !names.contains(normalized)) {
                names.add(normalized);
            }
        }
        return String.join(", ", names);
    }

    @NonNull
    private static String normalizeAuthorName(@NonNull String rawAuthor) {
        String trimmed = rawAuthor.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int comma = trimmed.indexOf(',');
        if (comma > 0 && comma == trimmed.lastIndexOf(',')) {
            String lastPart = trimmed.substring(0, comma).trim();
            String firstPart = trimmed.substring(comma + 1).trim();
            if (!lastPart.isEmpty() && !firstPart.isEmpty()) {
                return firstPart + " " + lastPart;
            }
        }
        return trimmed;
    }

    @Nullable
    private static byte[] extractEpubCoverBytes(@NonNull Book epubBook) {
        Resource coverImage = epubBook.getCoverImage();
        byte[] data = readResourceBytes(coverImage);
        if (data != null && data.length > 0) {
            return data;
        }

        Resource coverPage = epubBook.getCoverPage();
        if (coverPage != null) {
            String html = readResourceText(coverPage);
            Matcher m = IMAGE_SRC_PATTERN.matcher(html);
            while (m.find()) {
                String src = m.group(1);
                if (src == null || src.trim().isEmpty()) {
                    continue;
                }
                String resolved = resolveRelativeHref(coverPage.getHref(), src);
                Resource imgRes = findResourceByHref(epubBook, resolved);
                if (imgRes == null) {
                    imgRes = findResourceByHref(epubBook, src);
                }
                byte[] imgData = readResourceBytes(imgRes);
                if (imgData != null && imgData.length > 0) {
                    return imgData;
                }
            }
        }

        if (epubBook.getResources() != null) {
            for (Resource res : epubBook.getResources().getAll()) {
                if (res == null || !MediaTypes.isBitmapImage(res.getMediaType())) {
                    continue;
                }
                String hrefLower = safeLower(res.getHref());
                String idLower = safeLower(res.getId());
                if (hrefLower.contains("cover") || idLower.contains("cover")) {
                    byte[] imgData = readResourceBytes(res);
                    if (imgData != null && imgData.length > 0) {
                        return imgData;
                    }
                }
            }
        }
        return null;
    }

    @NonNull
    private static Set<String> collectStructuralHrefs(@NonNull Book epubBook) {
        Set<String> structural = new HashSet<>();
        addStructuralHref(structural, epubBook.getCoverPage());
        addStructuralHref(structural, epubBook.getOpfResource());
        addStructuralHref(structural, epubBook.getNcxResource());

        if (epubBook.getSpine() != null) {
            addStructuralHref(structural, epubBook.getSpine().getTocResource());
        }

        if (epubBook.getGuide() != null && epubBook.getGuide().getReferences() != null) {
            for (GuideReference ref : epubBook.getGuide().getReferences()) {
                if (ref == null) {
                    continue;
                }
                String type = safeLower(ref.getType());
                if (GuideReference.COVER.equalsIgnoreCase(type)
                        || GuideReference.TOC.equalsIgnoreCase(type)) {
                    addStructuralHref(structural, ref.getResource());
                    if (ref.getCompleteHref() != null) {
                        String norm = normalizeHref(ref.getCompleteHref());
                        if (!norm.isEmpty()) {
                            structural.add(norm);
                        }
                    }
                }
            }
        }

        Resource navResource = findNavResource(epubBook);
        addStructuralHref(structural, navResource);
        return structural;
    }

    private static void addStructuralHref(@NonNull Set<String> out, @Nullable Resource resource) {
        if (resource == null) {
            return;
        }
        String norm = normalizeHref(resource.getHref());
        if (!norm.isEmpty()) {
            out.add(norm);
        }
    }

    @Nullable
    private static Resource findNavResource(@NonNull Book epubBook) {
        if (epubBook.getResources() == null) {
            return null;
        }
        for (Resource res : epubBook.getResources().getAll()) {
            if (res == null || res.getHref() == null) {
                continue;
            }
            String href = res.getHref();
            String lower = href.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
                continue;
            }
            String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
            if ("nav.xhtml".equals(fileName) || "nav.html".equals(fileName)) {
                return res;
            }
            String content = readResourceText(res);
            if (content.contains("epub:type=\"toc\"") || content.contains("epub:type='toc'")) {
                return res;
            }
        }
        return null;
    }

    @NonNull
    private static PageListInfo extractEpubPageList(@NonNull Book epubBook) {
        PageListInfo info = new PageListInfo();
        Resource ncx = epubBook.getNcxResource();
        if (ncx == null && epubBook.getSpine() != null) {
            ncx = epubBook.getSpine().getTocResource();
        }
        if (ncx != null) {
            parseNcxPageList(ncx, info);
        }
        if (info.hrefToStartPage.isEmpty()) {
            Resource nav = findNavResource(epubBook);
            if (nav != null) {
                parseNavPageList(nav, info);
            }
        }
        return info;
    }

    private static void parseNcxPageList(@NonNull Resource ncx, @NonNull PageListInfo info) {
        Document doc = parseXmlDocumentQuietly(ncx);
        if (doc == null) {
            return;
        }
        String baseHref = ncx.getHref();
        NodeList pageTargets = doc.getElementsByTagNameNS("*", "pageTarget");
        if (pageTargets.getLength() == 0) {
            pageTargets = doc.getElementsByTagName("pageTarget");
        }
        for (int i = 0; i < pageTargets.getLength(); i++) {
            Node node = pageTargets.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element target = (Element) node;
            int pageNum = parsePositiveInt(target.getAttribute("value"));
            if (pageNum <= 0) {
                pageNum = parsePositiveInt(getFirstDescendantText(target, "text"));
            }
            String src = getFirstDescendantAttr(target, "content", "src");
            if (pageNum > 0 && src != null && !src.isEmpty()) {
                info.record(resolveRelativeHref(baseHref, src), pageNum);
            }
        }
    }

    private static void parseNavPageList(@NonNull Resource nav, @NonNull PageListInfo info) {
        Document doc = parseXmlDocumentQuietly(nav);
        if (doc == null) {
            return;
        }
        Element pageListNav = findNavByEpubType(doc, "page-list");
        if (pageListNav == null) {
            return;
        }
        String baseHref = nav.getHref();
        NodeList links = pageListNav.getElementsByTagName("a");
        for (int i = 0; i < links.getLength(); i++) {
            Node node = links.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element a = (Element) node;
            String href = a.getAttribute("href");
            int pageNum = parsePositiveInt(a.getTextContent());
            if (pageNum > 0 && href != null && !href.isEmpty()) {
                info.record(resolveRelativeHref(baseHref, href), pageNum);
            }
        }
    }

    @NonNull
    private static List<SpineItem> buildSpineItems(@NonNull Book epubBook) {
        List<SpineItem> items = new ArrayList<>();
        if (epubBook.getSpine() == null || epubBook.getSpine().getSpineReferences() == null) {
            return items;
        }
        for (SpineReference ref : epubBook.getSpine().getSpineReferences()) {
            if (ref == null || ref.getResource() == null) {
                continue;
            }
            Resource res = ref.getResource();
            String normHref = normalizeHref(res.getHref());
            if (!normHref.isEmpty()) {
                items.add(new SpineItem(normHref, res));
            }
        }
        return items;
    }

    @NonNull
    private static List<TocEntry> collectTocEntries(
            @NonNull Book epubBook,
            @NonNull Set<String> structuralHrefs
    ) {
        List<TocEntry> entries = new ArrayList<>();
        Set<String> seenEntryHrefs = new HashSet<>();
        List<TOCReference> roots = epubBook.getTableOfContents() != null
                ? epubBook.getTableOfContents().getTocReferences()
                : Collections.emptyList();

        if (roots != null && !roots.isEmpty()) {
            for (TOCReference ref : roots) {
                flattenTocReference(ref, "", structuralHrefs, seenEntryHrefs, entries);
            }
        }

        if (entries.isEmpty()) {
            Resource nav = findNavResource(epubBook);
            if (nav != null) {
                parseNavTocEntries(epubBook, nav, structuralHrefs, seenEntryHrefs, entries);
            }
        }
        return entries;
    }

    private static void flattenTocReference(
            @Nullable TOCReference ref,
            @NonNull String inheritedPartLabel,
            @NonNull Set<String> structuralHrefs,
            @NonNull Set<String> seenEntryHrefs,
            @NonNull List<TocEntry> out
    ) {
        if (ref == null) {
            return;
        }
        String title = decodeHtmlEntities(safeTrim(ref.getTitle()));
        Resource res = ref.getResource();
        String normHref = res != null ? normalizeHref(res.getHref()) : "";
        if (normHref.isEmpty() && ref.getCompleteHref() != null) {
            normHref = normalizeHref(ref.getCompleteHref());
        }

        List<TOCReference> children = ref.getChildren() != null ? ref.getChildren() : Collections.emptyList();

        boolean addedSelf = false;
        boolean sharesHrefWithFirstChild = false;
        if (!children.isEmpty()) {
            TOCReference firstChild = children.get(0);
            String firstChildHref = firstChild != null && firstChild.getResource() != null
                    ? normalizeHref(firstChild.getResource().getHref())
                    : (firstChild != null ? normalizeHref(firstChild.getCompleteHref()) : "");
            sharesHrefWithFirstChild = !normHref.isEmpty() && normHref.equals(firstChildHref);
            if (!sharesHrefWithFirstChild
                    && !normHref.isEmpty()
                    && !structuralHrefs.contains(normHref)
                    && res != null
                    && !seenEntryHrefs.contains(normHref)) {
                String plain = htmlToPlainText(readResourceText(res));
                if (plain.length() >= PART_DIVIDER_MAX_CHARS) {
                    seenEntryHrefs.add(normHref);
                    out.add(new TocEntry(
                            title.isEmpty() ? extractHtmlHeading(readResourceText(res)) : title,
                            inheritedPartLabel,
                            normHref,
                            res
                    ));
                    addedSelf = true;
                }
            }
        } else if (!normHref.isEmpty() && !structuralHrefs.contains(normHref) && res != null) {
            if (!seenEntryHrefs.contains(normHref)) {
                seenEntryHrefs.add(normHref);
                out.add(new TocEntry(
                        title.isEmpty() ? extractHtmlHeading(readResourceText(res)) : title,
                        inheritedPartLabel,
                        normHref,
                        res
                ));
                addedSelf = true;
            }
        }

        if (!children.isEmpty()) {
            if (!normHref.isEmpty() && !sharesHrefWithFirstChild) {
                structuralHrefs.add(normHref);
            }
            String childPart = (!addedSelf && !title.isEmpty()) ? title : inheritedPartLabel;
            for (TOCReference child : children) {
                flattenTocReference(child, childPart, structuralHrefs, seenEntryHrefs, out);
            }
        }
    }

    private static void parseNavTocEntries(
            @NonNull Book epubBook,
            @NonNull Resource nav,
            @NonNull Set<String> structuralHrefs,
            @NonNull Set<String> seenEntryHrefs,
            @NonNull List<TocEntry> out
    ) {
        Document doc = parseXmlDocumentQuietly(nav);
        if (doc == null) {
            return;
        }
        Element tocNav = findNavByEpubType(doc, "toc");
        if (tocNav == null) {
            return;
        }
        String baseHref = nav.getHref();
        for (int i = 0; i < tocNav.getChildNodes().getLength(); i++) {
            Node child = tocNav.getChildNodes().item(i);
            if (child instanceof Element && "ol".equalsIgnoreCase(child.getLocalName())) {
                walkNavOl(epubBook, (Element) child, baseHref, "", structuralHrefs, seenEntryHrefs, out);
            }
        }
    }

    private static void walkNavOl(
            @NonNull Book epubBook,
            @NonNull Element olElement,
            @NonNull String baseHref,
            @NonNull String inheritedPartLabel,
            @NonNull Set<String> structuralHrefs,
            @NonNull Set<String> seenEntryHrefs,
            @NonNull List<TocEntry> out
    ) {
        NodeList children = olElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element) || !"li".equalsIgnoreCase(n.getLocalName())) {
                continue;
            }
            Element li = (Element) n;
            Element anchor = null;
            Element nestedOl = null;
            NodeList liChildren = li.getChildNodes();
            for (int j = 0; j < liChildren.getLength(); j++) {
                Node c = liChildren.item(j);
                if (!(c instanceof Element)) {
                    continue;
                }
                String local = safeLower(c.getLocalName());
                if ("a".equals(local) && anchor == null) {
                    anchor = (Element) c;
                } else if ("ol".equals(local) && nestedOl == null) {
                    nestedOl = (Element) c;
                }
            }
            String title = anchor != null ? decodeHtmlEntities(safeTrim(anchor.getTextContent())) : "";
            String rawHref = anchor != null ? anchor.getAttribute("href") : "";
            String normHref = rawHref != null && !rawHref.isEmpty()
                    ? normalizeHref(resolveRelativeHref(baseHref, rawHref))
                    : "";
            Resource res = !normHref.isEmpty() ? findResourceByHref(epubBook, normHref) : null;

            boolean addedSelf = false;
            boolean sharesHrefWithFirstChild = false;
            if (nestedOl != null) {
                String firstChildHref = findFirstAnchorHref(nestedOl, baseHref);
                sharesHrefWithFirstChild = !normHref.isEmpty() && normHref.equals(firstChildHref);
                if (!sharesHrefWithFirstChild
                        && !structuralHrefs.contains(normHref)
                        && res != null && !seenEntryHrefs.contains(normHref)) {
                    String plain = htmlToPlainText(readResourceText(res));
                    if (plain.length() >= PART_DIVIDER_MAX_CHARS) {
                        seenEntryHrefs.add(normHref);
                        out.add(new TocEntry(title, inheritedPartLabel, normHref, res));
                        addedSelf = true;
                    }
                }
                if (!normHref.isEmpty() && !sharesHrefWithFirstChild) {
                    structuralHrefs.add(normHref);
                }
                String nextPart = (!addedSelf && !title.isEmpty()) ? title : inheritedPartLabel;
                walkNavOl(epubBook, nestedOl, baseHref, nextPart, structuralHrefs, seenEntryHrefs, out);
            } else if (!normHref.isEmpty() && !structuralHrefs.contains(normHref)
                    && res != null && !seenEntryHrefs.contains(normHref)) {
                seenEntryHrefs.add(normHref);
                out.add(new TocEntry(title, inheritedPartLabel, normHref, res));
            }
        }
    }

    private static void buildChaptersFromToc(
            @NonNull Book epubBook,
            @NonNull List<TocEntry> tocEntries,
            @NonNull List<SpineItem> spineItems,
            @NonNull Set<String> structuralHrefs,
            @NonNull PageListInfo pageListInfo,
            @NonNull EbookMetadata result
    ) {
        Map<String, Integer> spineIndexMap = new HashMap<>();
        for (int i = 0; i < spineItems.size(); i++) {
            spineIndexMap.putIfAbsent(spineItems.get(i).normHref, i);
        }

        Set<String> allHandledHrefs = new HashSet<>(structuralHrefs);
        for (TocEntry entry : tocEntries) {
            allHandledHrefs.add(entry.normHref);
        }

        int cumulativeChars = 0;
        for (int i = 0; i < tocEntries.size(); i++) {
            TocEntry entry = tocEntries.get(i);
            StringBuilder combinedHtml = new StringBuilder();
            combinedHtml.append(readResourceText(entry.resource));

            Integer startSpineIdx = spineIndexMap.get(entry.normHref);
            if (startSpineIdx != null) {
                for (int s = startSpineIdx + 1; s < spineItems.size(); s++) {
                    SpineItem nextItem = spineItems.get(s);
                    if (allHandledHrefs.contains(nextItem.normHref)) {
                        break;
                    }
                    allHandledHrefs.add(nextItem.normHref);
                    String contHtml = readResourceText(nextItem.resource);
                    if (!contHtml.trim().isEmpty()) {
                        combinedHtml.append('\n').append(contHtml);
                    }
                }
            }

            String rawHtml = combinedHtml.toString();
            String chapterTitle = !entry.title.isEmpty()
                    ? entry.title
                    : extractHtmlHeading(rawHtml);
            if (chapterTitle.isEmpty()) {
                chapterTitle = "Capitolo " + (result.chapters.size() + 1);
            }

            String plain = htmlToPlainText(rawHtml);
            plain = stripLeadingDuplicateTitle(plain, chapterTitle, entry.partLabel);
            if (plain.isEmpty()) {
                continue;
            }

            int chapterNum = result.chapters.size() + 1;
            int estPage = Math.max(1, cumulativeChars / CHARS_PER_ESTIMATED_PAGE + 1);
            Integer pageFromList = pageListInfo.getStartPage(entry.normHref);
            int displayPage = pageFromList != null ? pageFromList : estPage;
            cumulativeChars += plain.length();

            String preview = firstLinePreview(plain);
            String subtitle = !entry.partLabel.isEmpty() && !entry.partLabel.equalsIgnoreCase(chapterTitle)
                    ? entry.partLabel + " • " + preview
                    : preview;

            result.chapters.add(new Chapter(
                    0,
                    0,
                    chapterNum,
                    chapterTitle,
                    subtitle,
                    "Pag. " + displayPage,
                    plain,
                    0L,
                    0L,
                    0,
                    false
            ));
        }

        int estimatedTotalPages = Math.max(1, cumulativeChars / CHARS_PER_ESTIMATED_PAGE + 1);
        result.totalPages = Math.max(pageListInfo.maxPage, estimatedTotalPages);
    }

    private static void buildChaptersFromSpine(
            @NonNull List<SpineItem> spineItems,
            @NonNull Set<String> structuralHrefs,
            @NonNull PageListInfo pageListInfo,
            @NonNull EbookMetadata result
    ) {
        int cumulativeChars = 0;
        for (SpineItem item : spineItems) {
            if (structuralHrefs.contains(item.normHref)) {
                continue;
            }
            String html = readResourceText(item.resource);
            String plain = htmlToPlainText(html);
            if (plain.length() <= 40) {
                continue;
            }
            int chapterNum = result.chapters.size() + 1;
            String heading = extractHtmlHeading(html);
            String title = !heading.isEmpty() ? heading : ("Capitolo " + chapterNum);
            plain = stripLeadingDuplicateTitle(plain, title, "");

            int estPage = Math.max(1, cumulativeChars / CHARS_PER_ESTIMATED_PAGE + 1);
            Integer pageFromList = pageListInfo.getStartPage(item.normHref);
            int displayPage = pageFromList != null ? pageFromList : estPage;
            cumulativeChars += plain.length();

            result.chapters.add(new Chapter(
                    0,
                    0,
                    chapterNum,
                    title,
                    firstLinePreview(plain),
                    "Pag. " + displayPage,
                    plain,
                    0L,
                    0L,
                    0,
                    false
            ));
        }

        int estimatedTotalPages = Math.max(1, cumulativeChars / CHARS_PER_ESTIMATED_PAGE + 1);
        result.totalPages = Math.max(pageListInfo.maxPage, estimatedTotalPages);
    }

    @NonNull
    private static List<PdfSection> extractPdfOutlineSections(
            @NonNull PDDocument document,
            int totalPages
    ) {
        List<PdfSection> result = new ArrayList<>();
        if (document.getDocumentCatalog() == null) {
            return result;
        }
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return result;
        }

        PDPageTree pageTree = document.getPages();
        List<PdfOutlineEntry> entries = new ArrayList<>();
        collectOutlineEntries(document, pageTree, outline.getFirstChild(), entries, 0);
        if (entries.isEmpty()) {
            return result;
        }

        entries.sort((a, b) -> Integer.compare(a.startPage, b.startPage));
        List<PdfOutlineEntry> deduped = new ArrayList<>();
        for (PdfOutlineEntry e : entries) {
            if (deduped.isEmpty() || deduped.get(deduped.size() - 1).startPage != e.startPage) {
                deduped.add(e);
            }
        }

        for (int i = 0; i < deduped.size(); i++) {
            PdfOutlineEntry cur = deduped.get(i);
            int endPage = (i + 1 < deduped.size())
                    ? Math.max(cur.startPage, deduped.get(i + 1).startPage - 1)
                    : totalPages;
            result.add(new PdfSection(cur.title, cur.startPage, endPage));
        }
        return result;
    }

    private static void collectOutlineEntries(
            @NonNull PDDocument document,
            @NonNull PDPageTree pageTree,
            @Nullable PDOutlineItem item,
            @NonNull List<PdfOutlineEntry> out,
            int depth
    ) {
        if (depth > 2) {
            return;
        }
        PDOutlineItem current = item;
        while (current != null) {
            String title = safeTrim(current.getTitle());
            if (!title.isEmpty()) {
                try {
                    PDPage destPage = current.findDestinationPage(document);
                    if (destPage != null) {
                        int pageIndexZeroBased = pageTree.indexOf(destPage);
                        if (pageIndexZeroBased >= 0) {
                            out.add(new PdfOutlineEntry(title, pageIndexZeroBased + 1));
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            if (current.hasChildren() && depth < 1) {
                collectOutlineEntries(document, pageTree, current.getFirstChild(), out, depth + 1);
            }
            current = current.getNextSibling();
        }
    }

    @NonNull
    private static List<PdfSection> buildBalancedPdfSections(int totalPages) {
        List<PdfSection> sections = new ArrayList<>();
        int pagesPerChapter = Math.max(1, Math.min(15, (int) Math.ceil(totalPages / 10.0)));
        int index = 1;
        for (int startPage = 1; startPage <= totalPages; startPage += pagesPerChapter) {
            int endPage = Math.min(totalPages, startPage + pagesPerChapter - 1);
            String rangeLabel = startPage == endPage
                    ? "Pag. " + startPage
                    : "Pag. " + startPage + "–" + endPage;
            sections.add(new PdfSection("Sezione " + index + " (" + rangeLabel + ")", startPage, endPage));
            index++;
        }
        return sections;
    }

    @NonNull
    private static String extractPdfRangeText(
            @NonNull PDDocument document,
            @Nullable PDFTextStripper stripper,
            int startPage,
            int endPage
    ) {
        if (stripper == null) {
            return "";
        }
        try {
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);
            String raw = stripper.getText(document);
            if (raw == null) {
                return "";
            }
            return raw.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("[ \\t\\x0B\\f]+\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    @Nullable
    private static byte[] renderPdfFirstPageCover(@NonNull Context context, @NonNull Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return null;
            }
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                if (renderer.getPageCount() <= 0) {
                    return null;
                }
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    int width = 480;
                    int height = Math.max(480, Math.round((float) width * page.getHeight() / Math.max(1, page.getWidth())));
                    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    bmp.recycle();
                    return out.toByteArray();
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void ensureFallbackChapter(@NonNull EbookMetadata metadata) {
        if (metadata.chapters.isEmpty()) {
            metadata.chapters.add(new Chapter(
                    0, 0, 1, metadata.title, "", "1", "", 0L, 0L, 0, false));
        }
    }

    /**
     * Converts XHTML/HTML into clean readable plain text while preserving paragraph breaks and
     * without injecting spaces inside inline spans/links.
     */
    @NonNull
    public static String htmlToPlainText(@NonNull String html) {
        if (html.isEmpty()) {
            return "";
        }
        String clean = HTML_HEAD_STYLE_SCRIPT_PATTERN.matcher(html).replaceAll("");
        clean = HTML_COMMENT_PATTERN.matcher(clean).replaceAll("");
        clean = HTML_LI_OPEN_PATTERN.matcher(clean).replaceAll("\n• ");
        clean = HTML_BR_PATTERN.matcher(clean).replaceAll("\n");
        clean = HTML_HR_PATTERN.matcher(clean).replaceAll("\n\n* * *\n\n");
        clean = HTML_CELL_PATTERN.matcher(clean).replaceAll(" ");
        clean = HTML_BLOCK_PATTERN.matcher(clean).replaceAll("\n\n");
        clean = HTML_TAG_PATTERN.matcher(clean).replaceAll("");
        clean = decodeHtmlEntities(clean);

        // Normalize spaces on each line while preserving intentional paragraph breaks.
        String[] rawLines = clean.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder(clean.length());
        int blankCount = 0;
        for (String line : rawLines) {
            String trimmedLine = line.replaceAll("[ \\t\\x0B\\f\\u00A0]+", " ").trim();
            if (trimmedLine.isEmpty()) {
                if (sb.length() > 0 && blankCount == 0) {
                    blankCount = 1;
                }
            } else {
                if (sb.length() > 0) {
                    sb.append(blankCount > 0 ? "\n\n" : "\n");
                }
                sb.append(trimmedLine);
                blankCount = 0;
            }
        }
        return sb.toString().trim();
    }

    @NonNull
    private static String stripLeadingDuplicateTitle(
            @NonNull String plainText,
            @NonNull String chapterTitle,
            @NonNull String partLabel
    ) {
        if (plainText.isEmpty()) {
            return plainText;
        }
        String normChapter = normalizeComparisonKey(chapterTitle);
        String normPart = normalizeComparisonKey(partLabel);
        String[] paragraphs = plainText.split("\n\n");
        if (paragraphs.length <= 1) {
            return plainText;
        }

        int startIdx = 0;
        int titleStrips = 0;
        while (startIdx < paragraphs.length - 1 && startIdx < 4) {
            String p = paragraphs[startIdx].trim();
            if (p.isEmpty()) {
                startIdx++;
                continue;
            }
            if (DECORATIVE_LINE_PATTERN.matcher(p).matches() && p.length() <= 24) {
                startIdx++;
                continue;
            }
            if (titleStrips >= 3) {
                break;
            }
            String normPara = normalizeComparisonKey(p);
            if (!normPara.isEmpty() && normPara.length() <= 140 && matchesHeader(normPara, normChapter, normPart)) {
                startIdx++;
                titleStrips++;
                continue;
            }
            break;
        }

        if (startIdx == 0) {
            return plainText;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < paragraphs.length; i++) {
            String p = paragraphs[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean matchesHeader(
            @NonNull String normPara,
            @NonNull String normChapter,
            @NonNull String normPart
    ) {
        if (normPara.length() < 3) {
            return false;
        }
        if (!normChapter.isEmpty() && (normChapter.equals(normPara)
                || normChapter.startsWith(normPara + " ")
                || normChapter.endsWith(" " + normPara))) {
            return true;
        }
        return !normPart.isEmpty() && (normPart.equals(normPara)
                || normPart.startsWith(normPara + " ")
                || normPart.endsWith(" " + normPara));
    }

    @NonNull
    private static String normalizeComparisonKey(@NonNull String input) {
        String lower = decodeHtmlEntities(input)
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('‘', '\'')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return lower;
    }

    @NonNull
    public static String decodeHtmlEntities(@NonNull String text) {
        if (text.isEmpty() || text.indexOf('&') < 0) {
            return text;
        }
        String out = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&rsquo;", "’")
                .replace("&lsquo;", "‘")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&trade;", "™")
                .replace("&bull;", "•");

        Matcher m = NUMERIC_ENTITY_PATTERN.matcher(out);
        if (!m.find()) {
            return out;
        }
        m.reset();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(1);
            String replacement = m.group(0);
            if (raw != null) {
                try {
                    int codePoint = raw.startsWith("x") || raw.startsWith("X")
                            ? Integer.parseInt(raw.substring(1), 16)
                            : Integer.parseInt(raw, 10);
                    if (Character.isValidCodePoint(codePoint)) {
                        replacement = new String(Character.toChars(codePoint));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @NonNull
    private static String extractHtmlHeading(@NonNull String html) {
        Matcher m = HEADING_TAG_PATTERN.matcher(html);
        while (m.find()) {
            String inner = m.group(2);
            if (inner == null) {
                continue;
            }
            String clean = htmlToPlainText(inner).replace('\n', ' ').trim();
            if (!clean.isEmpty() && clean.length() <= 140) {
                return clean;
            }
        }
        return "";
    }

    @NonNull
    private static String readResourceText(@Nullable Resource resource) {
        byte[] data = readResourceBytes(resource);
        if (data == null || data.length == 0) {
            return "";
        }
        String encoding = resource != null ? resource.getInputEncoding() : null;
        if (encoding != null && !encoding.trim().isEmpty()) {
            try {
                return new String(data, encoding.trim());
            } catch (Exception ignored) {
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    @Nullable
    private static byte[] readResourceBytes(@Nullable Resource resource) {
        if (resource == null) {
            return null;
        }
        try {
            return resource.getData();
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static Resource findResourceByHref(@NonNull Book epubBook, @NonNull String href) {
        if (epubBook.getResources() == null || href.isEmpty()) {
            return null;
        }
        String clean = stripFragment(href);
        Resource res = epubBook.getResources().getByHref(clean);
        if (res != null) {
            return res;
        }
        for (Resource candidate : epubBook.getResources().getAll()) {
            if (candidate != null && candidate.getHref() != null) {
                String cHref = normalizeHref(candidate.getHref());
                if (cHref.equals(clean) || cHref.endsWith("/" + clean) || clean.endsWith("/" + cHref)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Document parseXmlDocumentQuietly(@NonNull Resource resource) {
        try {
            return ResourceUtil.getAsDocument(resource);
        } catch (Exception ignored) {
            try (InputStream is = resource.getInputStream()) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                dbf.setValidating(false);
                DocumentBuilder builder = dbf.newDocumentBuilder();
                return builder.parse(is);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    @Nullable
    private static Element findNavByEpubType(@NonNull Document doc, @NonNull String targetType) {
        NodeList navs = doc.getElementsByTagNameNS("*", "nav");
        if (navs.getLength() == 0) {
            navs = doc.getElementsByTagName("nav");
        }
        for (int i = 0; i < navs.getLength(); i++) {
            Node node = navs.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element nav = (Element) node;
            String epubType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type");
            if (epubType.isEmpty()) {
                epubType = nav.getAttribute("epub:type");
            }
            if (targetType.equalsIgnoreCase(epubType.trim())) {
                return nav;
            }
        }
        return null;
    }

    @NonNull
    private static String findFirstAnchorHref(@NonNull Element olElement, @NonNull String baseHref) {
        NodeList anchors = olElement.getElementsByTagName("a");
        if (anchors.getLength() > 0 && anchors.item(0) instanceof Element) {
            String href = ((Element) anchors.item(0)).getAttribute("href");
            if (href != null && !href.isEmpty()) {
                return normalizeHref(resolveRelativeHref(baseHref, href));
            }
        }
        return "";
    }

    @Nullable
    private static String getFirstDescendantText(@NonNull Element parent, @NonNull String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(localName);
        }
        return list.getLength() > 0 ? safeTrim(list.item(0).getTextContent()) : null;
    }

    @Nullable
    private static String getFirstDescendantAttr(
            @NonNull Element parent,
            @NonNull String localName,
            @NonNull String attrName
    ) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(localName);
        }
        if (list.getLength() > 0 && list.item(0) instanceof Element) {
            return ((Element) list.item(0)).getAttribute(attrName);
        }
        return null;
    }

    private static int parsePositiveInt(@Nullable String raw) {
        if (raw == null) {
            return -1;
        }
        String digits = raw.trim();
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            int val = Integer.parseInt(digits);
            return val > 0 ? val : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    private static String resolveRelativeHref(@Nullable String baseHref, @Nullable String relativeHref) {
        if (relativeHref == null || relativeHref.trim().isEmpty()) {
            return "";
        }
        String cleanRel = stripFragment(relativeHref.trim());
        if (cleanRel.startsWith("/")) {
            cleanRel = cleanRel.substring(1);
        }
        String baseDir = "";
        if (baseHref != null) {
            int lastSlash = baseHref.lastIndexOf('/');
            if (lastSlash >= 0) {
                baseDir = baseHref.substring(0, lastSlash + 1);
            }
        }
        String joined = baseDir.isEmpty() ? cleanRel : (baseDir + cleanRel);
        return collapseDots(joined);
    }

    @NonNull
    private static String collapseDots(@NonNull String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(part);
            }
        }
        return String.join("/", stack);
    }

    @NonNull
    private static String normalizeHref(@Nullable String href) {
        if (href == null) {
            return "";
        }
        String stripped = stripFragment(href.trim()).replace('\\', '/');
        while (stripped.startsWith("./")) {
            stripped = stripped.substring(2);
        }
        return stripped;
    }

    @NonNull
    private static String stripFragment(@NonNull String href) {
        int hash = href.indexOf('#');
        return hash >= 0 ? href.substring(0, hash) : href;
    }

    @NonNull
    private static String readStreamAsUtf8(@NonNull InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int nRead;
        while ((nRead = is.read(chunk, 0, chunk.length)) != -1) {
            buffer.write(chunk, 0, nRead);
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }

    @NonNull
    private static String firstLinePreview(@NonNull String text) {
        String clean = text.replace('\n', ' ').trim();
        return clean.length() > 70 ? clean.substring(0, 70) + "…" : clean;
    }

    @NonNull
    private static String stripExtension(@NonNull String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    @NonNull
    private static String safeTrim(@Nullable String value) {
        return value != null ? value.trim() : "";
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private static final class TocEntry {
        final String title;
        final String partLabel;
        final String normHref;
        final Resource resource;

        TocEntry(String title, String partLabel, String normHref, Resource resource) {
            this.title = title;
            this.partLabel = partLabel;
            this.normHref = normHref;
            this.resource = resource;
        }
    }

    private static final class SpineItem {
        final String normHref;
        final Resource resource;

        SpineItem(String normHref, Resource resource) {
            this.normHref = normHref;
            this.resource = resource;
        }
    }

    private static final class PageListInfo {
        final Map<String, Integer> hrefToStartPage = new HashMap<>();
        int maxPage = 0;

        void record(@NonNull String normHref, int pageNum) {
            if (pageNum <= 0) {
                return;
            }
            maxPage = Math.max(maxPage, pageNum);
            if (!normHref.isEmpty()) {
                Integer existing = hrefToStartPage.get(normHref);
                if (existing == null || pageNum < existing) {
                    hrefToStartPage.put(normHref, pageNum);
                }
            }
        }

        @Nullable
        Integer getStartPage(@NonNull String normHref) {
            return hrefToStartPage.get(normHref);
        }
    }

    private static final class PdfSection {
        final String title;
        final int startPage;
        final int endPage;

        PdfSection(String title, int startPage, int endPage) {
            this.title = title;
            this.startPage = startPage;
            this.endPage = endPage;
        }
    }

    private static final class PdfOutlineEntry {
        final String title;
        final int startPage;

        PdfOutlineEntry(String title, int startPage) {
            this.title = title;
            this.startPage = startPage;
        }
    }
}
