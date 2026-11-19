package com.logininc.ereader.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.persistence.DatabaseContract.ChapterEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAO for persisting and querying {@link Chapter} rows (including hierarchical sections/subsections)
 * in SQLite.
 */
public class ChapterDao {

    private static final String ORDER_BY_READING =
            ChapterEntry.COLUMN_CHAPTER_INDEX + " ASC, "
                    + ChapterEntry.COLUMN_LEVEL + " ASC, "
                    + ChapterEntry.COLUMN_SORT_ORDER + " ASC";

    private static final String ORDER_BY_SIBLING =
            ChapterEntry.COLUMN_SORT_ORDER + " ASC, "
                    + ChapterEntry.COLUMN_CHAPTER_INDEX + " ASC";

    private static final String[] SUMMARY_COLUMNS = {
            ChapterEntry._ID,
            ChapterEntry.COLUMN_TITLE_ID,
            ChapterEntry.COLUMN_PARENT_CHAPTER_ID,
            ChapterEntry.COLUMN_CHAPTER_INDEX,
            ChapterEntry.COLUMN_LEVEL,
            ChapterEntry.COLUMN_SORT_ORDER,
            ChapterEntry.COLUMN_CHAPTER_TITLE,
            ChapterEntry.COLUMN_SUBTITLE,
            ChapterEntry.COLUMN_HREF,
            ChapterEntry.COLUMN_FRAGMENT_ID,
            ChapterEntry.COLUMN_PAGE_OR_DURATION,
            ChapterEntry.COLUMN_AUDIO_START_MS,
            ChapterEntry.COLUMN_AUDIO_DURATION_MS,
            ChapterEntry.COLUMN_PROGRESS_PERCENT,
            ChapterEntry.COLUMN_IS_COMPLETED
    };

    private final EReaderDbHelper dbHelper;

    public ChapterDao(@NonNull Context context) {
        this(EReaderDbHelper.getInstance(context));
    }

    public ChapterDao(@NonNull EReaderDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Replaces all chapters (and any nested child sections/subsections) for {@code titleId}
     * inside a transaction.
     */
    public void replaceChapters(int titleId, @NonNull List<Chapter> chapters) {
        replaceChapterTree(titleId, chapters);
    }

    /**
     * Replaces the entire hierarchical chapter/section tree for {@code titleId} inside a transaction.
     *
     * @return total number of persisted chapter/section nodes.
     */
    public int replaceChapterTree(int titleId, @NonNull List<Chapter> rootChapters) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int inserted = replaceChapterTree(db, titleId, rootChapters);
            db.setTransactionSuccessful();
            return inserted;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Package-private variant allowing {@link TitleDao} to persist chapters within an existing
     * transaction.
     */
    int replaceChapterTree(
            @NonNull SQLiteDatabase db,
            int titleId,
            @NonNull List<Chapter> rootChapters
    ) {
        db.delete(
                ChapterEntry.TABLE_NAME,
                ChapterEntry.COLUMN_TITLE_ID + " = ?",
                new String[]{String.valueOf(titleId)}
        );
        if (rootChapters.isEmpty()) {
            return 0;
        }
        boolean preserveExplicitIndices = hasUniquePositiveIndices(rootChapters);
        int[] nextIndex = new int[]{1};
        return insertNodesRecursive(
                db,
                titleId,
                0,
                0,
                rootChapters,
                preserveExplicitIndices,
                nextIndex
        );
    }

    private boolean hasUniquePositiveIndices(@NonNull List<Chapter> rootChapters) {
        List<Chapter> flat = Chapter.flattenTree(rootChapters);
        Set<Integer> seen = new HashSet<>(flat.size());
        for (Chapter ch : flat) {
            int idx = ch.getChapterIndex();
            if (idx < 1 || !seen.add(idx)) {
                return false;
            }
        }
        return !flat.isEmpty();
    }

    private int insertNodesRecursive(
            @NonNull SQLiteDatabase db,
            int titleId,
            int parentChapterId,
            int depth,
            @NonNull List<Chapter> nodes,
            boolean preserveExplicitIndices,
            @NonNull int[] nextIndex
    ) {
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Chapter node = nodes.get(i);
            if (node == null) {
                continue;
            }
            int effectiveLevel = parentChapterId > 0 ? depth : Math.max(depth, node.getLevel());
            node.setTitleId(titleId);
            node.setParentChapterId(parentChapterId);
            node.setLevel(effectiveLevel);
            node.setSortOrder(i);
            if (!preserveExplicitIndices) {
                node.setChapterIndex(nextIndex[0]++);
            }

            long rowId = db.insert(ChapterEntry.TABLE_NAME, null, toContentValues(node));
            if (rowId != -1) {
                node.setId((int) rowId);
                count++;
                if (node.hasChildren()) {
                    count += insertNodesRecursive(
                            db,
                            titleId,
                            (int) rowId,
                            effectiveLevel + 1,
                            node.getChildren(),
                            preserveExplicitIndices,
                            nextIndex
                    );
                }
            }
        }
        return count;
    }

