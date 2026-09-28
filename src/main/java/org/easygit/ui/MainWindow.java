package org.easygit.ui;

import org.easygit.core.AppSettings;
import org.easygit.core.GraphBuilder;
import org.easygit.core.GitProcess;
import org.easygit.core.JGitService;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.StatusParser.StatusResult;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.StashEntry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 主窗口:工具栏 + 左侧分支树 + 中部(变更/历史/Stash)+ 状态栏 + 欢迎页。
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final TabPane tabs = new TabPane();
    private final RepoPanel repoPanel;
    private final BranchPanel branchPanel;
    private final ChangesPanel changesPanel;
    private final HistoryPanel historyPanel;
    private final StashPanel stashPanel;
    private final StatusBar statusBar = new StatusBar();
    private OutputPanel outputPanel;
    private final VBox welcome = buildWelcome();
    private VBox leftBox;
    private String loadedRepoPath;

    private Tab changesTab, historyTab, stashTab;

    public MainWindow(Stage stage) {
        this.stage = stage;
        repoPanel = new RepoPanel(stage);
        branchPanel = new BranchPanel(this::refreshAll);
        changesPanel = new ChangesPanel(this::refreshAll, this::openBlame);
        historyPanel = new HistoryPanel(this::refreshAll, this::openBlame);
        stashPanel = new StashPanel(this::refreshAll);
        buildRepoUI();
        buildToolbar();

        root.setCenter(welcome);
        outputPanel = new OutputPanel(); // 构造时绑定 UiLog
        root.setBottom(new VBox(outputPanel, statusBar));

        RepoManager.get().addListener(repo -> Platform.runLater(this::refreshAll));

        // 轻量自动刷新:每 5 秒刷新工作区状态
        Timeline poller = new Timeline(new KeyFrame(Duration.seconds(5), e -> lightRefresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    public void show() {
        Scene scene = new Scene(root, AppSettings.get().windowW(), AppSettings.get().windowH());
        String css = getClass().getResource("/css/theme.css").toExternalForm();
        scene.getStylesheets().add(css);
        if ("dark".equals(AppSettings.get().theme())) {
            root.getStyleClass().add("dark");
        }
        stage.setTitle("EasyGit");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> AppSettings.get().setWindowSize(stage.getWidth(), stage.getHeight()));
        stage.show();
        refreshAll();
    }

    // ---------- 布局 ----------

    private void buildRepoUI() {
        changesTab = new Tab("变更", changesPanel);
        historyTab = new Tab("历史", historyPanel);
        stashTab = new Tab("Stash", stashPanel);
        changesTab.setClosable(false);
        historyTab.setClosable(false);
        stashTab.setClosable(false);
        tabs.getTabs().addAll(changesTab, historyTab, stashTab);
        leftBox = new VBox(4, repoPanel, new Separator(), branchPanel);
        leftBox.setPrefWidth(300);
        leftBox.setMinWidth(220);
        root.setLeft(leftBox);
    }

    private void buildToolbar() {
        Button open = new Button("打开");
        open.setOnAction(e -> Dialogs.openRepo(stage));
        Button clone = new Button("克隆");
        clone.setOnAction(e -> Dialogs.cloneRepo(stage));
        Button init = new Button("初始化");
        init.setOnAction(e -> Dialogs.initRepo(stage));

        Button refresh = new Button("⟳ 刷新");
        refresh.setOnAction(e -> refreshAll());

        Button pull = new Button("拉取");
        pull.setOnAction(e -> network("拉取中…", () -> NativeGit.pull(RepoManager.get().current())));
        Button push = new Button("推送");
        push.setOnAction(e -> Dialogs.pushDialog(null, this::refreshAll));
        Button fetch = new Button("抓取");
        fetch.setOnAction(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) return;
            // 没有远程时给出明确指引,而不是静默无反应
            if (NativeGit.remotes(repo).isEmpty()) {
                Fx.info("抓取", "当前仓库没有配置远程。\n\n可在「推送」对话框里点「管理…」添加远程,添加后会自动抓取。");
                return;
            }
            network("抓取中…", () -> NativeGit.fetch(repo));
        });

        Button newBranch = new Button("新建分支");
        newBranch.setOnAction(e -> {
            if (RepoManager.get().current() == null) return;
            String name = Dialogs.newBranch("");
            if (name != null) {
                Fx.bg("创建分支…", () -> {
                    new JGitService(RepoManager.get().current()).checkout(name, true, null);
                    return true;
                }, r -> {
                    Fx.status("已创建并切换到 " + name);
                    refreshAll();
                });
            }
        });
        Button mergeBtn = new Button("合并");
        mergeBtn.setOnAction(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) return;
            Dialogs.mergeDialog(branchPanel == null ? List.of() : snapshotBranches, this::refreshAll);
        });

        Button blameBtn = new Button("Blame");
        blameBtn.setOnAction(e -> {
            if (RepoManager.get().current() == null) return;
            String path = Dialogs.askText("Blame", "输入要追溯的文件相对路径:", "");
            if (path != null && !path.isBlank()) openBlame(path.strip());
        });

        // LFS 菜单
        javafx.scene.control.MenuButton lfsBtn = new javafx.scene.control.MenuButton("LFS");
        javafx.scene.control.MenuItem miLfsStatus = new javafx.scene.control.MenuItem("LFS 状态…");
        javafx.scene.control.MenuItem miLfsPull = new javafx.scene.control.MenuItem("拉取 LFS 对象");
        javafx.scene.control.MenuItem miLfsFetch = new javafx.scene.control.MenuItem("抓取 LFS 对象");
        javafx.scene.control.MenuItem miLfsTrack = new javafx.scene.control.MenuItem("管理跟踪规则…");
        javafx.scene.control.MenuItem miLfsPrune = new javafx.scene.control.MenuItem("清理本地对象缓存…");
        javafx.scene.control.MenuItem miLfsInstall = new javafx.scene.control.MenuItem("初始化 Git LFS(git lfs install)");
        lfsBtn.getItems().addAll(miLfsStatus, miLfsPull, miLfsFetch, miLfsTrack, miLfsPrune,
                new javafx.scene.control.SeparatorMenuItem(), miLfsInstall);
        miLfsStatus.setOnAction(e -> {
            Fx.bg("读取 LFS 状态…", () -> {
                Path repo = RepoManager.get().current();
                if (repo == null) throw new IllegalStateException("请先打开仓库");
                return org.easygit.core.LfsService.gather(repo);
            }, info -> Dialogs.lfsStatusDialog(stage, info));
        });
        miLfsPull.setOnAction(e -> {
            if (!Fx.confirm("拉取 LFS 对象", "将当前分支所有 LFS 指针替换为完整文件(git lfs pull)?")) return;
            Fx.bg("拉取 LFS 对象…", () -> {
                Path repo = RepoManager.get().current();
                if (repo == null) throw new IllegalStateException("请先打开仓库");
                var r = org.easygit.core.LfsService.pull(repo);
                if (!r.ok()) throw new IllegalStateException(r.message());
                org.easygit.core.LfsService.resetCaches();
                return true;
            }, ok -> {
                Fx.status("LFS 对象拉取完成");
                refreshAll();
            });
        });
        miLfsFetch.setOnAction(e -> {
            Fx.bg("抓取 LFS 对象…", () -> {
                Path repo = RepoManager.get().current();
                if (repo == null) throw new IllegalStateException("请先打开仓库");
                var r = org.easygit.core.LfsService.fetch(repo);
                if (!r.ok()) throw new IllegalStateException(r.message());
                org.easygit.core.LfsService.resetCaches();
                return true;
            }, ok -> {
                Fx.status("LFS 对象抓取完成");
                refreshAll();
            });
        });
        miLfsTrack.setOnAction(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) {
                Fx.info("LFS", "请先打开仓库");
                return;
            }
            Dialogs.lfsTrackRulesDialog(stage, repo, this::refreshAll);
        });
        miLfsPrune.setOnAction(e -> {
            if (!Fx.confirm("清理 LFS 缓存", "清理本地 LFS 对象缓存中可安全移除的对象(git lfs prune)?\n已推送到远程的旧版本对象会被删除,需要时可重新下载。")) return;
            Fx.bg("清理 LFS 缓存…", () -> {
                Path repo = RepoManager.get().current();
                if (repo == null) throw new IllegalStateException("请先打开仓库");
                var r = org.easygit.core.LfsService.prune(repo);
                if (!r.ok()) throw new IllegalStateException(r.message());
                org.easygit.core.LfsService.resetCaches();
                return true;
            }, ok -> Fx.status("LFS 缓存清理完成"));
        });
        miLfsInstall.setOnAction(e -> {
            Fx.bg("初始化 Git LFS…", () -> {
                var r = org.easygit.core.LfsService.install();
                if (!r.ok()) throw new IllegalStateException(r.message());
                org.easygit.core.LfsService.resetCaches();
                return true;
            }, ok -> Fx.info("Git LFS", "已初始化(git lfs install),之后 add/commit/push 会自动处理 LFS 文件。"));
        });

        Button themeBtn = new Button("主题");
        themeBtn.setOnAction(e -> {
            var classes = root.getStyleClass();
            boolean dark = classes.contains("dark");
            if (dark) {
                classes.remove("dark");
                AppSettings.get().setTheme("light");
            } else {
                classes.add("dark");
                AppSettings.get().setTheme("dark");
            }
        });

        Button outputBtn = new Button("输出");
        outputBtn.setOnAction(e -> outputPanel.toggle());

        Button settingsBtn = new Button("设置");
        settingsBtn.setOnAction(e -> SettingsDialog.show(stage, root));

        ToolBar bar = new ToolBar(
                open, clone, init, new Separator(),
                refresh, pull, push, fetch, new Separator(),
                newBranch, mergeBtn, blameBtn, lfsBtn, new Separator(),
                outputBtn, settingsBtn, themeBtn
        );
        root.setTop(bar);
    }

    /** 工具栏合并对话框用的分支快照(在刷新时保存)。 */
    private List<BranchInfo> snapshotBranches = List.of();
    private String lastHeadSha;

    private void network(String busyLabel, java.util.function.Supplier<GitProcess.GitResult> work) {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg(busyLabel, work, r -> {
            UiLog.op(busyLabel + " (git " + busyLabel.replace("中…", "") + ")"
                    + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                Fx.status(busyLabel.replace("中…", "") + "完成");
                refreshAll();
            } else {
                Fx.error(busyLabel + "失败", r.message(), null);
            }
        });
    }

    // ---------- 欢迎页 ----------

    private VBox buildWelcome() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        Label title = new Label("EasyGit");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("原生 JavaFX Git 客户端 · 快速 · 轻量");
        sub.getStyleClass().add("welcome-sub");

        Button open = new Button("打开仓库…");
        open.setOnAction(e -> Dialogs.openRepo(stage));
        Button clone = new Button("克隆仓库…");
        clone.setOnAction(e -> Dialogs.cloneRepo(stage));
        Button init = new Button("初始化新仓库…");
        init.setOnAction(e -> Dialogs.initRepo(stage));
        for (Button b : new Button[]{open, clone, init}) {
            b.setMaxWidth(220);
            b.setPrefHeight(36);
        }

        Label recentTitle = new Label("我的仓库:");
        recentTitle.getStyleClass().add("h2");
        ListView<AppSettings.RepoEntry> recent = new ListView<>();
        recent.setPrefSize(460, 180);
        recent.setPlaceholder(new Label("还没有仓库,点击上方按钮添加"));
        List<AppSettings.RepoEntry> managed = AppSettings.get().repos();
        recent.getItems().addAll(managed);
        recent.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(AppSettings.RepoEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) { setText(null); setGraphic(null); return; }
                String group = e.group().isBlank() ? "未分组" : e.group();
                setText("【" + group + "】" + e.name() + "  —  " + e.path());
            }
        });
        recent.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AppSettings.RepoEntry sel = recent.getSelectionModel().getSelectedItem();
                if (sel != null && !RepoManager.get().open(Path.of(sel.path()))) {
                    Fx.error("打开失败", "该目录不再是 git 仓库: " + sel.path(), null);
                }
            }
        });

        Label gitInfo = new Label(GitProcess.version() + "   ·   " + org.easygit.core.GitLocator.describe());
        gitInfo.getStyleClass().add("dim");

        Region spacerTop = new Region();
        Region spacerBottom = new Region();
        VBox.setVgrow(spacerTop, Priority.ALWAYS);
        VBox.setVgrow(spacerBottom, Priority.ALWAYS);
        box.getChildren().addAll(spacerTop, title, sub, new Region(),
                open, clone, init, recentTitle, recent, gitInfo, spacerBottom);
        return box;
    }

    // ---------- 刷新 ----------

    private StatusResult currentStatus;

    private void refreshAll() {
        Path repo = RepoManager.get().current();
        if (repo == null) {
            loadedRepoPath = null;
            root.setLeft(null);
            root.setCenter(welcome);
            statusBar.updateRepo(null, null, false, 0, 0);
            statusBar.updateCounts(0, 0, 0, 0);
            refreshWelcomeList();
            return;
        }
        root.setLeft(leftBox);
        if (root.getCenter() != tabs) root.setCenter(tabs);

        // 切换到不同仓库时:立即清掉上一个仓库的内容与 Blame 页签
        if (!repo.toString().equals(loadedRepoPath)) {
            loadedRepoPath = repo.toString();
            lastHeadSha = null;
            tabs.getTabs().removeIf(t -> t.getText().startsWith("Blame:"));
            changesPanel.clearDiffView();
            historyPanel.setCommits(List.of());
            branchPanel.refresh(List.of());
            stashPanel.refresh(List.of());
        }

        boolean allBranches = historyPanel.allBranchesSelected();
        int maxCommits = AppSettings.get().maxCommits();

        // 阶段一(快):状态 / 分支 / stash / LFS 缓存状态 —— 让界面先可用
        Fx.bg("刷新仓库状态…", () -> {
            StatusResult st;
            List<BranchInfo> branches;
            try {
                st = NativeGit.status(repo);
                branches = NativeGit.branches(repo);
            } catch (Exception ex) {
                // 后台刷新失败不打扰用户,仅记录输出面板
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
            List<StashEntry> stashes;
            try {
                stashes = new JGitService(repo).stashList();
            } catch (Exception e) {
                stashes = List.of();
            }
            int[] lfs = org.easygit.core.LfsService.cachedRepoState(repo); // 命中缓存约 0ms
            String head = NativeGit.headSha(repo);
            return new RefreshData(st, branches, stashes, lfs[0] == 1, lfs[1], head);
        }, data -> {
            if (data == null) return;
            lastHeadSha = data.head();
            currentStatus = data.status;
            snapshotBranches = data.branches;
            changesPanel.refresh(data.status);
            branchPanel.refresh(data.branches);
            stashPanel.refresh(data.stashes);

            int staged = 0, unstaged = 0, untracked = 0, conflicts = 0;
            for (var f : data.status.changes()) {
                if (f.unmerged) conflicts++;
                else if (f.untracked) untracked++;
                if (f.indexState != ' ' && f.indexState != '?') staged++;
                if (!f.untracked && !f.unmerged && f.wtState != ' ') unstaged++;
            }
            statusBar.updateRepo(repo.toString(), data.status.branch(), data.status.detached(),
                    data.status.ahead(), data.status.behind());
            statusBar.updateCounts(staged, unstaged, untracked, conflicts);
            statusBar.updateLfs(data.lfsUsed, data.lfsCount);
        });

        // 阶段二(慢,并行):提交历史 + 未推送标记
        Fx.bg("读取提交历史…", () -> {
            List<CommitEntry> log = List.of();
            try {
                log = NativeGit.log(repo, maxCommits, allBranches, null);
            } catch (Exception ignored) {
                // 空仓库没有提交
            }
            return new HistoryData(log, NativeGit.unpushedShas(repo, maxCommits));
        }, data -> {
            GraphBuilder.build(data.log());
            historyPanel.setCommits(data.log(), data.unpushed());
        });
    }

    private void lightRefresh() {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg("刷新状态…", () -> {
            try {
                String head = NativeGit.headSha(repo);
                StatusResult st = NativeGit.status(repo);
                return new LightData(st, head);
            } catch (Exception ex) {
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
        }, data -> {
            if (data == null) return;
            // HEAD 变化(新提交/amend/检出)→ 自动重载历史
            if (lastHeadSha == null || !data.head().equals(lastHeadSha)) {
                lastHeadSha = data.head();
                historyPanel.refresh();
            }
            currentStatus = data.st();
            changesPanel.refresh(data.st());
            int staged = 0, unstaged = 0, untracked = 0, conflicts = 0;
            for (var f : data.st().changes()) {
                if (f.unmerged) conflicts++;
                else if (f.untracked) untracked++;
                if (f.indexState != ' ' && f.indexState != '?') staged++;
                if (!f.untracked && !f.unmerged && f.wtState != ' ') unstaged++;
            }
            statusBar.updateRepo(repo.toString(), data.st().branch(), data.st().detached(),
                    data.st().ahead(), data.st().behind());
            statusBar.updateCounts(staged, unstaged, untracked, conflicts);
        });
    }

    private void refreshWelcomeList() {
        // 重建欢迎页中的仓库列表(简单做法:整体替换欢迎页内容)
        root.setCenter(buildWelcome());
    }

    // ---------- Blame ----------

    private void openBlame(String path) {
        BlameView view = new BlameView(path);
        Tab tab = new Tab("Blame: " + path, view);
        tab.setClosable(true);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        tab.setOnClosed(e -> view.getChildren().clear());
    }

    private record RefreshData(StatusResult status, List<BranchInfo> branches,
                               List<StashEntry> stashes, boolean lfsUsed, int lfsCount, String head) {}

    private record HistoryData(List<CommitEntry> log, java.util.Set<String> unpushed) {}

    private record LightData(StatusResult st, String head) {}
}
