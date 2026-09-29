package org.easygit.ui;

import org.easygit.core.GitProcess;
import org.easygit.core.JGitService;
import org.easygit.core.NativeGit;
import org.easygit.core.StatusParser.StatusResult;
import org.easygit.core.model.FileChange;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 变更面板(标记式提交,IDEA/GitHub Desktop 风格):
 * 上区=工作区改动(未暂存/未跟踪/冲突);下区=待提交清单(仅标记,不真正 git add);
 * 点提交时统一 add + commit。已在外部暂存的文件也会出现在下区。
 */
public class ChangesPanel extends VBox {

    private enum Area { UNSTAGED, STAGED }

    private enum ViewMode { FILE, DIFF }

    private final ListView<FileChange> workList = new ListView<>();
    private final ListView<FileChange> stagedList = new ListView<>();
    private final Label workTitle = new Label("未暂存(含未跟踪)");
    private final Label stagedTitle = new Label("待提交");
    private final TextArea msg = new TextArea();
    private final Button commitBtn = new Button("提交");
    private final javafx.scene.control.ToggleButton fileBtn = new javafx.scene.control.ToggleButton("文件");
    private final javafx.scene.control.ToggleButton diffBtn = new javafx.scene.control.ToggleButton("差异");
    private final DiffView diffView = new DiffView();

    /** 标记进"待提交"清单的路径(尚未真正 git add)。 */
    private final LinkedHashSet<String> toCommit = new LinkedHashSet<>();
    private StatusResult lastStatus;

    private final Runnable refreshAll;
    private final Consumer<String> blameOpener;
    private boolean updatingSelection = false;
    private FileChange selected;
    private Area selectedArea = Area.UNSTAGED;
    private ViewMode mode = ViewMode.DIFF;
    private int selectSeq = 0;

    public ChangesPanel(Runnable refreshAll, Consumer<String> blameOpener) {
        this.refreshAll = refreshAll;
        this.blameOpener = blameOpener;
        setSpacing(0);
        setPadding(Insets.EMPTY);

        // ---- 左列:文件清单(未暂存 / 待提交,两个常驻分区) ----
        configList(workList, Area.UNSTAGED);
        configList(stagedList, Area.STAGED);
        workTitle.getStyleClass().add("section-title");
        stagedTitle.getStyleClass().add("section-title");
        Button joinAll = new Button("全部加入");
        joinAll.getStyleClass().add("ghost");
        joinAll.setOnAction(e -> joinAll());
        Button removeAll = new Button("全部移出");
        removeAll.getStyleClass().add("ghost");
        removeAll.setOnAction(e -> removeAll());
        Region stagedSpacer = new Region();
        HBox.setHgrow(stagedSpacer, Priority.ALWAYS);
        HBox workHead = new HBox(workTitle);
        workHead.getStyleClass().add("changes-head");
        HBox stagedHead = new HBox(6, stagedTitle, stagedSpacer, removeAll, joinAll);
        stagedHead.getStyleClass().add("changes-head");
        stagedHead.setAlignment(Pos.CENTER_LEFT);

        VBox filesCol = new VBox(0, workHead, workList, stagedHead, stagedList);
        VBox.setVgrow(workList, Priority.ALWAYS);
        VBox.setVgrow(stagedList, Priority.ALWAYS);

        // ---- 右列:查看器(文件/差异 分段 + 统一/并排) ----
        javafx.scene.control.ToggleGroup viewGroup = new javafx.scene.control.ToggleGroup();
        diffBtn.setToggleGroup(viewGroup);
        fileBtn.setToggleGroup(viewGroup);
        diffBtn.setSelected(true);
        diffBtn.setOnAction(e -> {
            mode = ViewMode.DIFF;
            renderSelection();
        });
        fileBtn.setOnAction(e -> {
            mode = ViewMode.FILE;
            renderSelection();
        });
        HBox modeSwitch = new HBox(fileBtn, diffBtn);
        modeSwitch.getStyleClass().add("diff-mode-switch");
        Region viewerSpacer = new Region();
        HBox.setHgrow(viewerSpacer, Priority.ALWAYS);
        HBox viewerHead = new HBox(8, modeSwitch, viewerSpacer, diffView.makeLayoutSwitch());
        viewerHead.getStyleClass().add("changes-head");
        viewerHead.setAlignment(Pos.CENTER_LEFT);

        VBox diffCol = new VBox(0, viewerHead, diffView);
        VBox.setVgrow(diffView, Priority.ALWAYS);

        // ---- 主体:横向分栏(文件清单 | 差异) ----
        SplitPane hsplit = new SplitPane(filesCol, diffCol);
        hsplit.setDividerPositions(0.34);
        VBox.setVgrow(hsplit, Priority.ALWAYS);

        // ---- 提交页脚:说明 + 主提交按钮(全宽底色,文字与内容同在 12px 基准线) ----
        msg.setPromptText("输入提交说明…");
        msg.setPrefRowCount(5);
        // VBox 空间不足时会先把无 vgrow 的页脚压到最小——TextArea 最小=一行,
        // 曾被压成单行输入框;钉住"最小=首选"让它不可压缩
        msg.setMinHeight(Region.USE_PREF_SIZE);
        msg.getStyleClass().add("commit-msg");
        commitBtn.getStyleClass().add("primary");
        commitBtn.setOnAction(e -> doCommit());
        commitBtn.setPrefHeight(56);
        HBox.setHgrow(msg, Priority.ALWAYS);
        HBox footer = new HBox(10, msg, commitBtn);
        footer.getStyleClass().add("commit-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        // 内容统一 12px 侧边距:分区头/清单/查看器全部对齐到同一基准线
        VBox content = new VBox(0, hsplit);
        content.setPadding(new Insets(0, 12, 0, 12));
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(content, footer);
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Path repo() {
        return org.easygit.core.RepoManager.get().current();
    }

    private void configList(ListView<FileChange> list, Area area) {
        list.setCellFactory(v -> new FileCell());
        list.setPlaceholder(new Label(""));
        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (updatingSelection || nv == null) return;
            updatingSelection = true;
            workList.getSelectionModel().clearSelection();
            stagedList.getSelectionModel().clearSelection();
            list.getSelectionModel().select(nv);
            updatingSelection = false;
            selected = nv;
            selectedArea = area;
            renderSelection();
        });
        list.setOnMouseClicked(e -> {
            FileChange f = list.getSelectionModel().getSelectedItem();
            if (f != null && e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
                if (f.unmerged) openConflictDialog(f);
                else if (area == Area.STAGED) removeFromCommit(f);
                else addToCommit(f);
            }
        });
    }