    /**
     * Returns all chapters and nested sections for {@code titleId} as a flat list in reading order,
     * omitting heavy {@code content_text} and {@code raw_html} payloads to prevent CursorWindow OOM.
     */
    @NonNull
    public List<Chapter> getChapterSummariesForTitle(int titleId) {
        return queryChapters(
                SUMMARY_COLUMNS,
                ChapterEntry.COLUMN_TITLE_ID + " = ?",
                new String[]{String.valueOf(titleId)},
                ORDER_BY_READING
        );
    }

    /**
     * Reconstructs and returns the lightweight hierarchical chapter/section tree (omitting body text)
     * for {@code titleId}.
     */
    @NonNull
    public List<Chapter> getChapterSummaryTreeForTitle(int titleId) {
        return buildTreeFromFlat(getChapterSummariesForTitle(titleId));
    }

    @NonNull
    public static List<Chapter> buildTreeFromFlat(@NonNull List<Chapter> flat) {
        Map<Integer, Chapter> byId = new HashMap<>(flat.size());
        for (Chapter ch : flat) {
            byId.put(ch.getId(), ch);
        }

        List<Chapter> roots = new ArrayList<>();
        for (Chapter ch : flat) {
            int parentId = ch.getParentChapterId();
            Chapter parent = parentId > 0 ? byId.get(parentId) : null;
            if (parent != null) {
                parent.addChild(ch);
            } else {
                roots.add(ch);
            }
        }
        return roots;
    }

    /**
     * Returns all chapters and nested sections for {@code titleId} as a flat list in reading order.
     */
    @NonNull
    public List<Chapter> getChaptersForTitle(int titleId) {
        return queryChapters(
                null,
                ChapterEntry.COLUMN_TITLE_ID + " = ?",
                new String[]{String.valueOf(titleId)},
                ORDER_BY_READING
        );
    }

    /**
     * Reconstructs and returns the hierarchical chapter/section tree (root chapters with populated
     * {@link Chapter#getChildren()} lists) for {@code titleId}.
     */
    @NonNull
    public List<Chapter> getChapterTreeForTitle(int titleId) {
        return buildTreeFromFlat(getChaptersForTitle(titleId));
    }

