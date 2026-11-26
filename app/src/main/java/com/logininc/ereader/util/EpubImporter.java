package com.logininc.ereader.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.TitleDao;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.domain.SpineReference;
import io.documentnode.epub4j.domain.TOCReference;

/**
 * Parses and imports EPUB books (NCX TOC, EPUB 3 {@code nav.xhtml}, HTML {@code <section>} trees,
 * fragment-scoped chapter text, and cover artwork) into SQLite.
 */
public final class EpubImporter {

    private static final String TAG = "EpubImporter";

    private EpubImporter() {
    }

    @Nullable
    public static Book importEpub(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return null;
            }
            io.documentnode.epub4j.domain.Book epubBook = EbookParser.parse(is);
            String titleText = epubBook.getTitle();
            titleText = (titleText == null || titleText.trim().isEmpty())
                    ? FileScanner.stripExtension(displayName)
                    : titleText.trim();

            Uri coverUri = null;
            if (epubBook.getCoverImage() != null && epubBook.getCoverImage().getData() != null) {
                coverUri = FileScanner.saveCoverBytes(
                        context,
                        "epub_" + Math.abs(uri.toString().hashCode()),
                        epubBook.getCoverImage().getData()
                );
            }

            List<Chapter> chapters = buildEpubChapters(epubBook);
            if (chapters.isEmpty()) {
                chapters.add(new Chapter(
                        0, 0, 0, 1, 0, 0,
                        titleText, "", "", "", "",
                        "", "", 0L, 0L, 0, false
                ));
            }

            List<Chapter> flattened = Chapter.flattenTree(chapters);
            Book book = new Book(0, titleText, flattened.size(), uri);
            book.setAuthor(extractEpubAuthor(epubBook));
            book.setCoverUri(coverUri);
            book.setTotalPages(estimateEpubTotalPages(chapters));
            book.setChapters(chapters);