    // ---------- 状态刷新(纯分桶,不产生 git 调用) ----------

    public void refresh(StatusResult st) {
        lastStatus = st;

        // 待提交 = 真实已暂存(外部操作产生) ∪ 标记集合(按最新状态解析,已消失的丢弃)
        Map<String, FileChange> commitMap = new LinkedHashMap<>();
        for (FileChange f : st.changes()) {
            if (!f.untracked && !f.unmerged && f.indexState != ' ' && f.indexState != '?') {
                commitMap.put(f.path, f);
            }
        }
        toCommit.removeIf(p -> st.changes().stream().noneMatch(c -> c.path.equals(p)));
        for (String p : toCommit) {
            st.changes().stream().filter(c -> c.path.equals(p)).findFirst()
                    .ifPresent(f -> commitMap.putIfAbsent(p, f));
        }
        List<FileChange> commitList = new ArrayList<>(commitMap.values());

        // 上区 = 未进入待提交清单的工作区改动
        List<FileChange> work = st.changes().stream()
                .filter(f -> (f.untracked || f.unmerged || f.wtState != ' ')
                        && !commitMap.containsKey(f.path))
                .toList();

        workList.getItems().setAll(work);
        stagedList.getItems().setAll(commitList);
        long conflicts = st.changes().stream().filter(f -> f.unmerged).count();
        workTitle.setText("未暂存(含未跟踪) (" + work.size() + ")"
                + (conflicts > 0 ? "   ⚠ 冲突 " + conflicts : ""));
        stagedTitle.setText("待提交 (" + commitList.size() + ")");
        commitBtn.setDisable(commitList.isEmpty());

        // 选中的文件已不存在时清空查看器
        if (selected != null) {
            boolean still = work.stream().anyMatch(f -> f.path.equals(selected.path))
                    || commitList.stream().anyMatch(f -> f.path.equals(selected.path));
            if (!still) {
                selected = null;
                diffView.clear();
            }
        }
    }