    /**
     * Returns only top-level chapters (without populated children) for {@code titleId}.
     */
    @NonNull
    public List<Chapter> getRootChaptersForTitle(int titleId) {
        return queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND "
                        + ChapterEntry.COLUMN_PARENT_CHAPTER_ID + " IS NULL",
                new String[]{String.valueOf(titleId)},
                ORDER_BY_SIBLING
        );
    }

    /**
     * Returns direct child sections/subsections of {@code parentChapterId} in sibling order.
     */
    @NonNull
    public List<Chapter> getChildChapters(int parentChapterId) {
        return queryChapters(
                ChapterEntry.COLUMN_PARENT_CHAPTER_ID + " = ?",
                new String[]{String.valueOf(parentChapterId)},
                ORDER_BY_SIBLING
        );
    }

    @NonNull
    public List<Chapter> searchChapters(int titleId, @NonNull String query) {
        String like = "%" + query + "%";
        return queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND ("
                        + ChapterEntry.COLUMN_CHAPTER_TITLE + " LIKE ? OR "
                        + ChapterEntry.COLUMN_SUBTITLE + " LIKE ?)",
                new String[]{String.valueOf(titleId), like, like},
                ORDER_BY_READING
        );
    }

    @Nullable
    public Chapter getChapter(int titleId, int chapterIndex) {
        List<Chapter> rows = queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_CHAPTER_INDEX + " = ?",
                new String[]{String.valueOf(titleId), String.valueOf(chapterIndex)},
                ORDER_BY_READING
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Finds a chapter or section matching {@code href} (and optional {@code fragmentId}).
     */
    @Nullable
    public Chapter getChapterByHref(int titleId, @NonNull String href, @Nullable String fragmentId) {
        String cleanFragment = fragmentId != null ? fragmentId : "";
        if (!cleanFragment.isEmpty()) {
            List<Chapter> exact = queryChapters(
                    ChapterEntry.COLUMN_TITLE_ID + " = ? AND "
                            + ChapterEntry.COLUMN_HREF + " = ? AND "
                            + ChapterEntry.COLUMN_FRAGMENT_ID + " = ?",
                    new String[]{String.valueOf(titleId), href, cleanFragment},
                    ORDER_BY_READING
            );
            return exact.isEmpty() ? null : exact.get(0);
        }

        List<Chapter> noFragment = queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND "
                        + ChapterEntry.COLUMN_HREF + " = ? AND "
                        + ChapterEntry.COLUMN_FRAGMENT_ID + " = ''",
                new String[]{String.valueOf(titleId), href},
                ORDER_BY_READING
        );
        if (!noFragment.isEmpty()) {
            return noFragment.get(0);
        }
        List<Chapter> anyFragment = queryChapters(
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_HREF + " = ?",
                new String[]{String.valueOf(titleId), href},
                ORDER_BY_READING
        );
        return anyFragment.isEmpty() ? null : anyFragment.get(0);
    }

    public void updateChapterProgress(int titleId, int chapterIndex, int progressPercent, boolean completed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(ChapterEntry.COLUMN_PROGRESS_PERCENT, Math.max(0, Math.min(100, progressPercent)));
            values.put(ChapterEntry.COLUMN_IS_COMPLETED, completed ? 1 : 0);
            db.update(
                    ChapterEntry.TABLE_NAME,
                    values,
                    ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_CHAPTER_INDEX + " = ?",
                    new String[]{String.valueOf(titleId), String.valueOf(chapterIndex)}
            );

            int parentId = queryParentChapterId(db, titleId, chapterIndex);
            while (parentId > 0) {
                parentId = rollupParentNode(db, parentId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private int queryParentChapterId(@NonNull SQLiteDatabase db, int titleId, int chapterIndex) {
        try (Cursor c = db.query(
                ChapterEntry.TABLE_NAME,
                new String[]{ChapterEntry.COLUMN_PARENT_CHAPTER_ID},
                ChapterEntry.COLUMN_TITLE_ID + " = ? AND " + ChapterEntry.COLUMN_CHAPTER_INDEX + " = ?",
                new String[]{String.valueOf(titleId), String.valueOf(chapterIndex)},
                null, null, null
        )) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    /**
     * Recomputes a parent chapter's progress and completion from its direct children,
     * returning the grandparent ID (or 0 if root).
     */
    private int rollupParentNode(@NonNull SQLiteDatabase db, int parentId) {
        int childCount = 0;
        int completedCount = 0;
        int sumProgress = 0;

        try (Cursor c = db.query(
                ChapterEntry.TABLE_NAME,
                new String[]{ChapterEntry.COLUMN_PROGRESS_PERCENT, ChapterEntry.COLUMN_IS_COMPLETED},
                ChapterEntry.COLUMN_PARENT_CHAPTER_ID + " = ?",
                new String[]{String.valueOf(parentId)},
                null, null, null
        )) {
            while (c.moveToNext()) {
                childCount++;
                int pct = c.getInt(0);
                boolean done = c.getInt(1) == 1;
                sumProgress += done ? 100 : pct;
                if (done) {
                    completedCount++;
                }
            }
        }

        if (childCount > 0) {
            boolean allCompleted = completedCount == childCount;
            int avgProgress = allCompleted ? 100 : (sumProgress / childCount);
            ContentValues parentValues = new ContentValues();
            parentValues.put(ChapterEntry.COLUMN_PROGRESS_PERCENT, Math.max(0, Math.min(100, avgProgress)));
            parentValues.put(ChapterEntry.COLUMN_IS_COMPLETED, allCompleted ? 1 : 0);
            db.update(
                    ChapterEntry.TABLE_NAME,
                    parentValues,
                    ChapterEntry._ID + " = ?",
                    new String[]{String.valueOf(parentId)}
            );
        }

        try (Cursor c = db.query(
                ChapterEntry.TABLE_NAME,
                new String[]{ChapterEntry.COLUMN_PARENT_CHAPTER_ID},
                ChapterEntry._ID + " = ?",
                new String[]{String.valueOf(parentId)},
                null, null, null
        )) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    @NonNull
    private List<Chapter> queryChapters(
            @NonNull String selection,
            @NonNull String[] selectionArgs,
            @NonNull String orderBy
    ) {
        return queryChapters(null, selection, selectionArgs, orderBy);
    }

    @NonNull
    private List<Chapter> queryChapters(
            @Nullable String[] columns,
            @NonNull String selection,
            @NonNull String[] selectionArgs,
            @NonNull String orderBy
    ) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Chapter> results = new ArrayList<>();
        try (Cursor cursor = db.query(
                ChapterEntry.TABLE_NAME,
                columns,
                selection,
                selectionArgs,
                null,
                null,
                orderBy
        )) {
            while (cursor.moveToNext()) {
                results.add(fromCursor(cursor));
            }
        }
        return results;
    }

    @NonNull
    private ContentValues toContentValues(@NonNull Chapter chapter) {
        ContentValues values = new ContentValues();
        values.put(ChapterEntry.COLUMN_TITLE_ID, chapter.getTitleId());
        if (chapter.getParentChapterId() > 0) {
            values.put(ChapterEntry.COLUMN_PARENT_CHAPTER_ID, chapter.getParentChapterId());
        } else {
            values.putNull(ChapterEntry.COLUMN_PARENT_CHAPTER_ID);
        }
        values.put(ChapterEntry.COLUMN_CHAPTER_INDEX, chapter.getChapterIndex());
        values.put(ChapterEntry.COLUMN_LEVEL, chapter.getLevel());
        values.put(ChapterEntry.COLUMN_SORT_ORDER, chapter.getSortOrder());
        values.put(ChapterEntry.COLUMN_CHAPTER_TITLE, chapter.getChapterTitle());
        values.put(ChapterEntry.COLUMN_SUBTITLE, chapter.getSubtitle());
        values.put(ChapterEntry.COLUMN_HREF, chapter.getHref());
        values.put(ChapterEntry.COLUMN_FRAGMENT_ID, chapter.getFragmentId());
        values.put(ChapterEntry.COLUMN_PAGE_OR_DURATION, chapter.getPageOrDurationLabel());
        values.put(ChapterEntry.COLUMN_CONTENT_TEXT, chapter.getContentText());
        values.put(ChapterEntry.COLUMN_RAW_HTML, chapter.getRawHtml());
        values.put(ChapterEntry.COLUMN_AUDIO_START_MS, chapter.getAudioStartMs());
        values.put(ChapterEntry.COLUMN_AUDIO_DURATION_MS, chapter.getAudioDurationMs());
        values.put(ChapterEntry.COLUMN_PROGRESS_PERCENT, chapter.getProgressPercent());
        values.put(ChapterEntry.COLUMN_IS_COMPLETED, chapter.isCompleted() ? 1 : 0);
        return values;
    }

    @NonNull
    private Chapter fromCursor(@NonNull Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry._ID));
        int titleId = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_TITLE_ID));
        int parentCol = cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_PARENT_CHAPTER_ID);
        int parentChapterId = cursor.isNull(parentCol) ? 0 : cursor.getInt(parentCol);
        int index = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_CHAPTER_INDEX));
        int level = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_LEVEL));
        int sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_SORT_ORDER));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_CHAPTER_TITLE));
        String subtitle = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_SUBTITLE));
        String href = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_HREF));
        String fragmentId = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_FRAGMENT_ID));
        String label = cursor.getString(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_PAGE_OR_DURATION));
        int contentCol = cursor.getColumnIndex(ChapterEntry.COLUMN_CONTENT_TEXT);
        String contentText = contentCol >= 0 && !cursor.isNull(contentCol) ? cursor.getString(contentCol) : "";
        int rawHtmlCol = cursor.getColumnIndex(ChapterEntry.COLUMN_RAW_HTML);
        String rawHtml = rawHtmlCol >= 0 && !cursor.isNull(rawHtmlCol) ? cursor.getString(rawHtmlCol) : "";
        long startMs = cursor.getLong(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_AUDIO_START_MS));
        long durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_AUDIO_DURATION_MS));
        int progress = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_PROGRESS_PERCENT));
        boolean completed = cursor.getInt(cursor.getColumnIndexOrThrow(ChapterEntry.COLUMN_IS_COMPLETED)) == 1;

        return new Chapter(
                id,
                titleId,
                parentChapterId,
                index,
                level,
                sortOrder,
                title,
                subtitle,
                href,
                fragmentId,
                label,
                contentText,
                rawHtml,
                startMs,
                durationMs,
                progress,
                completed
        );
    }
}
