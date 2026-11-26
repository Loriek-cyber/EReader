package com.logininc.ereader.util;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.TitleDao;

import java.util.Collections;

/**
 * Inspects PDF documents via {@link PdfRenderer} and imports them into SQLite.
 */
public final class PdfImporter {

    private static final String TAG = "PdfImporter";

    private PdfImporter() {
    }

    @Nullable
    public static Book importPdf(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull String displayName,
            @NonNull TitleDao titleDao
    ) {
        int pageCount = 1;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    pageCount = Math.max(1, renderer.getPageCount());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inspect PDF page count for " + uri, e);
        }

        String titleText = FileScanner.stripExtension(displayName);
        Book book = new Book(0, titleText, 1, uri);
        book.setTotalPages(pageCount);
        book.setChapters(Collections.singletonList(new Chapter(
                0, 0, 0, 1, 0, 0,
                titleText, "", "", "", pageCount + " p.",
                "", "", 0L, 0L, 0, false
        )));
        return titleDao.saveBookWithChapters(book) != -1 ? book : null;
    }
}
