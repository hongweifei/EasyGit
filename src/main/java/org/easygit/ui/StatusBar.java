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
    private final Label message = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label counts = new Label();

    public StatusBar() {
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 10, 4, 10));
        setSpacing(10);
        setStyle("-fx-background-color: -panel; -fx-border-color: -border transparent transparent transparent;");

        repoLabel.getStyleClass().add("dim");
        branchChip.getStyleClass().addAll("chip", "chip-branch");
        abChip.getStyleClass().add("chip");
        lfsChip.getStyleClass().addAll("chip", "chip-tag");
        lfsChip.setVisible(false);
        lfsChip.setTooltip(new javafx.scene.control.Tooltip("此仓库使用 Git LFS,数字为 LFS 跟踪的文件数"));
        counts.getStyleClass().add("dim");

        progress.setPrefSize(16, 16);
        progress.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        getChildren().addAll(repoLabel, branchChip, abChip, lfsChip, spacer, message, spacer2, counts, progress);

        Fx.bindStatus(label -> {
            if (label == null || label.isBlank()) {
                progress.setVisible(false);
            } else {
                progress.setVisible(true);
                message.setText(label);
            }
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
