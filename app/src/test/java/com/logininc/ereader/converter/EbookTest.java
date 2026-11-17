package com.logininc.ereader.converter;

import com.logininc.ereader.util.EbookParser;

import org.junit.Test;

import java.io.InputStream;
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
}
