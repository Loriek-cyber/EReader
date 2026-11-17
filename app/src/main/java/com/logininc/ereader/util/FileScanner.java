package com.logininc.ereader.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.persistence.ChapterDao;
import com.logininc.ereader.persistence.TitleDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans SAF folders or selected document URIs, parses real book/audiobook metadata and chapters,
 * and persists them into SQLite without creating synthetic/fake data.
 */
public final class FileScanner {
}
