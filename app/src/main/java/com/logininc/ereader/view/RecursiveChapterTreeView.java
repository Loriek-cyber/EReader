package com.logininc.ereader.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.logininc.ereader.R;
import com.logininc.ereader.model.Book;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.UiSessionHelper;

import java.util.List;
import java.util.Set;

/**
 * Dedicated recursive view for rendering an ebook's hierarchical chapter/section tree
 * with integrated recursive completion/progress status.
 */
public class RecursiveChapterTreeView extends LinearLayout {

    public interface OnChapterClickListener {
        void onChapterClick(@NonNull Chapter chapter);
    }

    private static final class SubtreeStatus {
        final boolean allCompleted;
        final boolean anyInProgress;
        final int aggregatedProgress;

        SubtreeStatus(boolean allCompleted, boolean anyInProgress, int aggregatedProgress) {
            this.allCompleted = allCompleted;
            this.anyInProgress = anyInProgress;
            this.aggregatedProgress = aggregatedProgress;
        }
    }

    public RecursiveChapterTreeView(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    public RecursiveChapterTreeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    public RecursiveChapterTreeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
    }

    public void renderTree(
            @NonNull List<ChapterTreeHelper.TreeNode> nodes,
            @NonNull Book book,
            @NonNull Set<Integer> expandedIndices,
            @NonNull Set<Integer> allExpandableIndices,
            boolean filtering,
            @Nullable Runnable onExpansionChanged,
            @NonNull OnChapterClickListener clickListener
    ) {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        renderNodesRecursive(
                nodes,
                this,
                0,
                book,
                expandedIndices,
                allExpandableIndices,
                filtering,
                inflater,
                onExpansionChanged,
                clickListener
        );
    }

    private void renderNodesRecursive(
            @NonNull List<ChapterTreeHelper.TreeNode> nodes,
            @NonNull LinearLayout container,
            int depth,
            @NonNull Book book,
            @NonNull Set<Integer> expandedIndices,
            @NonNull Set<Integer> allExpandableIndices,
            boolean filtering,
            @NonNull LayoutInflater inflater,
            @Nullable Runnable onExpansionChanged,
            @NonNull OnChapterClickListener clickListener
    ) {
        float density = getResources().getDisplayMetrics().density;
        boolean bookInProgress = Title.STATUS_IN_PROGRESS.equals(book.getStatus());

        for (ChapterTreeHelper.TreeNode node : nodes) {
            Chapter ch = node.chapter;
            View row = inflater.inflate(R.layout.item_chapter_ebook, container, false);

            MaterialCardView cardRow = row.findViewById(R.id.cardChapterRow);
            ImageView ivStatus = row.findViewById(R.id.ivChapterStatus);
            TextView tvTitle = row.findViewById(R.id.tvChapterTitle);
            TextView tvSubtitle = row.findViewById(R.id.tvChapterSubtitle);
            TextView tvPage = row.findViewById(R.id.tvChapterPage);
            ImageButton btnExpand = row.findViewById(R.id.btnChapterExpand);
            ImageView ivChevron = row.findViewById(R.id.ivChapterChevron);
            View layoutChildrenWrapper = row.findViewById(R.id.layoutChapterChildrenWrapper);
            View viewTreeGuide = row.findViewById(R.id.viewChapterTreeGuide);
            LinearLayout containerChildren = row.findViewById(R.id.containerChapterChildren);

            if (depth > 0) {
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                int iconSizePx = Math.round(24f * density);
                ViewGroup.LayoutParams iconLp = ivStatus.getLayoutParams();
                iconLp.width = iconSizePx;
                iconLp.height = iconSizePx;
                ivStatus.setLayoutParams(iconLp);
            }

            SubtreeStatus status = computeSubtreeStatus(node, book.getCurrentChapter(), bookInProgress);

            tvTitle.setText(ch.getChapterTitle());
            String statusLabel = status.allCompleted
                    ? getContext().getString(R.string.chapter_status_completed)
                    : status.aggregatedProgress > 0
                    ? getContext().getString(R.string.format_percent, status.aggregatedProgress)
                    : getContext().getString(R.string.chapter_status_to_start);

            if (!ch.getSubtitle().isEmpty()) {
                tvSubtitle.setText(ch.getSubtitle());
            } else if (!node.children.isEmpty()) {
                int childCount = node.children.size();
                String sectionsLabel = getResources().getQuantityString(
                        R.plurals.chapter_sections_count, childCount, childCount);
                tvSubtitle.setText(sectionsLabel + " • " + statusLabel);
            } else {
                tvSubtitle.setText(statusLabel);
            }
            tvPage.setText(ch.getPageOrDurationLabel());

            boolean isActiveChapter = ch.getChapterIndex() == book.getCurrentChapter() && bookInProgress;
            if (status.allCompleted) {
                ivStatus.setImageResource(R.drawable.ic_check);
            } else if (isActiveChapter || status.anyInProgress) {
                ivStatus.setImageResource(R.drawable.ic_play);
                if (isActiveChapter) {
                    cardRow.setStrokeColor(getContext().getColor(R.color.border_active));
                    cardRow.setStrokeWidth(Math.round(1.5f * density));
                }
            } else {
                ivStatus.setImageResource(R.drawable.ic_radio_unchecked);
            }

            Runnable toggleChildren = UiSessionHelper.bindRecursiveChapterChildren(
                    node,
                    depth,
                    filtering,
                    expandedIndices,
                    btnExpand,
                    ivChevron,
                    layoutChildrenWrapper,
                    viewTreeGuide,
                    containerChildren,
                    onExpansionChanged,
                    (children, childContainer, nextDepth) -> renderNodesRecursive(
                            children,
                            childContainer,
                            nextDepth,
                            book,
                            expandedIndices,
                            allExpandableIndices,
                            filtering,
                            inflater,
                            onExpansionChanged,
                            clickListener
                    )
            );

            if (toggleChildren != null) {
                cardRow.setOnClickListener(v -> toggleChildren.run());
            } else {
                cardRow.setOnClickListener(v -> clickListener.onChapterClick(ch));
            }

            container.addView(row);
        }
    }

    @NonNull
    private static SubtreeStatus computeSubtreeStatus(
            @NonNull ChapterTreeHelper.TreeNode node,
            int activeChapterIndex,
            boolean bookInProgress
    ) {
        Chapter ch = node.chapter;
        if (node.children.isEmpty()) {
            boolean done = ch.isCompleted();
            int pct = done ? 100 : ch.getProgressPercent();
            boolean inProgress = !done && ((bookInProgress && ch.getChapterIndex() == activeChapterIndex) || pct > 0);
            return new SubtreeStatus(done, inProgress, pct);
        }

        boolean allDone = true;
        boolean anyActive = bookInProgress && ch.getChapterIndex() == activeChapterIndex;
        int sumPct = 0;
        for (ChapterTreeHelper.TreeNode child : node.children) {
            SubtreeStatus childStatus = computeSubtreeStatus(child, activeChapterIndex, bookInProgress);
            if (!childStatus.allCompleted) {
                allDone = false;
            }
            if (childStatus.anyInProgress) {
                anyActive = true;
            }
            sumPct += childStatus.aggregatedProgress;
        }
        int avgPct = allDone ? 100 : (sumPct / node.children.size());
        return new SubtreeStatus(allDone, anyActive || (!allDone && avgPct > 0), avgPct);
    }
}
