package org.easygit.ui;

import org.easygit.core.AppSettings;
import org.easygit.core.AppVersion;
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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
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
    /** 中部页签的卡片容器(设计语言:画布 + 浮动卡片)。 */
    private VBox centerCard;

    public MainWindow(Stage stage) {
        this.stage = stage;
        repoPanel = new RepoPanel(stage);
        branchPanel = new BranchPanel(this::refreshAll);
        changesPanel = new ChangesPanel(this::refreshAll, this::openBlame);
        historyPanel = new HistoryPanel(this::refreshAll, this::openBlame);
        stashPanel = new StashPanel(this::refreshAll);
        buildRepoUI();
        branchPanel.setActions(this::newBranchAction,
                () -> Dialogs.mergeDialog(snapshotBranches, this::refreshAll));

        outputPanel = new OutputPanel(); // 构造时绑定 UiLog
        root.setBottom(new VBox(outputPanel, statusBar));
        buildHeader();
        root.setCenter(welcome);

        RepoManager.get().addListener(repo -> requestRefresh());

        // 轻量自动刷新:每 5 秒刷新工作区状态
        Timeline poller = new Timeline(new KeyFrame(Duration.seconds(5), e -> lightRefresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    /** 最大化前的窗口尺寸:最大化状态下关闭时取到的是整个屏幕,存下来下次会撑爆屏幕。 */
    private double preMaxW, preMaxH;

    public void show() {
        double[] fit = fitToScreen(AppSettings.get().windowW(), AppSettings.get().windowH(),
                AppSettings.DEFAULT_WINDOW_W, AppSettings.DEFAULT_WINDOW_H,
                stage.getMinWidth(), stage.getMinHeight());
        preMaxW = fit[0];
        preMaxH = fit[1];
        Scene scene = new Scene(root, fit[0], fit[1]);
        String css = getClass().getResource("/css/theme.css").toExternalForm();
        scene.getStylesheets().add(css);
        if ("dark".equals(AppSettings.get().theme())) {
            root.getStyleClass().add("dark");
        }
        stage.setTitle("EasyGit " + AppVersion.display());
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setScene(scene);
        // 只记录非最大化时的尺寸;最大化瞬间宽高已经变成整屏,要跳过
        stage.widthProperty().addListener((o, ov, nv) -> {
            if (!stage.isMaximized()) preMaxW = nv.doubleValue();
        });
        stage.heightProperty().addListener((o, ov, nv) -> {
            if (!stage.isMaximized()) preMaxH = nv.doubleValue();
        });
        stage.setOnCloseRequest(e -> saveWindowBounds());
        stage.show();
        refreshAll();
    }

    /**
     * 还原窗口尺寸时的屏幕适配。
     * 存储尺寸放得下当前屏幕 -> 原样使用(尊重用户自己调的大小)。
     * 放不下 -> 回退到默认尺寸:多显示器间移动、改过系统缩放比例、或在最大化状态下
     * 关闭,都会把比屏幕还大的尺寸存进设置(实测存到过 2604x1483,而屏幕只有 1536x912),
     * 直接还原会让窗口大到超出屏幕。回退值仍受屏幕可视区的 92% 与最小尺寸约束。
     */
    public static double[] fitToScreen(double w, double h, double defW, double defH,
                                       double minW, double minH) {
        var vb = Screen.getPrimary().getVisualBounds();
        if (w <= vb.getWidth() && h <= vb.getHeight()) {
            return new double[]{Math.max(minW, w), Math.max(minH, h)};
        }
        double maxW = Math.max(minW, vb.getWidth() * 0.92);
        double maxH = Math.max(minH, vb.getHeight() * 0.92);
        return new double[]{Math.min(Math.max(minW, defW), maxW),
                            Math.min(Math.max(minH, defH), maxH)};
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
        // 设计语言:画布 + 浮动卡片 —— 左侧(仓库/分支)一张卡,中部页签一张卡,
        // 顶部命令栏与底部状态条全宽附着,卡片与画布之间留 8px 呼吸缝
        leftBox = new VBox(4, repoPanel, new Separator(), branchPanel);
        leftBox.getStyleClass().add("side-panel");
        leftBox.setPrefWidth(300);
        leftBox.setMinWidth(220);
        centerCard = new VBox(tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.setLeft(leftBox);
        root.setCenter(centerCard);
    }

    /** 头部命令栏:左=仓库切换器,右=按频率分组的动作;低频操作收进「更多」。 */
    private MenuButton repoSwitcher;
    private HBox headerBar;

    private void buildHeader() {
        // 左锚点:仓库切换器(项目控件;品牌名交给系统标题栏,应用内不重复)
        repoSwitcher = new MenuButton("未打开仓库");
        repoSwitcher.getStyleClass().add("repo-switcher");
        refreshRepoSwitcher();

        // 右侧高频组:拉取 / 推送 / 刷新(真实文字钮,不用神秘图标)
        Button pullBtn = new Button("拉取");
        pullBtn.getStyleClass().add("ghost");
        pullBtn.setOnAction(e -> pull());
        Button pushBtn = new Button("推送");
        pushBtn.getStyleClass().add("ghost");
        pushBtn.setOnAction(e -> Dialogs.pushDialog(null, this::refreshAll));
        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("ghost");
        refreshBtn.setOnAction(e -> refreshAll());

        // 低频收纳:抓取 / Blame / LFS / 输出 / 主题
        MenuButton more = new MenuButton("⋯");
        MenuItem fetch = new MenuItem("抓取(fetch)");
        fetch.setOnAction(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) return;
            // 没有远程时给出明确指引,而不是静默无反应
            if (NativeGit.remotes(repo).isEmpty()) {
                Fx.info("抓取", "当前仓库没有配置远程。\n\n可在「推送」对话框里点「管理…」添加远程,添加后会自动抓取。");
                return;
            }
            network("抓取", false, () -> NativeGit.fetch(repo));
        });
        MenuItem blame = new MenuItem("Blame 文件(输入路径)…");
        blame.setOnAction(e -> {
            if (RepoManager.get().current() == null) return;
            String path = Dialogs.askText("Blame", "输入要追溯的文件相对路径:", "");
            if (path != null && !path.isBlank()) openBlame(path.strip());
        });
        MenuItem outputItem = new MenuItem("显示 / 隐藏输出面板");
        outputItem.setOnAction(e -> outputPanel.toggle());
        MenuItem themeItem = new MenuItem("切换亮暗主题");
        themeItem.setOnAction(e -> toggleTheme());
        more.getItems().addAll(fetch, blame, buildLfsMenu(), new SeparatorMenuItem(),
                outputItem, themeItem);

        Button settingsBtn = new Button("设置");
        settingsBtn.getStyleClass().add("ghost");
        settingsBtn.setOnAction(e -> SettingsDialog.show(stage, root));

        // 组间细分隔线:高频组 | 收纳组
        Separator groupSep = new Separator();
        groupSep.setOrientation(javafx.geometry.Orientation.VERTICAL);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, repoSwitcher, spacer,
                pullBtn, pushBtn, refreshBtn, groupSep, more, settingsBtn);
        header.getStyleClass().add("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        headerBar = header;
        root.setTop(header);
    }

    /** 关闭/退出前保存窗口尺寸(最大化瞬间取到的是整屏,回退到最大化前)。 */
    private void saveWindowBounds() {
        AppSettings.get().setWindowSize(
                Math.max(preMaxW, stage.getMinWidth()),
                Math.max(preMaxH, stage.getMinHeight()));
        // 设置是延迟合并落盘的,退出前强制写一次,别把窗口尺寸/当前仓库丢了
        AppSettings.get().saveNow();
    }

    private void toggleTheme() {
        var classes = root.getStyleClass();
        boolean dark = classes.contains("dark");
        if (dark) {
            classes.remove("dark");
            AppSettings.get().setTheme("light");
        } else {
            classes.add("dark");
            AppSettings.get().setTheme("dark");
        }
    }

    /** 仓库切换器:受管仓库列表 + 打开/克隆/初始化。 */
    private void refreshRepoSwitcher() {
        if (repoSwitcher == null) return;
        repoSwitcher.getItems().clear();
        String cur = RepoManager.get().current() == null ? "" : RepoManager.get().current().toString();
        for (AppSettings.RepoEntry e : AppSettings.get().repos()) {
            boolean current = e.path().equals(cur);
            MenuItem mi = new MenuItem((current ? "✓  " : "") + e.name()
                    + (e.group().isBlank() ? "" : "   ·  " + e.group()));
            mi.setOnAction(ev -> {
                if (!RepoManager.get().open(Path.of(e.path()))) {
                    Fx.error("打开失败", "该目录不再是 git 仓库: " + e.path(), null);
                }
            });
            repoSwitcher.getItems().add(mi);
        }
        if (!AppSettings.get().repos().isEmpty()) repoSwitcher.getItems().add(new SeparatorMenuItem());
        MenuItem open = new MenuItem("打开仓库…");
        open.setOnAction(e -> Dialogs.openRepo(stage));
        MenuItem clone = new MenuItem("克隆仓库…");
        clone.setOnAction(e -> Dialogs.cloneRepo(stage));
        MenuItem init = new MenuItem("初始化新仓库…");
        init.setOnAction(e -> Dialogs.initRepo(stage));
        repoSwitcher.getItems().addAll(open, clone, init);
    }

    private void newBranchAction() {
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
    }

    /** LFS 子菜单(收纳进头部「⋯」)。 */
    private javafx.scene.control.Menu buildLfsMenu() {
        javafx.scene.control.Menu lfsMenu = new javafx.scene.control.Menu("Git LFS");
        MenuItem miLfsStatus = new MenuItem("LFS 状态…");
        MenuItem miLfsPull = new MenuItem("拉取 LFS 对象");
        MenuItem miLfsFetch = new MenuItem("抓取 LFS 对象");
        MenuItem miLfsTrack = new MenuItem("管理跟踪规则…");
        MenuItem miLfsPrune = new MenuItem("清理本地对象缓存…");
        MenuItem miLfsInstall = new MenuItem("初始化 Git LFS(git lfs install)");
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
        lfsMenu.getItems().addAll(miLfsStatus, miLfsPull, miLfsFetch, miLfsTrack, miLfsPrune,
                new SeparatorMenuItem(), miLfsInstall);
        return lfsMenu;
    }

    /** 工具栏合并对话框用的分支快照(在刷新时保存)。 */
    private List<BranchInfo> snapshotBranches = List.of();
    private String lastHeadSha;

    /**
     * 网络操作(拉取 / 抓取)。
     *
     * 传进来的 name 是"操作名"(如「拉取」),忙碌/日志/结果提示都由它拼:
     * 之前是拿忙碌文案「拉取中…」反过来拼错误标题,于是失败时弹的是「拉取中…失败」这种怪话。
     *
     * @param reportIncoming 成功后是否报"新增了几个提交"(拉取才有意义;抓取不改 HEAD)
     */
    private void network(String name, boolean reportIncoming,
                         java.util.function.Supplier<GitProcess.GitResult> work) {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg(name + "中…", () -> {
            // HEAD 在后台取(不能用界面里的 lastHeadSha 快照:首屏刷新可能还没轮到它,
            // 那样新增提交数永远是 0,提示就成了错的"已是最新")
            String headBefore = reportIncoming ? NativeGit.headSha(repo) : "";
            GitProcess.GitResult r = work.get();
            int incoming = 0;
            if (reportIncoming && r.ok() && headBefore != null && !headBefore.isBlank()) {
                incoming = NativeGit.countRange(repo, headBefore + "..HEAD");   // 失败返回 -1
            }
            return new NetResult(r, incoming);
        }, res -> {
            GitProcess.GitResult r = res.result();
            UiLog.op("git " + name + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                if (!reportIncoming) {
                    Fx.status(name + "完成");
                } else if (res.incoming() > 0) {
                    Fx.status(name + "完成:新增 " + res.incoming() + " 个提交");
                } else if (res.incoming() == 0) {
                    Fx.status(name + "完成:已是最新");
                } else {
                    Fx.status(name + "完成");   // 提交数没算出来,别乱报"已是最新"
                }
                refreshAll();
            } else {
                Fx.error(name + "失败", r.message(), null);
            }
        });
    }

    private record NetResult(GitProcess.GitResult result, int incoming) {}

    // ---------- 拉取 ----------

    /** 抓取后的现状快照。error 非空表示抓取本身失败。 */
    private record PullPlan(String upstream, int ahead, int behind, int conflicts, boolean dirty, String error) {
        static PullPlan noUpstream() { return new PullPlan(null, 0, 0, 0, false, null); }
        static PullPlan failed(String error) { return new PullPlan(null, 0, 0, 0, false, error); }
    }

    /** 拉取那一步的结果(含冲突数,便于给出"去变更页解决"的引导)。 */
    private record PullStep(GitProcess.GitResult result, int incoming, int conflicts) {}

    /** 暂存后重试的结果。 */
    private record StashRetry(String stashError, String pullError, GitProcess.GitResult pop,
                              int incoming, int conflicts) {}

    /**
     * 拉取:先抓上游,再按「已是最新 / 可快进 / 已分叉」分流。
     *
     * 以前直接 `git pull --no-edit`:本地没有提交时也会产生合并提交,分叉时没有任何选择,
     * 失败时只丢一段英文报错。现在:
     *  - 已是最新 → 不执行合并,直接提示;
     *  - 本地无提交、远端有 → --ff-only 快进,不产生合并提交;
     *  - 已分叉 → 让用户选「合并拉取 / 变基拉取」;
     *  - 被本地未提交改动挡住 → 询问后暂存、重试、自动恢复;
     *  - 冲突/网络/凭据等失败 → 中文提示 + 下一步建议。
     */
    private void pull() {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg("获取远程更新…", () -> {
            String up = NativeGit.upstream(repo);
            if (up == null) return PullPlan.noUpstream();
            GitProcess.GitResult f = NativeGit.fetchUpstream(repo);
            if (!f.ok()) return PullPlan.failed(f.message());
            int[] ab = NativeGit.aheadBehind(repo);
            return new PullPlan(up, ab[0], ab[1], NativeGit.unmergedCount(repo), NativeGit.dirty(repo), null);
        }, plan -> {
            if (plan.error() != null) {
                Fx.error("拉取失败", NativeGit.friendlyError(plan.error()), plan.error());
                return;
            }
            if (plan.upstream() == null) {
                Fx.info("无法拉取", "当前分支还没有设置上游分支,不知道从哪里拉取。\n\n"
                        + "可先「推送」一次(会自动设置上游),或用「抓取」后手动合并。");
                return;
            }
            if (plan.conflicts() > 0) {
                Fx.info("先处理冲突", "当前已有 " + plan.conflicts() + " 个文件处于冲突状态。\n\n"
                        + "解决冲突或「中止合并」之后再拉取。");
                tabs.getSelectionModel().select(0);
                return;
            }
            if (plan.behind() == 0) {
                Fx.status("拉取完成:已是最新");
                refreshAll();
                return;
            }
            if (plan.ahead() == 0) {
                // 纯快进:本地没有额外提交,不该产生合并提交
                runPull(repo, "拉取中…", "拉取", () -> NativeGit.mergeUpstreamFfOnly(repo), false);
                return;
            }
            String mode = Dialogs.pullStrategy(plan.ahead(), plan.behind(), plan.dirty());
            if (mode == null) return;
            boolean rebase = "rebase".equals(mode);
            String name = rebase ? "变基拉取" : "合并拉取";
            runPull(repo, name + "…", name,
                    () -> rebase ? NativeGit.rebaseOntoUpstream(repo) : NativeGit.mergeUpstream(repo), rebase);
        });
    }

    /** 执行合并/变基那一步,并把结果翻译成用户能懂的状态。 */
    private void runPull(Path repo, String busy, String name,
                         java.util.function.Supplier<GitProcess.GitResult> step, boolean rebase) {
        Fx.bg(busy, () -> {
            String headBefore = NativeGit.headSha(repo);
            GitProcess.GitResult r = step.get();
            int incoming = r.ok() && headBefore != null && !headBefore.isBlank()
                    ? NativeGit.countRange(repo, headBefore + "..HEAD") : -1;
            return new PullStep(r, incoming, NativeGit.unmergedCount(repo));
        }, res -> {
            GitProcess.GitResult r = res.result();
            UiLog.op("git " + name + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                reportPullDone(name, res.incoming(), res.conflicts());
                return;
            }
            if (NativeGit.isLocalObstruction(r.message())) {
                if (Fx.confirm(name + "被本地改动挡住",
                        "本地未提交的改动会被覆盖,git 拒绝了本次" + name + "。\n\n"
                                + "先暂存(stash)这些改动,重试" + name + ",成功后再自动恢复?")) {
                    retryPullWithStash(repo, name, step);
                }
                return;
            }
            String hint = NativeGit.friendlyError(r.message());
            if (hint.equals(r.message().strip())) {
                Fx.error(name + "失败", r.message(), null);
            } else {
                Fx.error(name + "失败", hint, r.message());
            }
        });
    }

    /** 暂存本地改动 → 重试拉取 → 自动恢复改动。 */
    private void retryPullWithStash(Path repo, String name,
                                    java.util.function.Supplier<GitProcess.GitResult> step) {
        Fx.bg("暂存改动并重试…", () -> {
            GitProcess.GitResult stash = NativeGit.stashPush(repo, "EasyGit 自动暂存(" + name + ")");
            if (!stash.ok()) return new StashRetry(stash.message(), null, null, -1, 0);
            String headBefore = NativeGit.headSha(repo);
            GitProcess.GitResult r = step.get();
            int incoming = r.ok() && headBefore != null && !headBefore.isBlank()
                    ? NativeGit.countRange(repo, headBefore + "..HEAD") : -1;
            GitProcess.GitResult pop = NativeGit.stashPop(repo);   // 成败都要恢复本地改动
            return new StashRetry(null, r.ok() ? null : r.message(), pop, incoming,
                    NativeGit.unmergedCount(repo));
        }, res -> {
            if (res.stashError() != null) {
                Fx.error("暂存失败", NativeGit.friendlyError(res.stashError()), res.stashError());
                return;
            }
            if (res.pullError() != null) {
                Fx.error(name + "失败", NativeGit.friendlyError(res.pullError()), res.pullError());
                return;
            }
            UiLog.op("git 暂存→" + name + "→恢复 ✓", "", "");
            if (res.pop() != null && !res.pop().ok()) {
                UiLog.op("git stash pop ✖", res.pop().out(), res.pop().err());
                Fx.status(name + "完成,但恢复暂存改动时有冲突,请到「变更」页查看");
                tabs.getSelectionModel().select(0);
                refreshAll();
                return;
            }
            reportPullDone(name, res.incoming(), res.conflicts());
            Fx.status("本地改动已恢复");
        });
    }

    /** 拉取成功后的状态栏文案 + 冲突引导。 */
    private void reportPullDone(String name, int incoming, int conflicts) {
        if (conflicts > 0) {
            Fx.status(name + "完成,但有 " + conflicts + " 个文件冲突,请到「变更」页解决");
            UiLog.line("⚠ " + name + " 产生 " + conflicts + " 个冲突文件");
            tabs.getSelectionModel().select(0);
        } else if (incoming > 0) {
            Fx.status(name + "完成:新增 " + incoming + " 个提交");
        } else if (incoming == 0) {
            Fx.status(name + "完成:已是最新");
        } else {
            Fx.status(name + "完成");
        }
        refreshAll();
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

        // 快捷入口:卡片式 hero(设计语言 v1),替代三个长条按钮
        HBox actions = new HBox(12,
                actionCard("⌂", "打开仓库", "浏览本地已有的 git 仓库", () -> Dialogs.openRepo(stage)),
                actionCard("⇩", "克隆仓库", "从远程 URL 克隆(支持 LFS)", () -> Dialogs.cloneRepo(stage)),
                actionCard("✚", "初始化新仓库", "在空目录创建新的仓库", () -> Dialogs.initRepo(stage)));
        actions.setAlignment(Pos.CENTER);

        Label recentTitle = new Label("我的仓库:");
        recentTitle.getStyleClass().add("h2");
        ListView<AppSettings.RepoEntry> recent = new ListView<>();
        recent.setPrefSize(460, 180);
        recent.setPlaceholder(new Label("还没有仓库,点击上方按钮添加"));
        List<AppSettings.RepoEntry> managed = AppSettings.get().repos();
        recent.getItems().addAll(managed);
        recent.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            private VBox box;
            private Label name, path;
            @Override
            protected void updateItem(AppSettings.RepoEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) { setText(null); setGraphic(null); return; }
                if (box == null) {
                    setText(null);
                    name = new Label();
                    name.getStyleClass().add("file-name");
                    path = new Label();
                    path.getStyleClass().add("row-sub");
                    box = new VBox(1, name, path);
                }
                name.setText((e.group().isBlank() ? "未分组" : e.group()) + " · " + e.name());
                path.setText(e.path());
                setGraphic(box);
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

        Label gitInfo = new Label(AppVersion.full() + "   ·   " + GitProcess.version()
                + "   ·   " + org.easygit.core.GitLocator.describe());
        gitInfo.getStyleClass().add("dim");

        Region spacerTop = new Region();
        Region spacerBottom = new Region();
        VBox.setVgrow(spacerTop, Priority.ALWAYS);
        VBox.setVgrow(spacerBottom, Priority.ALWAYS);
        box.getChildren().addAll(spacerTop, title, sub, new Region(), actions,
                recentTitle, recent, gitInfo, spacerBottom);
        return box;
    }

    /** 欢迎页快捷入口卡片:面板底 + 细描边,hover 描边转 accent。 */
    private VBox actionCard(String glyph, String title, String desc, Runnable action) {
        Label g = new Label(glyph);
        g.getStyleClass().add("action-glyph");
        Label t = new Label(title);
        t.getStyleClass().add("action-title");
        Label d = new Label(desc);
        d.getStyleClass().add("action-desc");
        d.setWrapText(true);
        VBox card = new VBox(6, g, t, d);
        card.getStyleClass().add("action-card");
        card.setPrefWidth(220);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setOnMouseClicked(e -> action.run());
        return card;
    }

    // ---------- 刷新 ----------

    private StatusResult currentStatus;

    /** 已排队的整仓刷新所属仓库代号,-1 表示没有在途的整仓刷新。 */
    private long refreshingEpoch = -1;
    /** 本轮整仓刷新的开始时刻:回调万一被丢弃,也不能让"在途"永远成立(轮询会被永久跳过)。 */
    private long refreshingSince;

    /** 切换通知是否已排队(连续切换时把多次通知合并成一次刷新)。 */
    private boolean refreshQueued;

    /**
     * 仓库切换通知 -> 合并刷新。
     * 连续切换仓库时通知会连着来好几条,而每次通知都发起一轮"状态 + 历史"取数;
     * 合并后只按最终仓库取一次,旧仓库那一轮由 {@link Fx#dropStaleTasks()} 收走。
     */
    private void requestRefresh() {
        if (refreshQueued) return;
        refreshQueued = true;
        Platform.runLater(() -> {
            refreshQueued = false;
            refreshAll();
        });
    }

    private void refreshAll() {
        // 旧仓库还在跑/排队的刷新立刻作废:任务不再启动,结果不再回投
        Fx.dropStaleTasks();
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) {
            loadedRepoPath = null;
            refreshingEpoch = -1;
            root.setLeft(null);
            root.setCenter(welcome);
            if (repoSwitcher != null) repoSwitcher.setText("未打开仓库");
            statusBar.updateRepo(null, null, false, 0, 0);
            statusBar.updateCounts(0, 0, 0, 0);
            statusBar.updateLfs(false, 0);
            refreshWelcomeList();
            return;
        }
        refreshingEpoch = RepoManager.get().epoch(); // 本轮整仓刷新的代号(轮询期间不再叠加)
        refreshingSince = System.currentTimeMillis();
        root.setLeft(leftBox);
        if (root.getCenter() != centerCard) root.setCenter(centerCard);

        // 头部:仓库切换器跟随当前仓库
        String repoName = repo.getFileName() == null ? repo.toString() : repo.getFileName().toString();
        String group = AppSettings.get().repos().stream()
                .filter(r -> r.path().equals(repo.toString()))
                .map(r -> r.group()).findFirst().orElse("");
        repoSwitcher.setText(group.isBlank() ? repoName : repoName + " · " + group);
        repoSwitcher.setTooltip(new Tooltip(repo.toString()));
        refreshRepoSwitcher();

        // 切换到不同仓库时:立即清掉上一个仓库的内容与 Blame 页签
        if (!repo.toString().equals(loadedRepoPath)) {
            loadedRepoPath = repo.toString();
            lastHeadSha = null;
            currentStatus = null;
            tabs.getTabs().removeIf(t -> t.getText().startsWith("Blame:"));
            changesPanel.onRepoSwitched();
            historyPanel.onRepoSwitched();
            branchPanel.refresh(List.of());
            stashPanel.refresh(List.of());
            // 状态栏先归零:否则新仓库的数据到位前,下面显示的还是上一个仓库的分支/领先落后
            statusBar.updateRepo(repo.toString(), null, false, 0, 0);
            statusBar.updateCounts(0, 0, 0, 0);
            statusBar.updateLfs(false, 0);
            Fx.status("已切换到 " + repoName);
        }

        boolean allBranches = historyPanel.allBranchesSelected();
        int maxCommits = AppSettings.get().maxCommits();

        // 阶段一(快):状态 / 分支 / stash / LFS 缓存状态 —— 让界面先可用
        Fx.bg("刷新仓库状态…", guard, () -> {
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
            refreshingEpoch = -1;
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
        Fx.bg("读取提交历史…", guard, () -> {
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
        // 整仓刷新(切换仓库/手动刷新)还在途时跳过这一拍,别让轮询去和首屏取数抢线程;
        // 但"在途"最多认 10 秒——否则一个被丢弃的回调会让轮询永久停摆(界面看着就像卡住了)
        if (RepoManager.get().epoch() == refreshingEpoch
                && System.currentTimeMillis() - refreshingSince < 10_000) return;
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) return;
        Fx.bg("刷新状态…", guard, () -> {
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
