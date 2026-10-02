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
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
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
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;
import org.easygit.ui.base.RepoGuard;
import org.easygit.ui.base.StatusBar;
import org.easygit.ui.base.HeaderBar;
import org.easygit.ui.base.WelcomeView;
import org.easygit.ui.panels.ChangesPanel;
import org.easygit.ui.panels.HistoryPanel;
import org.easygit.ui.panels.BranchPanel;
import org.easygit.ui.panels.RepoPanel;
import org.easygit.ui.panels.StashPanel;
import org.easygit.ui.panels.OutputPanel;
import org.easygit.ui.views.BlameView;
import org.easygit.ui.panels.PullFlow;
import org.easygit.ui.panels.PushFlow;
import org.easygit.ui.dialogs.Dialogs;
import org.easygit.ui.dialogs.SettingsDialog;

/**
 * 主窗口:装配命令栏 + 左侧仓库/分支 + 中部页签(变更/历史/Stash)+ 状态栏,并编排刷新。
 *
 * 具体职责已外包,这里只做装配与刷新编排:
 *  - 命令栏 {@link HeaderBar}(含仓库切换器 / LFS 菜单 / 主题)
 *  - 欢迎页 {@link WelcomeView}
 *  - 拉取流程 {@link PullFlow}
 *  - 各面板在 {@code org.easygit.ui.panels},纯展示组件在 {@code org.easygit.ui.views},
 *    对话框在 {@code org.easygit.ui.dialogs},基础件在 {@code org.easygit.ui.base}
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
    private final OutputPanel outputPanel;
    private final HeaderBar headerBar;
    private final PullFlow pullFlow;
    private final PushFlow pushFlow;
    /** 未打开仓库时内容区显示的欢迎页;每次回到该状态时重建(仓库列表可能已变)。 */
    private VBox welcome;
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

        pullFlow = new PullFlow(new PullFlow.Host() {
            @Override public void refreshAll() { MainWindow.this.refreshAll(); }
            @Override public void showChangesTab() { tabs.getSelectionModel().select(0); }
        });
        pushFlow = new PushFlow(new PushFlow.Host() {
            @Override public void refreshAll() { MainWindow.this.refreshAll(); }
            @Override public void openPushDialog(PushFlow.Preflight pre) {
                Dialogs.pushDialog(null, MainWindow.this::refreshAll, pre, pullFlow);
            }
        }, pullFlow);
        headerBar = new HeaderBar(stage, new HeaderBar.Actions() {
            @Override public void pull() { pullFlow.pull(); }
            @Override public void push() { pushFlow.push(); }
            @Override public void refresh() { MainWindow.this.refreshAll(); }
            @Override public void fetch() { fetchNow(); }
            @Override public void blame() { blamePrompt(); }
            @Override public void toggleOutput() { outputPanel.toggle(); }
            @Override public void toggleTheme() { MainWindow.this.toggleTheme(); }
        });
        headerBar.rebuildRepoSwitcher(stage);
        root.setTop(headerBar);

        welcome = buildWelcome();
        root.setCenter(welcome);

        RepoManager.get().addListener(repo -> requestRefresh());

        // 轻量自动刷新:每 5 秒刷新工作区状态。
        // 窗口不在前台时降到 15 秒一次:一轮轮询要起 2 个 git 进程(指纹 + status),
        // 用户没在看的时候纯属白烧磁盘和 CPU,还会跟当前前台的应用抢 IO。
        // 回到前台立刻刷一次,所以降频不会让用户看到过期数据。
        Timeline poller = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            if (stage.isFocused()) {
                idleTicks = 0;
                lightRefresh();
            } else if (++idleTicks % 3 == 0) {
                lightRefresh();
            }
        }));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        // 引用一变就置脏(见 refreshAll/lightRefresh),真正加载推迟到「历史」页可见时。
        // 用面板自己的加载入口:它会带上文件历史筛选与「所有分支」勾选状态。
        tabs.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv == historyTab && historyDirty) {
                historyDirty = false;
                historyPanel.refresh();
            } else if (nv == stashTab && stashDirty) {
                stashDirty = false;
                loadStashes(RepoGuard.capture(), RepoManager.get().current());
            }
        });
        stage.focusedProperty().addListener((o, ov, nv) -> {
            if (nv) lightRefresh();
        });
    }

    /** 窗口不在前台时累计的轮询节拍(每 3 拍才刷一次)。 */
    private int idleTicks;

    /** 欢迎页(每次重建,保证最近仓库列表是最新的)。 */
    private VBox buildWelcome() {
        return WelcomeView.build(
                () -> Dialogs.openRepo(stage),
                () -> Dialogs.cloneRepo(stage),
                () -> Dialogs.initRepo(stage));
    }

    /** 「⋯」菜单里的抓取:没有远程时给出明确指引,而不是静默无反应。 */
    private void fetchNow() {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        if (NativeGit.remotes(repo).isEmpty()) {
            Fx.info("抓取", "当前仓库没有配置远程。\n\n可在「推送」对话框里点「管理…」添加远程,添加后会自动抓取。");
            return;
        }
        network("抓取", false, () -> NativeGit.fetch(repo));
    }

    /** 「⋯」菜单里的 Blame:输入路径后打开 Blame 页签。 */
    private void blamePrompt() {
        if (RepoManager.get().current() == null) return;
        String path = Dialogs.askText("Blame", "输入要追溯的文件相对路径:", "");
        if (path != null && !path.isBlank()) openBlame(path.strip());
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

    /** 工具栏合并对话框用的分支快照(在刷新时保存)。 */
    private List<BranchInfo> snapshotBranches = List.of();
    /** 上一次轮询看到的引用指纹(HEAD + 所有分支/标签),用于判断历史页要不要重载。 */
    private String lastRefsFingerprint;

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
            headerBar.setRepo("未打开仓库", "");
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
        headerBar.setRepo(group.isBlank() ? repoName : repoName + " · " + group, repo.toString());
        headerBar.rebuildRepoSwitcher(stage);

        // 切换到不同仓库时:立即清掉上一个仓库的内容与 Blame 页签
        if (!repo.toString().equals(loadedRepoPath)) {
            loadedRepoPath = repo.toString();
            lastRefsFingerprint = null;   // 新仓库:下一次轮询强制重载历史
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
            // 看过的仓库:先用上次的快照把界面填上(stale-while-revalidate),
            // 不用再等 status + for-each-ref 两个进程(实测 350~500ms);后台刷新一到就替换
            paintSnapshot(repo, repoName);
        }

        boolean allBranches = historyPanel.allBranchesSelected();
        int maxCommits = AppSettings.get().maxCommits();

        // 阶段一(快):状态 / 分支+引用指纹 —— 让界面先可用
        Fx.bg("刷新仓库状态…", guard, () -> {
            StatusResult st;
            NativeGit.RefSnapshot refs;
            try {
                st = NativeGit.status(repo);
                // 分支列表与引用指纹共用一次 for-each-ref:两个进程并一个(见 NativeGit.refSnapshot)
                refs = NativeGit.refSnapshot(repo);
            } catch (Exception ex) {
                // 后台刷新失败不打扰用户,仅记录输出面板
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
            return new RefreshData(st, refs.branches(), refs.fingerprint());
        }, data -> {
            refreshingEpoch = -1;
            if (data == null) return;
            applyStatus(repo, data.status, data.branches, data.refs);
            rememberSnapshot(repo, data.status, data.branches, data.refs);
        });

        // 阶段一之三:LFS 状态单独一个任务。
        // 只有 status 栏那个 LFS 徽标需要它,而启用 LFS 的仓库光 git lfs ls-files 就要 1.6s 起
        // (git-lfs 自身启动;大仓库冷启实测 14.9s),串在阶段一里会拖着状态/变更/分支一起等。
        // 它自己也带时间预算,超时按"数量未知"处理(见 LfsService.lsFilesChecked)。
        Fx.bg("读取 LFS 状态…", guard, () -> org.easygit.core.LfsService.cachedRepoState(repo),
                lfs -> statusBar.updateLfs(lfs[0] == 1, lfs[1]));

        // 阶段一之二:暂存列表 —— 同样只在「Stash」页可见时才取。
        // 首次打开仓库时 JGit 要做类初始化 + 读仓库(实测 ~1.1s,之后 4~9ms),
        // 而暂存列表是另一个页签的数据:停在「变更」页时没必要为它付这一次。
        if (stashTab.isSelected()) {
            loadStashes(guard, repo);
            stashDirty = false;
        } else {
            stashDirty = true;
        }

        // 阶段二(慢,并行):提交历史 + 未推送标记 —— **只在「历史」页可见时才加载**。
        // 停在「变更」页时,把 2000 条历史、未推送集合(2 个 git 进程)以及首个提交的差异
        // 一起算出来是纯浪费:每次切仓/刷新都要付一遍构图 + 列表重建 + 一次 diff。
        // 引用一变就置脏,用户切到「历史」页时立刻补一次(见构造里的页签监听)。
        if (historyTab.isSelected()) {
            loadHistory(guard, repo, allBranches, maxCommits);
            historyDirty = false;
        } else {
            historyDirty = true;
        }
    }

    /** 历史页数据是否已失效(切了仓库或引用变了),等用户切到「历史」页再加载。 */
    private boolean historyDirty = true;

    /** 暂存列表是否已失效,等用户切到「Stash」页再取(见 refreshAll)。 */
    private boolean stashDirty = true;

    /** 取暂存列表(唯一的消费者是 StashPanel)。 */
    private void loadStashes(RepoGuard guard, Path repo) {
        if (repo == null) return;
        Fx.bg("读取暂存列表…", guard, () -> {
            try {
                return new JGitService(repo).stashList();
            } catch (Exception e) {
                return List.<StashEntry>of();
            }
        }, stashPanel::refresh);
    }

    /** 加载提交历史 + 未推送标记(唯一的后处理点仍是 HistoryPanel.setCommits)。 */
    private void loadHistory(RepoGuard guard, Path repo, boolean allBranches, int maxCommits) {
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
                // 指纹覆盖所有引用,而不只是 HEAD:勾选「所有分支」时侧支/远程分支
                // 的新提交不动 HEAD,只看 HEAD 会让历史页永远不刷新(用户必须手动点)。
                String refs = NativeGit.refsFingerprint(repo);
                StatusResult st = NativeGit.status(repo);
                return new LightData(st, refs);
            } catch (Exception ex) {
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
        }, data -> {
            if (data == null) return;
            // 引用发生变化(新提交/amend/检出/新分支/远程更新)→ 重载历史;
            // 但用户没在看「历史」页时只置脏,别为了看不见的列表起 2 个 git 进程
            if (lastRefsFingerprint == null || !data.refs().equals(lastRefsFingerprint)) {
                lastRefsFingerprint = data.refs();
                if (historyTab.isSelected()) historyPanel.refresh();
                else historyDirty = true;
            }
            currentStatus = data.st();
            changesPanel.refresh(data.st());
            // 轮询这条路径也要记快照:启动时仓库会被再选一次(旧 guard 作废、首屏整仓刷新的回调被丢弃),
            // 界面上的数据其实来自轮询 —— 只在那里记快照的话,切回来就"没东西可先显示"(实测两次挂一次)
            rememberSnapshot(repo, data.st(), snapshotBranches, data.refs());
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

    private record RefreshData(StatusResult status, List<BranchInfo> branches, String refs) {}

    /** 一个仓库最近一次的「阶段一」数据(状态 + 分支 + 引用指纹)。 */
    private record Snapshot(StatusResult status, List<BranchInfo> branches, String refs) {}

    /** 记住的仓库快照(按最近使用排序,超过上限丢最旧的):切回时先上屏用。 */
    private final java.util.LinkedHashMap<String, Snapshot> snapshots =
            new java.util.LinkedHashMap<>(16, 0.75f, true);
    private static final int SNAPSHOT_MAX = 8;

    /**
     * 把一份「阶段一」数据套到界面上(新取的或上次缓存的都走这里)。
     * 计数口径只写一处,免得缓存路径和新数据路径对不上。
     */
    private void applyStatus(Path repo, StatusResult st, List<BranchInfo> branches, String refs) {
        lastRefsFingerprint = refs;
        currentStatus = st;
        snapshotBranches = branches;
        changesPanel.refresh(st);
        branchPanel.refresh(branches);

        int staged = 0, unstaged = 0, untracked = 0, conflicts = 0;
        for (var f : st.changes()) {
            if (f.unmerged) conflicts++;
            else if (f.untracked) untracked++;
            if (f.indexState != ' ' && f.indexState != '?') staged++;
            if (!f.untracked && !f.unmerged && f.wtState != ' ') unstaged++;
        }
        statusBar.updateRepo(repo.toString(), st.branch(), st.detached(), st.ahead(), st.behind());
        statusBar.updateCounts(staged, unstaged, untracked, conflicts);
    }

    /**
     * 切回看过的仓库:先用上次快照把界面填上(stale-while-revalidate)。
     *
     * 这样切仓的感知延迟从「等两个 git 进程(350~500ms)」降到「立刻可见」,
     * 后台刷新一到就替换成最新值;指纹也一并对齐,轮询能照常发现离开期间的变化。
     */
    private void paintSnapshot(Path repo, String repoName) {
        Snapshot snap = snapshots.get(repo.toString());
        if (snap == null) return;
        applyStatus(repo, snap.status(), snap.branches(), snap.refs());
        Fx.status("已切换到 " + repoName + "(先显示上次快照,正在刷新…)");
    }

    private void rememberSnapshot(Path repo, StatusResult st, List<BranchInfo> branches, String refs) {
        snapshots.put(repo.toString(), new Snapshot(st, branches, refs));
        while (snapshots.size() > SNAPSHOT_MAX) {
            snapshots.remove(snapshots.keySet().iterator().next());
        }
    }

    private record HistoryData(List<CommitEntry> log, java.util.Set<String> unpushed) {}

    private record LightData(StatusResult st, String refs) {}
}
