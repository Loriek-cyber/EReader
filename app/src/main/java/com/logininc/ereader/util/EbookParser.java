package com.logininc.ereader.util;

import java.io.IOException;
import java.io.InputStream;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.epub.EpubReader;

/**
 * Parses EPUB documents via epub4j and converts them into the project's
 * {@link com.logininc.ereader.model.Book} and hierarchical
 * {@link com.logininc.ereader.model.Chapter} models, with SQLite persistence helpers.
 */
public class EbookParser {

    public static Book parse(InputStream is) throws RuntimeException {
        try {
            if (is == null) {
                throw new IllegalArgumentException("Errore con le risorse");
            }
            return new EpubReader().readEpub(is);
        } catch (IOException e) {
            throw new RuntimeException(e+"Errore nella lettura del libro");
        }
    }
}
