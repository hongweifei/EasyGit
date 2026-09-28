package org.easygit.ui;

import org.easygit.core.GraphBuilder;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.util.List;

/**
 * 历史面板:提交图(虚拟化列表 + Canvas 泳道图)+ 提交详情。
 */
public class HistoryPanel extends VBox {

    private static final Color[] PALETTE = {
            Color.web("#58a6ff"), Color.web("#3fb950"), Color.web("#d29922"), Color.web("#f85149"),
            Color.web("#a371f7"), Color.web("#39c5cf"), Color.web("#ff7b72"), Color.web("#7ee787")
    };

    private static final double LANE_W = 12;
    private static final double ROW_H = 44;

    private final ListView<CommitEntry> list = new ListView<>();
    private final CommitDetailPanel detail = new CommitDetailPanel();
    private final CheckBox allBranches = new CheckBox("所有分支");
    private final SplitPane split = new SplitPane();
    private final Runnable refreshAll;
    private final javafx.scene.control.Label filterLabel = new javafx.scene.control.Label();
    private final HBox filterBar;
    private String pathFilter;
    /** 当前分支尚未推送到上游的提交 SHA 集合。 */
    private java.util.Set<String> unpushedIds = java.util.Set.of();

    public HistoryPanel(Runnable refreshAll, java.util.function.Consumer<String> blameOpener) {
        this.refreshAll = refreshAll;
        setSpacing(4);
        setPadding(new Insets(6));

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshAll.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        allBranches.setOnAction(e -> refresh());
        HBox top = new HBox(10, new Label("提交历史"), allBranches, spacer, refreshBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 4, 0));

        Button clearFilter = new Button("✕ 清除筛选");
        clearFilter.setOnAction(e -> clearPathFilter());
        filterLabel.getStyleClass().add("chip");
        filterBar = new HBox(8, filterLabel, clearFilter);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setVisible(false);
        filterBar.setManaged(false);