    /**
     * 切换仓库:清空标记、查看器与两个文件清单。
     * 只清查看器不够——状态栏在切换瞬间就换成了新仓库,而两个清单要等新仓库的
     * 状态到位才重填,中间这段窗口里旧仓库的文件会挂在新仓库界面上(连续切换时肉眼可见)。
     */
    public void onRepoSwitched() {
        updatingSelection = true;
        workList.getSelectionModel().clearSelection();
        stagedList.getSelectionModel().clearSelection();
        updatingSelection = false;
        workList.getItems().clear();
        stagedList.getItems().clear();
        workTitle.setText("未暂存(含未跟踪) (0)");
        stagedTitle.setText("待提交 (0)");
        commitBtn.setDisable(true);
        selected = null;
        selectSeq++; // 在途的旧文件/差异读取作废(守卫之外的二道保险)
        lastStatus = null; // 旧仓库的状态快照作废:重新分桶/"加入待提交"都不能再基于它
        toCommit.clear();
        diffView.clear();
    }

    // ---------- 加入 / 移出待提交 ----------

    private void addToCommit(FileChange f) {
        toCommit.add(f.path);
        if (lastStatus != null) refresh(lastStatus); // 纯 UI 重新分桶,零 git 调用
        UiLog.line("加入待提交: " + f.path);
        Fx.status("已加入待提交: " + f.path);
    }

    private void removeFromCommit(FileChange f) {
        toCommit.remove(f.path);
        if (f.indexState != ' ' && f.indexState != '?') {
            // 外部/之前真实暂存过的,需要真正 unstage
            act(() -> new JGitService(repo()).unstage(List.of(f.path)), "已移出待提交: " + f.path);
        } else {
            if (lastStatus != null) refresh(lastStatus);
            UiLog.line("移出待提交: " + f.path);
            Fx.status("已移出待提交: " + f.path);
        }
    }

    private void joinAll() {
        if (workList.getItems().isEmpty()) return;
        for (FileChange f : workList.getItems()) toCommit.add(f.path);
        if (lastStatus != null) refresh(lastStatus);
        UiLog.line("全部加入待提交(" + workList.getItems().size() + " 个文件)");
        Fx.status("已全部加入待提交");
    }

    private void removeAll() {
        List<String> realStaged = stagedList.getItems().stream()
                .filter(f -> f.indexState != ' ' && f.indexState != '?')
                .map(f -> f.path).toList();
        if (realStaged.isEmpty()) {
            toCommit.clear();
            if (lastStatus != null) refresh(lastStatus);
            UiLog.line("已清空待提交清单");
            Fx.status("已清空待提交清单");
            return;
        }
        act(() -> new JGitService(repo()).unstage(realStaged), "已清空待提交清单");
        toCommit.clear();
    }

    // ---------- 选择 -> 文件视图 / 差异视图 ----------

    private void renderSelection() {
        FileChange f = selected;
        if (f == null) {
            diffView.clear();
            return;
        }
        // 守卫:切走仓库后旧仓库的差异/文件内容不再回填查看器
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) return;
        final int seq = ++selectSeq;
        final FileChange requested = f;

        // 是否为"真实已暂存"(决定差异视图对比基准:暂存区 vs 工作区)
        boolean realStaged = selectedArea == Area.STAGED
                && f.indexState != ' ' && f.indexState != '?';