            return titleDao.saveBookWithChapters(book) != -1 ? book : null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to import EPUB: " + uri, e);
            return null;
        }
    }

    @NonNull
    public static List<Chapter> buildEpubChapters(@NonNull io.documentnode.epub4j.domain.Book epubBook) {
        List<Chapter> roots = new ArrayList<>();
        int[] indexCounter = {1};

        List<TOCReference> tocRefs = epubBook.getTableOfContents() != null
                ? epubBook.getTableOfContents().getTocReferences()
                : null;
        if (tocRefs != null && !tocRefs.isEmpty()) {
            for (int i = 0; i < tocRefs.size(); i++) {
                roots.add(convertTocReference(tocRefs.get(i), 0, i, indexCounter));
            }
            return roots;
        }

        List<Chapter> navChapters = buildChaptersFromEpub3Nav(epubBook, indexCounter);
        if (!navChapters.isEmpty()) {
            return navChapters;
        }

        List<SpineReference> spineRefs = epubBook.getSpine() != null
                ? epubBook.getSpine().getSpineReferences()
                : null;
        if (spineRefs == null) {
            return roots;
        }
        for (int i = 0; i < spineRefs.size(); i++) {
            Resource res = spineRefs.get(i).getResource();
            String rawHtml = readResourceHtml(res);
            String plainText = extractReadableText(rawHtml, null);
            String chTitle = (res != null && res.getTitle() != null && !res.getTitle().trim().isEmpty())
                    ? res.getTitle().trim()
                    : "Chapter " + (i + 1);
            String href = res != null && res.getHref() != null ? res.getHref() : "";
            Chapter chapter = new Chapter(
                    0, 0, 0, indexCounter[0]++, 0, i,
                    chTitle, "", href, "", "",
                    plainText, "", 0L, 0L, 0, false
            );
            attachSectionsAndClearContainerText(chapter, rawHtml, href, 0, indexCounter);
            roots.add(chapter);
        }
        return roots;
    }

    public static int estimateEpubTotalPages(@NonNull List<Chapter> rootChapters) {
        List<Chapter> leaves = ChapterTreeHelper.collectLeafChapters(rootChapters);
        long totalChars = 0L;
        for (Chapter leaf : leaves) {
            totalChars += leaf.getContentText().length();
        }
        int byChars = (int) Math.ceil(totalChars / 1800.0);
        return Math.max(Math.max(1, leaves.size()), byChars);
    }

    public static void attachHtmlSectionsIfPresent(
            @NonNull Chapter parent,
            @Nullable String rawHtml,
            @NonNull String href,
            int parentLevel,
            @NonNull int[] indexCounter
    ) {
        if (rawHtml == null || rawHtml.trim().isEmpty()) {
            return;
        }
        Document doc = Jsoup.parse(rawHtml);
        doc.select("script, style").remove();
        Element body = doc.body();
        if (body == null) {
            return;
        }
        List<Element> topSections = directSectionElements(body);
        if (topSections.size() == 1) {
            List<Element> inner = directSectionElements(topSections.get(0));
            if (inner.size() < 2) {
                return;
            }
            topSections = inner;
        }
        if (topSections.size() < 2) {
            return;
        }
        for (int i = 0; i < topSections.size(); i++) {
            parent.addChild(convertHtmlSectionElement(topSections.get(i), href, parentLevel + 1, i, indexCounter));
        }
    }

    @NonNull
    public static String extractReadableText(@Nullable String rawHtml, @Nullable String fragmentId) {
        if (rawHtml == null || rawHtml.trim().isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(rawHtml);
        doc.select("script, style").remove();
        if (doc.body() == null) {
            return "";
        }

        String scopedHtml = extractScopedFragmentHtml(doc, fragmentId);
        Element workBody = (scopedHtml != null ? Jsoup.parseBodyFragment(scopedHtml) : doc).body();
        if (workBody == null) {
            return "";
        }
        workBody.select("br").append("%%BR%%");
        workBody.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, div, tr").prepend("%%PARA%%");

        String rawText = workBody.text()
                .replace("%%PARA%%", "\n\n")
                .replace("%%BR%%", "\n");
        return collapseWhitespaceLines(rawText);
    }

    @NonNull
    private static String extractEpubAuthor(@NonNull io.documentnode.epub4j.domain.Book epubBook) {
        if (epubBook.getMetadata() == null || epubBook.getMetadata().getAuthors() == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Author author : epubBook.getMetadata().getAuthors()) {
            String first = author.getFirstname() != null ? author.getFirstname().trim() : "";
            String last = author.getLastname() != null ? author.getLastname().trim() : "";
            String combined = (first + " " + last).trim();
            if (!combined.isEmpty()) {
                names.add(combined);
            }
        }
        return String.join(", ", names);
    }

    @NonNull
    private static List<Chapter> buildChaptersFromEpub3Nav(
            @NonNull io.documentnode.epub4j.domain.Book epubBook,
            @NonNull int[] indexCounter
    ) {
        if (epubBook.getResources() == null) {
            return new ArrayList<>();
        }
        for (Resource res : epubBook.getResources().getAll()) {
            String href = res.getHref() != null ? res.getHref().toLowerCase(Locale.ROOT) : "";
            if (!href.endsWith(".xhtml") && !href.endsWith(".html")) {
                continue;
            }
            String html = readResourceHtml(res);
            if (!html.contains("epub:type=\"toc\"") && !html.contains("epub:type='toc'")) {
                continue;
            }
            Element nav = Jsoup.parse(html).selectFirst("nav[epub|type=toc], nav[epub\\:type=toc], nav");
            Element rootOl = nav != null ? nav.selectFirst("ol") : null;
            if (rootOl == null) {
                continue;
            }
            String origHref = res.getHref() != null ? res.getHref() : "";
            int slash = origHref.lastIndexOf('/');
            String baseDir = slash >= 0 ? origHref.substring(0, slash + 1) : "";
            return parseNavList(epubBook, rootOl, baseDir, 0, indexCounter);
        }
        return new ArrayList<>();
    }

    @NonNull
    private static List<Chapter> parseNavList(
            @NonNull io.documentnode.epub4j.domain.Book epubBook,
            @NonNull Element ol,
            @NonNull String baseDir,
            int level,
            @NonNull int[] indexCounter
    ) {
        List<Chapter> nodes = new ArrayList<>();
        int sortOrder = 0;
        for (Element child : ol.children()) {
            if (!"li".equalsIgnoreCase(child.tagName())) {
                continue;
            }
            Element link = child.selectFirst("> a, > span");
            String title = link != null ? link.text().trim() : "";
            String rawHref = (link != null && "a".equalsIgnoreCase(link.tagName())) ? link.attr("href").trim() : "";
            Element nestedOl = child.selectFirst("> ol");
            boolean hasChildren = nestedOl != null && !nestedOl.select("> li").isEmpty();

            int hash = rawHref.indexOf('#');
            String fileHref = hash >= 0 ? rawHref.substring(0, hash) : rawHref;
            String fragmentId = hash >= 0 ? rawHref.substring(hash + 1) : "";
            String resolvedHref = (!fileHref.isEmpty() && !baseDir.isEmpty() && !fileHref.contains("/"))
                    ? baseDir + fileHref
                    : fileHref;

            int chIndex = indexCounter[0]++;
            if (title.isEmpty()) {
                title = "Chapter " + chIndex;
            }
            String rawHtml = "";
            String plainText = "";
            if (!hasChildren && !resolvedHref.isEmpty() && epubBook.getResources() != null) {
                Resource targetRes = epubBook.getResources().getByHref(resolvedHref);
                if (targetRes == null && !fileHref.isEmpty()) {
                    targetRes = epubBook.getResources().getByHref(fileHref);
                }
                rawHtml = readResourceHtml(targetRes);
                plainText = extractReadableText(rawHtml, fragmentId);
            }

            Chapter node = new Chapter(
                    0, 0, 0, chIndex, level, sortOrder++,
                    title, "", resolvedHref, fragmentId, "",
                    plainText, "", 0L, 0L, 0, false
            );
            if (hasChildren) {
                for (Chapter sub : parseNavList(epubBook, nestedOl, baseDir, level + 1, indexCounter)) {
                    node.addChild(sub);
                }
            } else if (fragmentId.isEmpty() && !rawHtml.isEmpty()) {
                attachSectionsAndClearContainerText(node, rawHtml, resolvedHref, level, indexCounter);
            }
            nodes.add(node);
        }
        return nodes;
    }

    @NonNull
    private static Chapter convertTocReference(
            @NonNull TOCReference ref,
            int level,
            int sortOrder,
            @NonNull int[] indexCounter
    ) {
        int chIndex = indexCounter[0]++;
        Resource res = ref.getResource();
        String href = res != null && res.getHref() != null ? res.getHref() : "";
        String fragmentId = ref.getFragmentId() != null ? ref.getFragmentId() : "";
        List<TOCReference> children = ref.getChildren();
        boolean hasTocChildren = children != null && !children.isEmpty();

        String rawHtml = hasTocChildren ? "" : readResourceHtml(res);
        String plainText = hasTocChildren ? "" : extractReadableText(rawHtml, fragmentId);
        String chTitle = ref.getTitle() != null && !ref.getTitle().trim().isEmpty()
                ? ref.getTitle().trim()
                : "Chapter " + chIndex;

        Chapter chapter = new Chapter(
                0, 0, 0, chIndex, level, sortOrder,
                chTitle, "", href, fragmentId, "",
                plainText, "", 0L, 0L, 0, false
        );
        if (hasTocChildren) {
            for (int i = 0; i < children.size(); i++) {
                chapter.addChild(convertTocReference(children.get(i), level + 1, i, indexCounter));
            }
        } else if (fragmentId.isEmpty() && !rawHtml.isEmpty()) {
            attachSectionsAndClearContainerText(chapter, rawHtml, href, level, indexCounter);
        }
        return chapter;
    }

    private static void attachSectionsAndClearContainerText(
            @NonNull Chapter chapter,
            @NonNull String rawHtml,
            @NonNull String href,
            int level,
            @NonNull int[] indexCounter
    ) {
        attachHtmlSectionsIfPresent(chapter, rawHtml, href, level, indexCounter);
        if (!chapter.getChildren().isEmpty()) {
            chapter.setContentText("");
        }
    }

    @NonNull
    private static Chapter convertHtmlSectionElement(
            @NonNull Element sectionEl,
            @NonNull String href,
            int level,
            int sortOrder,
            @NonNull int[] indexCounter
    ) {
        int chIndex = indexCounter[0]++;
        String title = resolveSectionTitle(sectionEl, chIndex);
        String fragmentId = sectionEl.id();
        List<Element> nestedSections = directSectionElements(sectionEl);
        String text = nestedSections.isEmpty() ? extractReadableText(sectionEl.outerHtml(), null) : "";
        Chapter node = new Chapter(
                0, 0, 0, chIndex, level, sortOrder,
                title, "", href, fragmentId, "",
                text, "", 0L, 0L, 0, false
        );
        for (int i = 0; i < nestedSections.size(); i++) {
            node.addChild(convertHtmlSectionElement(nestedSections.get(i), href, level + 1, i, indexCounter));
        }
        return node;
    }

    @NonNull
    private static List<Element> directSectionElements(@NonNull Element container) {
        List<Element> result = new ArrayList<>();
        collectTopLevelSections(container, result);
        return result;
    }

    private static void collectTopLevelSections(@NonNull Element element, @NonNull List<Element> out) {
        for (Element child : element.children()) {
            if ("section".equalsIgnoreCase(child.tagName())) {
                out.add(child);
            } else {
                collectTopLevelSections(child, out);
            }
        }
    }

    @NonNull
    private static String resolveSectionTitle(@NonNull Element sectionEl, int fallbackIndex) {
        Element heading = sectionEl.selectFirst("h1, h2, h3, h4, h5, h6");
        if (heading != null && !heading.text().trim().isEmpty()) {
            return heading.text().trim();
        }
        String aria = sectionEl.attr("aria-label").trim();
        if (!aria.isEmpty()) {
            return aria;
        }
        String titleAttr = sectionEl.attr("title").trim();
        return !titleAttr.isEmpty() ? titleAttr : ("Section " + fallbackIndex);
    }

    @Nullable
    private static String extractScopedFragmentHtml(@NonNull Document doc, @Nullable String fragmentId) {
        if (fragmentId == null || fragmentId.trim().isEmpty()) {
            return null;
        }
        Element target = doc.getElementById(fragmentId.trim());
        if (target == null) {
            return null;
        }
        String tag = target.tagName().toLowerCase(Locale.ROOT);
        if (!tag.matches("h[1-6]|a|span")) {
            return target.outerHtml();
        }
        StringBuilder sb = new StringBuilder(target.outerHtml());
        Element sibling = target.nextElementSibling();
        while (sibling != null) {
            String sibTag = sibling.tagName().toLowerCase(Locale.ROOT);
            if (!sibling.id().isEmpty() && sibTag.matches("h[1-6]|section|div|article")) {
                break;
            }
            if (tag.matches("h[1-6]") && sibTag.matches("h[1-6]") && sibTag.charAt(1) <= tag.charAt(1)) {
                break;
            }
            sb.append(sibling.outerHtml());
            sibling = sibling.nextElementSibling();
        }
        return sb.toString();
    }

    @NonNull
    private static String collapseWhitespaceLines(@NonNull String rawText) {
        String[] lines = rawText.split("\n");
        StringBuilder cleaned = new StringBuilder(rawText.length());
        int consecutiveEmpty = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                consecutiveEmpty++;
                if (cleaned.length() > 0 && consecutiveEmpty == 1) {
                    cleaned.append("\n\n");
                }
            } else {
                if (cleaned.length() > 0 && consecutiveEmpty == 0) {
                    cleaned.append('\n');
                }
                cleaned.append(trimmed);
                consecutiveEmpty = 0;
            }
        }
        return cleaned.toString().trim();
    }

    @NonNull
    private static String readResourceHtml(@Nullable Resource res) {
        if (res == null) {
            return "";
        }
        try {
            byte[] data = res.getData();
            return data != null ? new String(data, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