        list.setFixedCellSize(ROW_H);
        list.setCellFactory(v -> new CommitCell());
        list.setPlaceholder(new Label("暂无提交"));
        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> detail.show(nv));
        list.setOnContextMenuRequested(e -> {
            CommitEntry c = list.getSelectionModel().getSelectedItem();
            if (c == null) return;
            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(
                    mi("检出此提交(detached)", () -> {
                        Fx.bg("检出提交…", () -> {
                            new org.easygit.core.JGitService(repo()).checkoutCommit(c.id);
                            return true;
                        }, r -> { Fx.status("已检出 " + c.abbr); refreshAll.run(); });
                    }),
                    mi("在此提交上创建分支…", () -> {
                        String name = Dialogs.newBranch("");
                        if (name != null) {
                            Fx.bg("创建分支…", () -> {
                                new org.easygit.core.JGitService(repo()).createBranch(name, c.id);
                                return true;
                            }, r -> { Fx.status("已创建分支 " + name); refreshAll.run(); });
                        }
                    }),
                    mi("打标签…", () -> {
                        String[] t = Dialogs.tag(c.abbr);
                        if (t != null) {
                            Fx.bg("创建标签…", () -> {
                                new org.easygit.core.JGitService(repo()).createTag(t[0], c.id, t[1]);
                                return true;
                            }, r -> { Fx.status("已创建标签 " + t[0]); refreshAll.run(); });
                        }
                    }),
                    mi("撤回到此提交…", () -> Dialogs.resetToDialog(c, refreshAll)),
                    mi("修改提交消息…", () ->
                            Dialogs.editCommitMessage(c, refreshAll)),
                    mi("复制 SHA", () -> copy(c.id)),
                    mi("复制提交说明", () -> copy(c.subject))
            );
            menu.show(list, e.getScreenX(), e.getScreenY());
        });

        // 左右布局:提交列表在左,提交详情(信息/文件/查看器)在右
        split.getItems().addAll(list, detail);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);
        detail.setBlameOpener(blameOpener);
        detail.setHistoryFilter(this::filterByPath);
        getChildren().addAll(top, filterBar, split);
    }

    /** 按文件路径筛选历史(只显示改动该文件的提交)。 */
    public void filterByPath(String path) {
        if (path == null || path.isBlank()) return;
        pathFilter = path;
        filterLabel.setText("文件历史: " + path);
        filterBar.setVisible(true);
        filterBar.setManaged(true);
        refresh();
    }

    private void clearPathFilter() {
        pathFilter = null;
        filterBar.setVisible(false);
        filterBar.setManaged(false);
        refresh();
    }

    private static Path repo() { return org.easygit.core.RepoManager.get().current(); }

    public boolean allBranchesSelected() { return allBranches.isSelected(); }

    /** 主窗口统一取数后注入(已含图布局)。 */
    public void setCommits(List<CommitEntry> commits) {
        setCommits(commits, java.util.Set.of());
    }

    /** 带未推送标记的注入。默认选中第一条(最新提交);已有选择且仍存在时保持。 */
    public void setCommits(List<CommitEntry> commits, java.util.Set<String> unpushed) {
        unpushedIds = unpushed == null ? java.util.Set.of() : unpushed;
        CommitEntry prev = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(commits);
        if (commits.isEmpty()) {
            detail.clear();
            return;
        }
        CommitEntry target = null;
        if (prev != null) {
            for (CommitEntry c : commits) {
                if (c.id.equals(prev.id)) { target = c; break; }
            }
        }
        if (target == null) target = commits.get(0);
        list.getSelectionModel().select(target); // 触发监听器加载详情
    }

    private int histSeq = 0;

    public void refresh() {
        Path r = repo();
        if (r == null) {
            list.getItems().clear();
            detail.clear();
            return;
        }
        final int seq = ++histSeq; // 防止并发的多次加载乱序覆盖
        Fx.bg("读取提交历史…", () -> {
            List<CommitEntry> log = org.easygit.core.NativeGit.log(r,
                    org.easygit.core.AppSettings.get().maxCommits(),
                    allBranches.isSelected(), null, pathFilter);
            // 未推送集合随历史一起重算,保证 ↑ 标记始终准确
            java.util.Set<String> unpushed =
                    org.easygit.core.NativeGit.unpushedShas(r,
                            org.easygit.core.AppSettings.get().maxCommits());
            return new HistoryLoad(log, unpushed);
        }, data -> {
            if (seq != histSeq) return;
            setCommits(data.log(), data.unpushed());
        });
    }

    private record HistoryLoad(List<CommitEntry> log, java.util.Set<String> unpushed) {}

    private static MenuItem mi(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private static void copy(String s) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    // ---------- 渲染 ----------

    private class CommitCell extends javafx.scene.control.ListCell<CommitEntry> {

        CommitCell() {
            // 与 theme.css 的 .list-cell.commit-cell 规则配套。缺了这个样式类，
            // 整套选中高亮规则都不会命中(此前就是如此，属于死 CSS)。
            getStyleClass().add("commit-cell");
        }

        @Override
        protected void updateItem(CommitEntry c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            Canvas canvas = new Canvas(70, ROW_H);
            drawGraph(canvas.getGraphicsContext2D(), c);

            Label subject = new Label(c.subject);
            subject.getStyleClass().add("commit-subject");
            subject.setMaxWidth(Double.MAX_VALUE);
            subject.setTooltip(new Tooltip(c.subject + "\n" + c.author + "  " + c.timeText() + "  " + c.abbr));

            HBox meta = new HBox(8);
            meta.getChildren().add(new Label(c.author));
            Label time = new Label(c.timeText());
            time.getStyleClass().add("commit-meta");
            Label sha = new Label(c.abbr);
            sha.getStyleClass().add("commit-meta");
            meta.getChildren().addAll(time, sha);
            for (String r : c.refs) {
                Label chip = new Label(r.startsWith("tag:") ? r : r.replace("HEAD -> ", "★ "));
                chip.getStyleClass().add("chip");
                if (r.startsWith("tag:")) chip.getStyleClass().add("chip-tag");
                else if (r.startsWith("HEAD")) chip.getStyleClass().add("chip-head");
                else if (r.contains("/")) chip.getStyleClass().add("chip-remote");
                else chip.getStyleClass().add("chip-branch");
                meta.getChildren().add(chip);
            }
            if (unpushedIds.contains(c.id)) {
                Label up = new Label("↑ 未推送");
                up.getStyleClass().addAll("chip", "chip-unpushed");
                meta.getChildren().add(up);
            }
            VBox textBox = new VBox(1, subject, meta);
            textBox.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            HBox box = new HBox(canvas, textBox);
            box.getStyleClass().add("graph-container");
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
        }

        private void drawGraph(javafx.scene.canvas.GraphicsContext g, CommitEntry c) {
            g.clearRect(0, 0, 70, ROW_H);
            double h = ROW_H;
            double mid = h / 2.0;
            g.setLineWidth(2);
            g.setLineDashes(null);

            for (int[] e : c.edges) {
                int a = e[0], b = e[1];
                Color color = colorOf(b == -1 ? a : b);
                g.setStroke(color);
                if (b == -1) {
                    // 从上方弯入节点
                    g.strokeLine(laneX(a), 0, laneX(c.lane), mid);
                } else if (a == b) {
                    // 与节点无关的贯穿泳道
                    g.strokeLine(laneX(a), 0, laneX(a), h);
                } else {
                    // 从节点弯向下方泳道
                    g.strokeLine(laneX(a), mid, laneX(b), h);
                }
            }
            // 节点自身的竖线:上方来自 incoming,下方去往第一父
            g.setStroke(colorOf(c.lane));
            if (c.hasIncoming) {
                g.strokeLine(laneX(c.lane), 0, laneX(c.lane), mid);
            }
            if (!c.parents.isEmpty()) {
                g.strokeLine(laneX(c.lane), mid, laneX(c.lane), h);
            }
            // 节点
            g.setFill(colorOf(c.lane));
            double x = laneX(c.lane);
            g.fillOval(x - 4.5, mid - 4.5, 9, 9);
            if (c.isHead()) {
                g.setStroke(Color.web("#d29922"));
                g.setLineWidth(2);
                g.strokeOval(x - 6.5, mid - 6.5, 13, 13);
            }
        }
    }

    private static Color colorOf(int lane) {
        return PALETTE[Math.floorMod(lane, PALETTE.length)];
    }

    private static double laneX(int lane) {
        return GraphBuilder.laneX(lane, LANE_W);
    }
}