        if (mode == ViewMode.FILE) {
            if (f.wtState == 'D' || (!f.untracked && !Files.exists(repo.resolve(f.path)))) {
                diffView.showPlainFile(f.path, List.of("(文件在工作区已删除,可切换差异视图查看)"));
                return;
            }
            Fx.bg("读取文件…", guard, () -> org.easygit.core.NativeGit.localFileLines(repo, f.path),
                    lines -> {
                        if (selectSeq != seq || selected != requested) return;
                        diffView.showPlainFile(f.path, lines);
                    });
            return;
        }
        // 差异视图
        if (f.unmerged) {
            Fx.bg("读取冲突文件…", guard, () -> {
                try {
                    return Files.readAllLines(repo.resolve(f.path));
                } catch (Exception ex) {
                    return List.of("(无法读取: " + ex.getMessage() + ")");
                }
            }, lines -> {
                if (selectSeq != seq || selected != requested) return;
                diffView.showPlainFile(f.path, lines);
            });
        } else if (f.untracked) {
            // 未跟踪文件没有真正的 diff:直接显示完整内容
            Fx.bg("读取文件…", guard, () -> org.easygit.core.NativeGit.localFileLines(repo, f.path),
                    lines -> {
                        if (selectSeq != seq || selected != requested) return;
                        diffView.showPlainFile(f.path, lines);
                    });
        } else if (realStaged) {
            Fx.bg("读取差异…", guard, () -> org.easygit.core.NativeGit.diffStaged(repo, f.path),
                    list -> {
                        if (selectSeq != seq || selected != requested) return;
                        diffView.showFiles(list);
                    });
        } else {
            Fx.bg("读取差异…", guard, () -> org.easygit.core.NativeGit.diffUnstaged(repo, f.path),
                    list -> {
                        if (selectSeq != seq || selected != requested) return;
                        diffView.showFiles(list);
                    });
        }
    }

    // ---------- 提交(统一 add + commit) ----------

    private void doCommit() {
        String message = msg.getText();
        if (message == null || message.isBlank()) {
            Fx.error("无法提交", "请输入提交说明", null);
            return;
        }
        List<String> toAdd = new ArrayList<>(toCommit);
        Path repo = repo();
        if (repo == null) return;

        Fx.bg("提交中…", () -> {
            // 1. 把标记的文件统一暂存(CLI:LFS 安全,且能标记冲突已解决)
            if (!toAdd.isEmpty()) {
                List<String> args = new ArrayList<>(List.of("add", "--"));
                args.addAll(toAdd);
                GitProcess.GitResult ar = GitProcess.in(repo).exec(args.toArray(String[]::new));
                UiLog.op("git add (" + toAdd.size() + " 个文件)", ar.out(), ar.err());
                if (!ar.ok()) throw new RuntimeException("暂存失败:\n" + ar.message());
                // 冲突必须全部解决后才能提交
                StatusResult st = NativeGit.status(repo);
                boolean unresolved = st.changes().stream()
                        .anyMatch(fc -> fc.unmerged && toAdd.contains(fc.path));
                if (unresolved) {
                    throw new RuntimeException("仍有未解决的冲突(文件中还有冲突标记),请先双击文件解决");
                }
            }
            // 2. 提交(CLI:钩子/输出可见)
            List<String> args = new ArrayList<>(List.of("commit", "-m", message.strip()));
            GitProcess.GitResult r = GitProcess.in(repo).exec(args.toArray(String[]::new));
            UiLog.op("git commit", r.out(), r.err());
            if (!r.ok()) throw new RuntimeException(r.message());
            String sha = NativeGit.headSha(repo);
            if (sha.isEmpty()) throw new RuntimeException("提交后无法读取 HEAD");
            return sha;
        }, sha -> {
            toCommit.clear();
            msg.clear();
            String subject = message.strip().split("\n", 2)[0];
            UiLog.line("提交 ✓ " + sha.substring(0, Math.min(8, sha.length())) + "  " + subject);
            Fx.status("提交成功 " + sha.substring(0, Math.min(8, sha.length())));
            refreshAll.run();
        });
    }

    // ---------- 动作 ----------

    private void discard(FileChange f) {
        if (!Fx.confirm("丢弃改动", "确定丢弃 " + f.path + " 的工作区改动?此操作不可撤销。")) return;
        act(() -> new JGitService(repo()).discard(List.of(f.path), null), "已丢弃 " + f.path);
    }

    private void deleteUntracked(FileChange f) {
        if (!Fx.confirm("删除文件", "确定删除未跟踪文件 " + f.path + "?")) return;
        act(() -> new JGitService(repo()).discard(null, List.of(f.path)), "已删除 " + f.path);
    }

    private void act(Runnable work, String doneMsg) {
        Fx.bg("执行 git 操作…", () -> {
            work.run();
            return null;
        }, r -> {
            UiLog.line(doneMsg);
            Fx.status(doneMsg);
            refreshAll.run();
        });
    }

    // ---------- 冲突 ----------

    private void openConflictDialog(FileChange f) {
        Path p = repo().resolve(f.path);
        try {
            List<String> lines = Files.readAllLines(p);
            org.easygit.core.model.ConflictModels.ConflictFile cf =
                    org.easygit.core.ConflictParser.parse(f.path, lines);
            if (!cf.hasConflicts()) {
                Fx.info("无冲突标记", "文件中没有冲突标记,可直接加入待提交。");
                return;
            }
            new ConflictDialog(cf, refreshAll).showAndWait();
        } catch (Exception ex) {
            Fx.error("打开冲突文件失败", ex.getMessage(), null);
        }
    }

    // ---------- 单元格 ----------

    private class FileCell extends javafx.scene.control.ListCell<FileChange> {
        @Override
        protected void updateItem(FileChange f, boolean empty) {
            super.updateItem(f, empty);
            if (empty || f == null) {
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            String letter = f.shortStatus();
            Label chip = new Label(letter);
            chip.getStyleClass().addAll("status-letter", chipClass(letter));
            Label name = new Label(ellipsizeLeading(f.displayPath(), maxPathChars(getListView())));
            name.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);

            boolean fromStaged = getListView() == stagedList;
            Button quick = new Button(f.unmerged ? "!" : (fromStaged ? "−" : "＋"));
            quick.getStyleClass().add("row-action");
            quick.setMinWidth(26);
            quick.setMaxHeight(22);
            if (f.unmerged) {
                quick.setTooltip(new Tooltip("解决冲突"));
                quick.setOnAction(e -> openConflictDialog(f));
            } else if (fromStaged) {
                quick.setTooltip(new Tooltip("移出待提交"));
                quick.setOnAction(e -> removeFromCommit(f));
            } else {
                quick.setTooltip(new Tooltip("加入待提交"));
                quick.setOnAction(e -> addToCommit(f));
            }

            HBox box = new HBox(8, chip, name, quick);
            box.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            if (getListView() != null) {
                box.prefWidthProperty().bind(getListView().widthProperty().subtract(30));
            }
            setGraphic(box);
            // 窄栏里文件名必然被省略号截断(上一行的 chip/按钮还要占位),悬停给全名
            setTooltip(Fx.tip(f.displayPath()));

            ContextMenu menu = new ContextMenu();
            if (f.unmerged) {
                menu.getItems().add(item("解决冲突…", e -> openConflictDialog(f)));
                menu.getItems().add(item("标记已解决(保留当前内容)", e -> {
                    toCommit.add(f.path);
                    act(() -> new JGitService(repo()).stage(List.of(f.path)), "已标记解决 " + f.path);
                }));
                menu.getItems().add(item("中止合并(恢复到合并前)", e -> {
                    if (Fx.confirm("中止合并", "确定中止当前合并,恢复到合并前的状态?")) {
                        Fx.bg("中止合并…", () -> org.easygit.core.NativeGit.mergeAbort(repo()).ok(),
                                ok -> {
                                    Fx.status(ok ? "已中止合并" : "中止合并失败(可能没有正在进行的合并)");
                                    refreshAll.run();
                                });
                    }
                }));
            } else if (fromStaged) {
                menu.getItems().add(item("移出待提交", e -> removeFromCommit(f)));
                menu.getItems().add(item("Blame 此文件", e -> blameOpener.accept(f.path)));
            } else {
                menu.getItems().add(item("加入待提交", e -> addToCommit(f)));
                menu.getItems().add(item("丢弃工作区改动", e -> discard(f)));
                menu.getItems().add(item("Blame 此文件", e -> blameOpener.accept(f.path)));
            }
            if (f.untracked) {
                menu.getItems().add(item("删除文件", e -> deleteUntracked(f)));
            }
            menu.getItems().add(item("复制路径", e -> copy(f.path)));
            setContextMenu(menu);
        }

        private String ellipsizeLeading(String s, int max) {
            if (s == null || s.length() <= max) return s;
            return "…" + s.substring(s.length() - Math.max(1, max - 1));
        }

        private int maxPathChars(ListView<?> list) {
            if (list == null || list.getWidth() <= 0) return 200;
            return Math.max(12, (int) ((list.getWidth() - 92) / 7.0));
        }
    }

    private static String chipClass(String letter) {
        return switch (letter) {
            case "A" -> "add";
            case "D" -> "del";
            case "R", "C" -> "ren";
            case "U" -> "conflict";
            case "?" -> "untracked";
            default -> "mod";
        };
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(h);
        return mi;
    }

    private static void copy(String s) {
        ClipboardContent content = new ClipboardContent();
        content.putString(s);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
