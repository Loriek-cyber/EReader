package com.logininc.ereader.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.logininc.ereader.R;
import com.logininc.ereader.model.AudioBook;
import com.logininc.ereader.model.Chapter;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.util.ChapterTreeHelper;
import com.logininc.ereader.util.FileScanner;
import com.logininc.ereader.util.UiSessionHelper;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dedicated recursive view for rendering an audiobook's hierarchical chapter/section tree
 * with integrated recursive completion/progress status.
 */
public class RecursiveAudioChapterTreeView extends LinearLayout {

    public interface OnChapterClickListener {
        void onChapterClick(@NonNull Chapter chapter);
    }

    public interface PlayingChapterChecker {
        boolean isPlayingChapter(int chapterIndex);
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

    public RecursiveAudioChapterTreeView(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    public RecursiveAudioChapterTreeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    public RecursiveAudioChapterTreeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
    }

    public void renderTree(
            @NonNull List<ChapterTreeHelper.TreeNode> nodes,
            @NonNull AudioBook audioBook,
            @NonNull Set<Integer> expandedIndices,
            @NonNull Set<Integer> allExpandableIndices,
            boolean filtering,
            @NonNull PlayingChapterChecker playingChecker,
            @Nullable Runnable onExpansionChanged,
            @NonNull OnChapterClickListener clickListener
    ) {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        renderNodesRecursive(
                nodes,
                this,
                0,
                audioBook,
                expandedIndices,
                allExpandableIndices,
                filtering,
                inflater,
                playingChecker,
                onExpansionChanged,
                clickListener
        );
    }

    private void renderNodesRecursive(
            @NonNull List<ChapterTreeHelper.TreeNode> nodes,
            @NonNull LinearLayout container,
            int depth,
            @NonNull AudioBook audioBook,
            @NonNull Set<Integer> expandedIndices,
            @NonNull Set<Integer> allExpandableIndices,
            boolean filtering,
            @NonNull LayoutInflater inflater,
            @NonNull PlayingChapterChecker playingChecker,
            @Nullable Runnable onExpansionChanged,
            @NonNull OnChapterClickListener clickListener
    ) {
        float density = getResources().getDisplayMetrics().density;
        boolean bookInProgress = Title.STATUS_IN_PROGRESS.equals(audioBook.getStatus());

        for (ChapterTreeHelper.TreeNode node : nodes) {
            Chapter ch = node.chapter;
            View row = inflater.inflate(R.layout.item_chapter_audio, container, false);

            MaterialCardView cardRow = row.findViewById(R.id.cardAudioChapterRow);
            ImageView ivIcon = row.findViewById(R.id.ivAudioChapterIcon);
            TextView tvNum = row.findViewById(R.id.tvAudioChapterNum);
            TextView tvRowTitle = row.findViewById(R.id.tvAudioChapterTitle);
            TextView tvMeta = row.findViewById(R.id.tvAudioChapterMeta);
            ImageButton btnExpand = row.findViewById(R.id.btnAudioChapterExpand);
            ImageButton btnAction = row.findViewById(R.id.btnAudioChapterAction);
            View layoutChildrenWrapper = row.findViewById(R.id.layoutAudioChapterChildrenWrapper);
            View viewTreeGuide = row.findViewById(R.id.viewAudioChapterTreeGuide);
            LinearLayout containerChildren = row.findViewById(R.id.containerAudioChapterChildren);

            if (depth > 0) {
                tvRowTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            }
            tvRowTitle.setText(ch.getChapterTitle());

            String durLabel = !ch.getPageOrDurationLabel().isEmpty()
                    ? ch.getPageOrDurationLabel()
                    : FileScanner.formatDuration(ch.getAudioDurationMs());

            SubtreeStatus status = computeSubtreeStatus(node, audioBook.getCurrentChapter(), bookInProgress);
            boolean isActiveChapter = ch.getChapterIndex() == audioBook.getCurrentChapter() && bookInProgress;

            String statusPart;
            if (status.allCompleted) {
                ivIcon.setVisibility(View.VISIBLE);
                tvNum.setVisibility(View.GONE);
                ivIcon.setImageResource(R.drawable.ic_check_circle);
                statusPart = getContext().getString(R.string.chapter_status_completed);
            } else if (isActiveChapter || status.anyInProgress) {
                ivIcon.setVisibility(View.VISIBLE);
                tvNum.setVisibility(View.GONE);
                ivIcon.setImageResource(R.drawable.ic_equalizer);
                if (isActiveChapter) {
                    cardRow.setStrokeColor(getContext().getColor(R.color.border_active));
                    cardRow.setStrokeWidth(Math.round(1.5f * density));
                }
                statusPart = getContext().getString(R.string.format_percent, status.aggregatedProgress);
            } else {
                ivIcon.setVisibility(View.GONE);
                tvNum.setVisibility(View.VISIBLE);
                tvNum.setText(String.format(Locale.ROOT, "%02d", ch.getChapterIndex()));
                statusPart = getContext().getString(R.string.chapter_status_to_start);
            }

            if (!node.children.isEmpty()) {
                int childCount = node.children.size();
                String sectionsLabel = getResources().getQuantityString(
                        R.plurals.chapter_sections_count, childCount, childCount);
                tvMeta.setText(durLabel + " • " + sectionsLabel + " • " + statusPart);
            } else {
                tvMeta.setText(durLabel + " • " + statusPart);
            }

            boolean isPlayingThis = playingChecker.isPlayingChapter(ch.getChapterIndex());
            btnAction.setImageResource(isPlayingThis ? R.drawable.ic_pause : R.drawable.ic_play_circle);

            View.OnClickListener openListener = v -> clickListener.onChapterClick(ch);
            cardRow.setOnClickListener(openListener);
            btnAction.setOnClickListener(openListener);

            UiSessionHelper.bindRecursiveChapterChildren(
                    node,
                    depth,
                    filtering,
                    expandedIndices,
                    btnExpand,
                    null,
                    layoutChildrenWrapper,
                    viewTreeGuide,
                    containerChildren,
                    onExpansionChanged,
                    (children, childContainer, nextDepth) -> renderNodesRecursive(
                            children,
                            childContainer,
                            nextDepth,
                            audioBook,
                            expandedIndices,
                            allExpandableIndices,
                            filtering,
                            inflater,
                            playingChecker,
                            onExpansionChanged,
                            clickListener
                    )
            );

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
