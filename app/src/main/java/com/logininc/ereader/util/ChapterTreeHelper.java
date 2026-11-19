package com.logininc.ereader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.model.Chapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Helper for building, filtering, and inspecting hierarchical {@link Chapter} trees for display.
 */
public final class ChapterTreeHelper {

    private ChapterTreeHelper() {
    }

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

    @NonNull
    public static List<TreeNode> buildDisplayTree(@NonNull List<Chapter> roots, @Nullable String query) {
        String normalized = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        boolean filtering = !normalized.isEmpty();
        List<TreeNode> result = new ArrayList<>();
        for (Chapter root : roots) {
            TreeNode node = buildNode(root, normalized, filtering);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    @Nullable
    private static TreeNode buildNode(@NonNull Chapter chapter, @NonNull String query, boolean filtering) {
        List<TreeNode> filteredChildren = new ArrayList<>();
        for (Chapter child : chapter.getChildren()) {
            TreeNode childNode = buildNode(child, query, filtering);
            if (childNode != null) {
                filteredChildren.add(childNode);
            }
        }

        if (!filtering) {
            return new TreeNode(chapter, filteredChildren, false);
        }

        boolean selfMatches = chapter.getChapterTitle().toLowerCase(Locale.ROOT).contains(query)
                || chapter.getSubtitle().toLowerCase(Locale.ROOT).contains(query);
        if (selfMatches || !filteredChildren.isEmpty()) {
            return new TreeNode(chapter, filteredChildren, !filteredChildren.isEmpty());
        }
        return null;
    }

    @NonNull
    public static Set<Integer> collectAncestorIndices(@NonNull List<Chapter> roots, int targetChapterIndex) {
        Set<Integer> ancestors = new HashSet<>();
        for (Chapter root : roots) {
            findAncestors(root, targetChapterIndex, ancestors);
        }
        return ancestors;
    }

    private static boolean findAncestors(
            @NonNull Chapter node,
            int targetChapterIndex,
            @NonNull Set<Integer> ancestors
    ) {
        if (node.getChapterIndex() == targetChapterIndex) {
            return true;
        }
        for (Chapter child : node.getChildren()) {
            if (findAncestors(child, targetChapterIndex, ancestors)) {
                ancestors.add(node.getChapterIndex());
                return true;
            }
        }
        return false;
    }

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
            if (!node.getChildren().isEmpty()) {
                out.add(node.getChapterIndex());
                collectExpandableRecursive(node.getChildren(), out);
            }
        }
    }

    public static boolean hasNestedChapters(@NonNull List<Chapter> roots) {
        for (Chapter root : roots) {
            if (!root.getChildren().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static int countNestedNodes(@NonNull List<Chapter> roots) {
        int count = 0;
        for (Chapter root : roots) {
            count += countDescendants(root);
        }
        return count;
    }

    private static int countDescendants(@NonNull Chapter node) {
        int count = 0;
        for (Chapter child : node.getChildren()) {
            count += 1 + countDescendants(child);
        }
        return count;
    }

    /**
     * Collects all leaf chapters (nodes without children) in pre-order reading sequence.
     */
    @NonNull
    public static List<Chapter> collectLeafChapters(@NonNull List<Chapter> roots) {
        List<Chapter> leaves = new ArrayList<>();
        collectLeavesRecursive(roots, leaves);
        return leaves;
    }

    private static void collectLeavesRecursive(@NonNull List<Chapter> nodes, @NonNull List<Chapter> out) {
        for (Chapter node : nodes) {
            if (node.getChildren().isEmpty()) {
                out.add(node);
            } else {
                collectLeavesRecursive(node.getChildren(), out);
            }
        }
    }

    /**
     * If {@code targetChapterIndex} is a leaf node, returns it; if it is a container section with
     * children, descends to its first leaf descendant. Falls back to the first leaf in the tree.
     */
    public static int resolveNearestLeafIndex(@NonNull List<Chapter> roots, int targetChapterIndex) {
        if (roots.isEmpty()) {
            return Math.max(1, targetChapterIndex);
        }
        Chapter matched = findByIndex(roots, targetChapterIndex);
        if (matched != null) {
            Chapter leaf = firstLeafOf(matched);
            if (leaf != null) {
                return leaf.getChapterIndex();
            }
        }
        List<Chapter> leaves = collectLeafChapters(roots);
        return leaves.isEmpty() ? Math.max(1, targetChapterIndex) : leaves.get(0).getChapterIndex();
    }

    /**
     * Returns the {@code chapterIndex} of the next leaf chapter after {@code currentChapterIndex},
     * or {@code -1} if already at or past the last leaf.
     */
    public static int findNextLeafIndex(@NonNull List<Chapter> roots, int currentChapterIndex) {
        List<Chapter> leaves = collectLeafChapters(roots);
        if (leaves.isEmpty()) {
            return -1;
        }
        int resolvedCurrent = resolveNearestLeafIndex(roots, currentChapterIndex);
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).getChapterIndex() == resolvedCurrent) {
                return (i + 1 < leaves.size()) ? leaves.get(i + 1).getChapterIndex() : -1;
            }
        }
        for (Chapter leaf : leaves) {
            if (leaf.getChapterIndex() > currentChapterIndex) {
                return leaf.getChapterIndex();
            }
        }
        return -1;
    }

    /**
     * Returns the {@code chapterIndex} of the previous leaf chapter before {@code currentChapterIndex},
     * or {@code -1} if already at or before the first leaf.
     */
    public static int findPreviousLeafIndex(@NonNull List<Chapter> roots, int currentChapterIndex) {
        List<Chapter> leaves = collectLeafChapters(roots);
        if (leaves.isEmpty()) {
            return -1;
        }
        int resolvedCurrent = resolveNearestLeafIndex(roots, currentChapterIndex);
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).getChapterIndex() == resolvedCurrent) {
                return (i - 1 >= 0) ? leaves.get(i - 1).getChapterIndex() : -1;
            }
        }
        for (int i = leaves.size() - 1; i >= 0; i--) {
            if (leaves.get(i).getChapterIndex() < currentChapterIndex) {
                return leaves.get(i).getChapterIndex();
            }
        }
        return -1;
    }

    @Nullable
    private static Chapter findByIndex(@NonNull List<Chapter> nodes, int chapterIndex) {
        for (Chapter node : nodes) {
            if (node.getChapterIndex() == chapterIndex) {
                return node;
            }
            Chapter found = findByIndex(node.getChildren(), chapterIndex);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static Chapter firstLeafOf(@NonNull Chapter node) {
        if (node.getChildren().isEmpty()) {
            return node;
        }
        for (Chapter child : node.getChildren()) {
            Chapter leaf = firstLeafOf(child);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }
}
