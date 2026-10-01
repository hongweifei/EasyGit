package org.easygit.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 底部状态栏:仓库/分支/领先落后 | 消息 | 忙碌指示。
 */
public class StatusBar extends HBox {

    private final Label repoLabel = new Label();
    private final Label branchChip = new Label();
    private final Label abChip = new Label();
    private final Label lfsChip = new Label();
    /** 结果消息(常驻到下一次消息):「拉取完成:新增 2 个提交」这类。 */
    private final Label message = new Label();
    /** 忙碌文案(临时):「刷新状态…」这类。单独一个 Label,免得周期性轮询把结果消息冲掉。 */
    private final Label busyText = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label counts = new Label();

    public StatusBar() {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 10, 4, 10));
        setSpacing(10);

        repoLabel.getStyleClass().add("dim");
        branchChip.getStyleClass().addAll("chip", "chip-branch");
        abChip.getStyleClass().add("chip");
        lfsChip.getStyleClass().addAll("chip", "chip-tag");
        lfsChip.setVisible(false);
        lfsChip.setTooltip(new javafx.scene.control.Tooltip("此仓库使用 Git LFS,数字为 LFS 跟踪的文件数"));
        counts.getStyleClass().add("dim");
        busyText.getStyleClass().add("dim");
        // 稳定锚点:位置会随忙碌状态变化,探针/样式按 id 找
        message.setId("status-message");
        busyText.setId("status-busy");

        progress.setPrefSize(16, 16);
        progress.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        getChildren().addAll(repoLabel, branchChip, abChip, lfsChip, spacer,
                message, busyText, spacer2, counts, progress);

        Fx.bindStatus(label -> {
            // 忙碌文案只在忙碌时出现;结果消息不再被它覆盖
            // (以前两者共用一个 Label,一句「刷新状态…」就把「拉取完成…」冲掉了,操作结果根本看不到)
            busyText.setText(label == null || label.isBlank() ? "" : label);
            progress.setVisible(label != null && !label.isBlank());
        }, msg -> message.setText(msg));
    }

    /** LFS 使用状态徽标。 */
    public void updateLfs(boolean used, int count) {
        if (used) {
            lfsChip.setText("LFS " + count);
            lfsChip.setVisible(true);
        } else {
            lfsChip.setVisible(false);
        }
    }

    public void updateRepo(String repoPath, String branch, boolean detached, int ahead, int behind) {
        repoLabel.setText(repoPath == null ? "未打开仓库" : repoPath);
        if (branch == null || branch.isEmpty()) {
            branchChip.setText("no branch");
            branchChip.setVisible(false);
        } else {
            branchChip.setText(detached ? "HEAD 分离 @" + branch : branch);
            branchChip.setVisible(true);
        }
        if (ahead == 0 && behind == 0) {
            abChip.setText("");
            abChip.setVisible(false);
        } else {
            abChip.setText("↑" + ahead + " ↓" + behind);
            abChip.setVisible(true);
        }
    }

    public void updateCounts(int staged, int unstaged, int untracked, int conflicts) {
        StringBuilder sb = new StringBuilder();
        if (conflicts > 0) sb.append("冲突 ").append(conflicts).append("  ");
        sb.append("已暂存 ").append(staged).append("  未暂存 ").append(unstaged);
        if (untracked > 0) sb.append("  未跟踪 ").append(untracked);
        counts.setText(sb.toString());
    }
}
