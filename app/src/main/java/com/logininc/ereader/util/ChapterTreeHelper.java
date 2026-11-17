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
}
