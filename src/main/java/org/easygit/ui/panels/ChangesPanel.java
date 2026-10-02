package org.easygit.ui.panels;

import org.easygit.core.ConflictIO;
import org.easygit.core.ConflictParser;
import org.easygit.core.GitProcess;
import org.easygit.core.JGitService;
import org.easygit.core.NativeGit;
import org.easygit.core.StatusParser.StatusResult;
import org.easygit.core.model.ConflictModels.ConflictFile;
import org.easygit.core.model.FileChange;
import org.easygit.core.model.MergeState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
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
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;
import org.easygit.ui.base.RepoGuard;
import org.easygit.ui.views.DiffView;
import org.easygit.ui.dialogs.ConflictDialog;

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

    // ---- 冲突 / 进行中操作横幅 ----
    private final HBox conflictBanner = new HBox(8);
    private final Label bannerTitle = new Label();
    private final Label bannerDetail = new Label();
    private final Button continueBtn = new Button();
    private final Button skipBtn = new Button("跳过");
    private final Button abortBtn = new Button("中止");
    /** 当前进行中的合并/变基/拣选状态(刷新时读取,动作按钮据此给出)。 */
    private MergeState mergeState = MergeState.NONE;
    /** 自动填进提交框的合并默认说明:操作结束后要能收回去,别留一段过期文案。 */
    private String autoFilledMsg;

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

        // ---- 冲突 / 进行中操作横幅(默认隐藏,refresh 时按仓库状态决定) ----
        bannerTitle.getStyleClass().add("conflict-title");
        bannerDetail.getStyleClass().add("conflict-detail");
        Region bannerSpacer = new Region();
        HBox.setHgrow(bannerSpacer, Priority.ALWAYS);
        continueBtn.getStyleClass().add("primary");
        continueBtn.setOnAction(e -> continueOperation());
        skipBtn.getStyleClass().add("ghost");
        skipBtn.setOnAction(e -> skipOperation());
        abortBtn.getStyleClass().add("ghost");
        abortBtn.setOnAction(e -> abortOperation());
        conflictBanner.getStyleClass().add("conflict-banner");
        conflictBanner.setAlignment(Pos.CENTER_LEFT);
        conflictBanner.getChildren().addAll(bannerTitle, bannerDetail, bannerSpacer,
                continueBtn, skipBtn, abortBtn);
        setBannerVisible(false);

        // 内容统一 12px 侧边距:分区头/清单/查看器全部对齐到同一基准线
        VBox content = new VBox(0, conflictBanner, hsplit);
        content.setPadding(new Insets(0, 12, 0, 12));
        VBox.setMargin(conflictBanner, new Insets(8, 0, 0, 0));
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(content, footer);
    }

    private void setBannerVisible(boolean visible) {
        conflictBanner.setVisible(visible);
        conflictBanner.setManaged(visible);
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
        // 稳定锚点:两个清单会被批量操作清空,按"是否非空"认列表不可靠(探针/样式都按 id 找)
        list.setId(area == Area.UNSTAGED ? "changes-work" : "changes-staged");
        list.setCellFactory(v -> new FileCell());
        list.setPlaceholder(new Label(""));
        // 多选:配合右键的批量「加入/移出/丢弃」;Ctrl+A 全选是 ListView 自带的
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (updatingSelection || nv == null) return;
            // 只清**另一个**清单的选中;本清单的多选不能动 ——
            // 原来这里会先 clear 再 select(nv) 把自己重置成单选,批量操作就只剩一行了(实测踩过)
            updatingSelection = true;
            ListView<FileChange> other = list == workList ? stagedList : workList;
            other.getSelectionModel().clearSelection();
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

        // 内容没变就不重建清单:5 秒轮询每次都 setAll 会把单元格连同悬停中的提示一起换掉
        // (实测:悬停文件项约 2 秒后提示自己消失),也会丢选中、白白刷新整列。
        if (!sameItems(workList.getItems(), work)) workList.getItems().setAll(work);
        if (!sameItems(stagedList.getItems(), commitList)) stagedList.getItems().setAll(commitList);
        int conflicts = (int) st.changes().stream().filter(f -> f.unmerged).count();
        workTitle.setText("未暂存(含未跟踪) (" + work.size() + ")"
                + (conflicts > 0 ? "   ⚠ 冲突 " + conflicts : ""));
        stagedTitle.setText("待提交 (" + commitList.size() + ")");
        commitBtn.setDisable(commitList.isEmpty());

        // 进行中的操作(合并/变基/拣选)决定横幅上给哪几个动作。
        // 只读 .git 下的标记文件,不起子进程,放在 FX 线程上没有负担。
        Path repoPath = repo();
        mergeState = repoPath == null ? MergeState.NONE : NativeGit.mergeState(repoPath);
        updateConflictBanner(conflicts);

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
     * 两个清单内容是否等价(逐项比路径与状态)。状态解析每次都产生新对象,不能比引用。
     */
    private static boolean sameItems(List<FileChange> shown, List<FileChange> next) {
        if (shown.size() != next.size()) return false;
        for (int i = 0; i < shown.size(); i++) {
            FileChange a = shown.get(i), b = next.get(i);
            if (!a.path.equals(b.path) || a.untracked != b.untracked || a.unmerged != b.unmerged
                    || a.indexState != b.indexState || a.wtState != b.wtState) {
                return false;
            }
        }
        return true;
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
        mergeState = MergeState.NONE;
        autoFilledMsg = null;
        setBannerVisible(false);
        diffView.clear();
    }

    // ---------- 冲突与进行中的操作(合并 / 变基 / 拣选) ----------

    /**
     * 刷新顶部横幅。
     *
     * 光把冲突文件列出来是不够的:变基停在冲突上时,用户解决完还必须「继续」,
     * 否则剩下的提交永远放不完;而且这个状态在文件清单里完全看不出来。
     * 横幅负责说清"什么操作进行到哪了、还剩几处冲突、下一步点哪里"。
     */
    private void updateConflictBanner(int conflicts) {
        boolean show = mergeState.inProgress() || conflicts > 0;
        setBannerVisible(show);
        conflictBanner.getStyleClass().remove("has-conflicts");
        if (conflicts > 0) conflictBanner.getStyleClass().add("has-conflicts");
        if (!show) {
            // 合并被中止/完成时,把自动填进去的默认说明一起收回,别留一段莫名其妙的文案
            if (autoFilledMsg != null && autoFilledMsg.equals(msg.getText())) msg.clear();
            autoFilledMsg = null;
            return;
        }
        List<String> hints = new ArrayList<>();
        String title;
        if (mergeState.inProgress()) {
            title = mergeState.kind().label() + "进行中";
            String progress = mergeState.progressText();
            if (!progress.isEmpty()) hints.add(progress);
            if (!mergeState.detail().isBlank()) hints.add(mergeState.detail());
        } else {
            title = "有 " + conflicts + " 个文件存在冲突";
        }
        if (conflicts > 0) {
            hints.add(mergeState.inProgress()
                    ? "还有 " + conflicts + " 个文件未解决(双击文件解决)"
                    : "双击文件逐个解决,全部解决后即可提交");
        } else if (mergeState.inProgress()) {
            hints.add("冲突已全部解决,可以继续");
        }
        bannerTitle.setText(title);
        bannerDetail.setText(String.join(" · ", hints));

        boolean busy = mergeState.inProgress();
        String label = mergeState.kind().label();
        continueBtn.setText("继续" + label);
        continueBtn.setDisable(conflicts > 0);
        continueBtn.setTooltip(conflicts > 0
                ? Fx.tip("先把 " + conflicts + " 个冲突文件全部解决")
                : Fx.tip("完成这次" + label + (mergeState.kind() == MergeState.Kind.MERGE
                        ? "(用 git 备好的合并说明提交)" : "")));
        showButton(continueBtn, busy);
        showButton(skipBtn, busy && mergeState.canSkip());
        skipBtn.setTooltip(Fx.tip("放弃当前提交的改动,继续变基剩下的部分"));
        showButton(abortBtn, busy);

        // 合并进行中:把 git 备好的默认说明填进提交框(用户自己敲过就不覆盖)
        if (mergeState.kind() == MergeState.Kind.MERGE && !mergeState.message().isBlank()
                && msg.getText().isBlank()) {
            msg.setText(mergeState.message());
            autoFilledMsg = mergeState.message();
        }
    }

    private static void showButton(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
    }

    /** 「继续」:合并走 commit --no-edit,变基/拣选走 continue(已压制编辑器)。 */
    private void continueOperation() {
        Path repo = repo();
        if (repo == null) return;
        String name = mergeState.kind().label();
        Fx.bg("继续" + name + "…", () -> NativeGit.continueInProgress(repo), r -> {
            UiLog.op("git " + name + " --continue" + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                Fx.status(name + "完成");
            } else if (NativeGit.unmergedCount(repo) > 0) {
                // 继续路上又撞上新的冲突:git 用非 0 退出码表示"停住了",这不是失败
                Fx.status(name + "又遇到新的冲突,解决后继续");
            } else {
                Fx.error("继续" + name + "失败", NativeGit.friendlyError(r.message()), r.message());
            }
            refreshAll.run();
        });
    }

    /** 「跳过」:只有变基有这回事,丢的是当前这个提交的改动,必须先确认。 */
    private void skipOperation() {
        Path repo = repo();
        if (repo == null) return;
        if (!Fx.confirm("跳过当前提交",
                "这个提交的改动会被丢弃,然后继续变基剩下的提交。\n\n只有确实不需要它时才跳过。")) {
            return;
        }
        Fx.bg("跳过当前提交…", () -> NativeGit.skipInProgress(repo), r -> {
            UiLog.op("git rebase --skip" + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                Fx.status("已跳过,变基继续");
            } else if (NativeGit.unmergedCount(repo) > 0) {
                Fx.status("已跳过,下一个提交又冲突了");
            } else {
                Fx.error("跳过失败", NativeGit.friendlyError(r.message()), r.message());
            }
            refreshAll.run();
        });
    }

    /** 「中止」:恢复操作前的状态。文件清单里的冲突行右键也走这里,文案只有一处。 */
    private void abortOperation() {
        Path repo = repo();
        if (repo == null) return;
        MergeState st = mergeState;
        String what = st.kind().inProgress() ? st.kind().label() : "合并";
        String extra = st.kind() == MergeState.Kind.REBASE
                ? "\n\n变基会退回已重放的提交,回到变基前的状态。" : "";
        if (!Fx.confirm("中止" + what, "确定中止进行中的" + what + ",恢复到操作前的状态?" + extra)) {
            return;
        }
        Fx.bg("中止" + what + "…", () -> NativeGit.abortInProgress(repo), r -> {
            UiLog.op("git " + what + " --abort" + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            Fx.status(r.ok() ? "已中止" + what + ",恢复原状"
                    : "中止失败:" + NativeGit.friendlyError(r.message()));
            refreshAll.run();
        });
    }

    // ---------- 加入 / 移出待提交 ----------

    /** 批量操作的目标:点了已选中的行就作用于整个选区,否则只作用于这一行。 */
    private static List<FileChange> targetsOf(ListView<FileChange> list, FileChange f) {
        var sel = list == null ? null : list.getSelectionModel().getSelectedItems();
        return sel != null && sel.contains(f) ? List.copyOf(sel) : List.of(f);
    }

    /** 菜单文案:单选带路径级的简洁名,多选带数量。 */
    private static String batchLabel(String action, List<FileChange> targets) {
        return targets.size() > 1 ? action + "(所选 " + targets.size() + " 个)" : action;
    }

    /**
     * 批量菜单项:文字与目标都在**菜单弹出/点击那一刻**按当前选区解析,
     * 不能在单元格刷新时捕获 —— 选区是之后才多选出来的,捕获旧值就会只操作一行(实测踩过)。
     */
    private MenuItem batchItem(ListView<FileChange> list, FileChange f, String action,
                               java.util.function.Consumer<List<FileChange>> run) {
        MenuItem mi = new MenuItem(action);
        mi.setOnAction(e -> run.accept(targetsOf(list, f)));
        mi.setOnMenuValidation(ev -> mi.setText(batchLabel(action, targetsOf(list, f))));
        return mi;
    }

    private static String describe(List<FileChange> targets) {
        return targets.size() == 1 ? targets.get(0).path : targets.size() + " 个文件";
    }

    private void addToCommit(FileChange f) {
        addToCommit(List.of(f));
    }

    /** 把所选未暂存文件标记进待提交(纯 UI 标记,git add 在提交时统一做);冲突文件跳过,要先解决。 */
    private void addToCommit(List<FileChange> targets) {
        int conflicts = 0;
        for (FileChange f : targets) {
            if (f.unmerged) { conflicts++; continue; }
            toCommit.add(f.path);
        }
        int added = targets.size() - conflicts;
        if (added > 0 && lastStatus != null) refresh(lastStatus); // 纯 UI 重新分桶,零 git 调用
        if (added > 0) {
            UiLog.line("加入待提交: " + describe(targets));
            Fx.status("已加入待提交: " + describe(targets)
                    + (conflicts > 0 ? "(跳过 " + conflicts + " 个冲突文件,请先解决)" : ""));
        } else {
            Fx.status("所选文件都有未解决冲突,请先双击解决");
        }
    }

    private void removeFromCommit(FileChange f) {
        removeFromCommit(List.of(f));
    }

    /** 移出所选待提交文件:仅标记的直接撤销;真实已暂存的合并成一次 git unstage。 */
    private void removeFromCommit(List<FileChange> targets) {
        List<String> realStaged = targets.stream()
                .filter(f -> f.indexState != ' ' && f.indexState != '?')
                .map(f -> f.path).toList();
        for (FileChange f : targets) toCommit.remove(f.path);
        String what = describe(targets);
        if (realStaged.isEmpty()) {
            if (lastStatus != null) refresh(lastStatus);
            UiLog.line("移出待提交: " + what);
            Fx.status("已移出待提交: " + what);
        } else {
            act(() -> new JGitService(repo()).unstage(realStaged), "已移出待提交: " + what);
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
            // 用 ConflictIO 读:GBK 文件按 UTF-8 读会整篇乱码,CRLF 也不该在查看时就丢掉
            Fx.bg("读取冲突文件…", guard, () -> {
                try {
                    return ConflictIO.read(repo.resolve(f.path)).lines();
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
            // 1. 冲突标记兜底:git add 会把"还带着 <<<<<<< 的文件"也当成已解决记进索引,
            //    所以必须在 add **之前**按文件内容判断,否则标记会被原样提交上去。
            //    (原来这里是在 add 之后查 status.unmerged —— 那一刻冲突早已被 add 清掉,永远不触发)
            if (!toAdd.isEmpty()) {
                List<String> unmerged = NativeGit.unmergedPaths(repo);
                List<String> stillMarked = new ArrayList<>();
                for (String p : toAdd) {
                    if (!unmerged.contains(p)) continue;
                    Path f = repo.resolve(p);
                    if (!Files.isRegularFile(f)) continue;   // 用"删除文件"解决的冲突
                    try {
                        if (ConflictParser.hasMarkers(ConflictIO.read(f).lines())) stillMarked.add(p);
                    } catch (Exception ignored) {
                        // 读不动就不拦(交给 git 自己判断),不能因为一个怪文件让提交彻底不可用
                    }
                }
                if (!stillMarked.isEmpty()) {
                    throw new RuntimeException("这些文件里还留着冲突标记,请先解决再提交:\n"
                            + String.join("\n", stillMarked));
                }
                // 2. 把标记的文件统一暂存(CLI:LFS 安全,且能标记冲突已解决)
                List<String> args = new ArrayList<>(List.of("add", "--"));
                args.addAll(toAdd);
                GitProcess.GitResult ar = GitProcess.in(repo).exec(args.toArray(String[]::new));
                UiLog.op("git add (" + toAdd.size() + " 个文件)", ar.out(), ar.err());
                if (!ar.ok()) throw new RuntimeException("暂存失败:\n" + ar.message());
            }
            // 3. 提交(CLI:钩子/输出可见);若正处于合并中,这次提交即完成合并
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
        discard(List.of(f));
    }

    /** 丢弃所选文件的工作区改动:一次确认、一批执行;未跟踪的走删除,冲突文件跳过。 */
    private void discard(List<FileChange> targets) {
        List<FileChange> real = targets.stream().filter(f -> !f.unmerged).toList();
        int skipped = targets.size() - real.size();
        if (real.isEmpty()) {
            Fx.status("所选都是冲突文件,请先解决冲突");
            return;
        }
        String listing = real.stream().limit(8).map(f -> f.path)
                .reduce((a, b) -> a + "\n" + b).orElse("") + (real.size() > 8 ? "\n…" : "");
        String ask = real.size() == 1
                ? "确定丢弃 " + real.get(0).path + " 的工作区改动?此操作不可撤销。"
                : "确定丢弃所选 " + real.size() + " 个文件的工作区改动?此操作不可撤销。\n\n" + listing;
        if (!Fx.confirm("丢弃改动", ask)) return;
        List<String> tracked = real.stream().filter(f -> !f.untracked).map(f -> f.path).toList();
        List<String> untracked = real.stream().filter(f -> f.untracked).map(f -> f.path).toList();
        act(() -> {
            if (!tracked.isEmpty()) new JGitService(repo()).discard(tracked, null);
            if (!untracked.isEmpty()) new JGitService(repo()).discard(null, untracked);
        }, "已丢弃 " + real.size() + " 个文件的改动" + (skipped > 0 ? "(跳过 " + skipped + " 个冲突文件)" : ""));
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
        Path repoPath = repo();
        if (repoPath == null) return;
        Path p = repoPath.resolve(f.path);
        try {
            // 读文件时就把编码/BOM/换行风格一起带出来,保存时原样写回
            ConflictIO.Content content = ConflictIO.read(p);
            ConflictFile cf = ConflictParser.parse(f.path, content.lines());
            if (!cf.hasConflicts()) {
                Fx.info("无冲突标记", "文件中没有冲突标记,可直接加入待提交。");
                return;
            }
            new ConflictDialog(cf, content, p, refreshAll).showAndWait();
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
            // 全名直接交给 Label,由 LEADING_ELLIPSIS 按可用宽度自适应截断:
            // 此前按"当时的列表宽度"手工截死文本,拉伸面板变宽后单元格不重建立,
            // 文本还是旧短串,文件名永远省略(2026-10-01 用户反馈)。
            Label name = new Label(f.displayPath());
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
                    act(() -> {
                        GitProcess.GitResult r = NativeGit.markResolved(repo(), List.of(f.path));
                        if (!r.ok()) throw new RuntimeException(r.message());
                    }, "已标记解决 " + f.path);
                }));
                menu.getItems().add(item("中止合并/变基(恢复原状)", e -> abortOperation()));
            } else if (fromStaged) {
                menu.getItems().add(batchItem(getListView(), f, "移出待提交", ts -> removeFromCommit(ts)));
                menu.getItems().add(item("Blame 此文件", e -> blameOpener.accept(f.path)));
            } else {
                menu.getItems().add(batchItem(getListView(), f, "加入待提交", ts -> addToCommit(ts)));
                menu.getItems().add(batchItem(getListView(), f, "丢弃工作区改动", ts -> discard(ts)));
                menu.getItems().add(item("Blame 此文件", e -> blameOpener.accept(f.path)));
            }
            if (f.untracked) {
                menu.getItems().add(item("删除文件", e -> deleteUntracked(f)));
            }
            menu.getItems().add(item("复制路径", e -> copy(f.path)));
            setContextMenu(menu);
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
