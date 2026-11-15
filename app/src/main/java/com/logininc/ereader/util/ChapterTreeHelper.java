package com.logininc.ereader.util;

import androidx.annotation.NonNull;

import com.logininc.ereader.model.Chapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure helper utilities for traversing, filtering, and managing expand/collapse state
 * on hierarchical {@link Chapter} trees (chapters, sections, and recursive subsections).
 */
public final class ChapterTreeHelper {

    public static final class TreeNode {
        @NonNull
        public final Chapter chapter;
        @NonNull
        public final List<TreeNode> children;
        public final boolean forceExpand;

        public TreeNode(@NonNull Chapter chapter, @NonNull List<TreeNode> children, boolean forceExpand) {
            this.chapter = chapter;
            this.children = children;
            this.forceExpand = forceExpand;
        }
    }

    private ChapterTreeHelper() {
    }

    /**
     * Builds a display tree from {@code roots}. When {@code query} is non-empty, only nodes
     * matching the query or having matching descendants are retained, and ancestor branches
     * leading to matching descendants are marked {@code forceExpand = true}.
     */
    @NonNull
    public static List<TreeNode> buildDisplayTree(@NonNull List<Chapter> roots, @NonNull String query) {
        String lower = query.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return wrapUnfiltered(roots);
        }
        return filterNodes(roots, lower);
    }

    @NonNull
    private static List<TreeNode> wrapUnfiltered(@NonNull List<Chapter> chapters) {
        List<TreeNode> nodes = new ArrayList<>(chapters.size());
        for (Chapter ch : chapters) {
            if (ch == null) {
                continue;
            }
            nodes.add(new TreeNode(ch, wrapUnfiltered(ch.getChildren()), false));
        }
        return nodes;
    }

    @NonNull
    private static List<TreeNode> filterNodes(@NonNull List<Chapter> chapters, @NonNull String lowerQuery) {
        List<TreeNode> result = new ArrayList<>();
        for (Chapter ch : chapters) {
            if (ch == null) {
                continue;
            }
            boolean selfMatches = matchesChapter(ch, lowerQuery);
            List<TreeNode> matchingChildren = filterNodes(ch.getChildren(), lowerQuery);
            if (!matchingChildren.isEmpty()) {
                result.add(new TreeNode(ch, matchingChildren, true));
            } else if (selfMatches) {
                result.add(new TreeNode(ch, wrapUnfiltered(ch.getChildren()), false));
            }
        }
        return result;
    }

    private static boolean matchesChapter(@NonNull Chapter chapter, @NonNull String lowerQuery) {
        return chapter.getChapterTitle().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || chapter.getSubtitle().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /**
     * Returns {@code true} if any root chapter contains nested sections/subsections.
     */
    public static boolean hasNestedChapters(@NonNull List<Chapter> roots) {
        for (Chapter root : roots) {
            if (root != null && root.hasChildren()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts all nested descendant sections/subsections below root chapters.
     */
    public static int countNestedNodes(@NonNull List<Chapter> roots) {
        int count = 0;
        for (Chapter root : roots) {
            if (root != null) {
                count += countDescendants(root);
            }
        }
        return count;
    }

    private static int countDescendants(@NonNull Chapter node) {
        int count = 0;
        for (Chapter child : node.getChildren()) {
            if (child != null) {
                count += 1 + countDescendants(child);
            }
        }
        return count;
    }

    /**
     * Collects the {@code chapterIndex} values of all ancestor nodes on the path to
     * {@code targetChapterIndex} so the active chapter/section is visible when opened.
     */
    @NonNull
    public static Set<Integer> collectAncestorIndices(@NonNull List<Chapter> roots, int targetChapterIndex) {
        Set<Integer> ancestors = new HashSet<>();
        findAncestorPath(roots, targetChapterIndex, ancestors);
        return ancestors;
    }

    private static boolean findAncestorPath(
            @NonNull List<Chapter> nodes,
            int targetChapterIndex,
            @NonNull Set<Integer> ancestorsOut
    ) {
        for (Chapter node : nodes) {
            if (node == null) {
                continue;
            }
            if (node.getChapterIndex() == targetChapterIndex) {
                return true;
            }
            if (node.hasChildren() && findAncestorPath(node.getChildren(), targetChapterIndex, ancestorsOut)) {
                ancestorsOut.add(node.getChapterIndex());
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the {@code chapterIndex} values of all nodes in the tree that have children.
     */
    @NonNull
    public static Set<Integer> collectExpandableIndices(@NonNull List<Chapter> roots) {
        Set<Integer> expandable = new HashSet<>();
        collectExpandableRecursive(roots, expandable);
        return expandable;
    }

    private static void collectExpandableRecursive(
            @NonNull List<Chapter> nodes,
            @NonNull Set<Integer> out
    ) {
        for (Chapter node : nodes) {
            if (node != null && node.hasChildren()) {
                out.add(node.getChapterIndex());
                collectExpandableRecursive(node.getChildren(), out);
            }
        }
    }
}
