package org.easygit.ui;

import org.easygit.core.AppSettings;
import org.easygit.core.AppVersion;
import org.easygit.core.GitProcess;
import org.easygit.core.JGitService;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.StatusParser.StatusResult;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.RepoSnapshot;
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
        // 面板自己的加载路径(勾选「所有分支」/文件筛选/切到本页)也要记快照,
        // 否则切回时"没东西可先画"(与阶段一快照同一个教训)。
        // 指纹由主窗口提供:面板在**发起加载时**取一次,宿主据此判断这份缓存是否仍然有效。
        historyPanel.setRefsSupplier(() -> lastRefsFingerprint);
        historyPanel.setOnLoaded((log, unpushed, refsAtLoad) -> rememberHistory(
                RepoManager.get().current(), log, unpushed, refsAtLoad, AppSettings.get().maxCommits()));
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
                // 推送对话框里可以增删改远程:刷新前先作废分支面板的"是否有远程"缓存
                Dialogs.pushDialog(null, () -> {
                    branchPanel.invalidateRemotesCache();
                    MainWindow.this.refreshAll();
                }, pre, pullFlow);
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
        // 连点仓库:手停下来之后才为新仓库取数(见 requestRefresh / SETTLE_MS)
        settling.setOnFinished(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) {
                refreshAll();     // 已经切空了:按空态收尾
                return;
            }
            startFetch(repo);
        });

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
        stage.setOnCloseRequest(e -> {
            saveWindowBounds();
            // 关窗前把当前仓库的快照落盘:下次开程序就能先把上次的样子画出来。
            // 写盘已挪到后台线程,这里要**等它写完**再退,否则这一份就白写了。
            persistSnapshots(RepoManager.get().current());
            org.easygit.core.SnapshotStore.flush(1500);
        });
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
    /**
     * 当前模式下最后一次看到的**历史指纹**,用于判断历史页要不要重载。
     * 按模式收窄(见 {@link NativeGit#headFingerprint}):「所有分支」= 全部引用的指纹,
     * 平时 = HEAD(+未推送集合)的指纹。两条取数路径(轮询/阶段一)必须用同一种格式,
     * 否则会互相把对方当成"引用变了",每拍白重载一次历史。
     */
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

    /** 切换通知是否已排队(同一拍里的多次通知只换一次皮)。 */
    private boolean refreshQueued;

    /**
     * 取数是否还没落定:有一轮在后台跑,或者已经排了延迟取数。
     * 连续切仓期间它一直是 true —— 这正是"用户还在连点"的判据。
     */
    private boolean fetchPending;

    /** 上一次整仓取数**完成**的时刻(初始 0 = 还没取过)。 */
    private long fullRefreshDoneAt;
    /** 整仓取数刚完成后的这段时间里让轮询让路:那一拍必然是重复的。 */
    private static final long POLL_AFTER_REFRESH_MS = 1_500;

    /**
     * 连点仓库时的"停稳"窗口:上一轮取数还没回来就又切了仓库,说明用户还在找东西,
     * 这时**先不为新仓库起 git 进程**,等手停下来再取。
     *
     * 为什么值得等:6 次连点里前 5 次的结果注定要被丢掉(切走就作废),却各自起了 2 个
     * git 子进程(大仓库上还要扫工作区)。合并成一次,省下的是磁盘 IO,不只是时间。
     * 单次切换时什么都没有在途,走的是"立刻取"那条路 —— 不为此付任何延迟。
     */
    private static final double SETTLE_MS = 120;
    private final javafx.animation.PauseTransition settling =
            new javafx.animation.PauseTransition(Duration.millis(SETTLE_MS));

    /**
     * 仓库切换通知 -> 立刻换皮,取数按"是否还在连点"决定立刻取还是停稳再取。
     *
     * 注意换皮(清界面 + 快照上屏)必须**同步、立刻**做:用户已经点到别的仓库了,
     * 界面上还挂着上一个仓库的数据就是"串仓",哪怕只挂 100ms 也不行。
     */
    private void requestRefresh() {
        if (refreshQueued) return;
        refreshQueued = true;
        Platform.runLater(() -> {
            refreshQueued = false;
            boolean wasPending = fetchPending;
            fetchPending = true;             // 连点期间保持"取数没落定"
            Fx.dropStaleTasks();             // 旧仓库在途的取数立刻收回(它已经没用了)
            Path repo = RepoManager.get().current();
            if (repo == null) {
                refreshAll();                // 仓库被关掉:走空态,顺带清掉延迟取数
                return;
            }
            paintSwitch(repo);               // 同步换皮 + 快照上屏
            if (wasPending) {
                settling.playFromStart();    // 还在连点:每切一次就顺延,手停下来再取
            } else {
                startFetch(repo);            // 单个切换:立刻取,不额外等
            }
        });
    }

    /** 手动刷新 / 操作完成后刷新:换皮(若切了仓库)+ 立刻取数。 */
    private void refreshAll() {
        // 旧仓库还在跑/排队的刷新立刻作废:任务不再启动,结果不再回投
        Fx.dropStaleTasks();
        settling.stop();
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) {
            fetchPending = false;
            loadedRepoPath = null;
            refreshingEpoch = -1;
            clearSwitchHint();   // 仓库都关了,切换提示不能留着
            root.setLeft(null);
            root.setCenter(welcome);
            headerBar.setRepo("未打开仓库", "");
            statusBar.updateRepo(null, null, false, 0, 0);
            statusBar.updateCounts(0, 0, 0, 0);
            statusBar.updateLfs(false, 0);
            refreshWelcomeList();
            return;
        }
        paintSwitch(repo);
        startFetch(repo);
    }

    /**
     * 换皮:当前仓库与界面上那份不同时,立刻把上一个仓库的内容清掉、用快照把新仓库填上。
     * 只做同步的、便宜的事(内存快照;磁盘快照的解析也是同步的 —— 首帧就得有内容,
     * 那是持久化的意义所在),真正的取数在 {@link #startFetch} 里。
     */
    private void paintSwitch(Path repo) {
        if (repo.toString().equals(loadedRepoPath)) return;   // 还是这个仓库:不必重新换皮
        // 离开旧仓库前把它的快照落盘(下次重开程序就能先画出来)。写盘在后台线程,见 persistSnapshots
        if (loadedRepoPath != null) persistSnapshots(java.nio.file.Path.of(loadedRepoPath));
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

        root.setLeft(leftBox);
        if (root.getCenter() != centerCard) root.setCenter(centerCard);

        // 头部:仓库切换器跟随当前仓库
        String repoName = repo.getFileName() == null ? repo.toString() : repo.getFileName().toString();
        String group = AppSettings.get().repos().stream()
                .filter(r -> r.path().equals(repo.toString()))
                .map(r -> r.group()).findFirst().orElse("");
        headerBar.setRepo(group.isBlank() ? repoName : repoName + " · " + group, repo.toString());
        headerBar.rebuildRepoSwitcher(stage);

        // 切换提示是**临时**的:这一轮数据到位后由 clearSwitchHint() 撤掉。
        // (状态栏消息是"常驻到下一次消息"的,不主动撤掉就会一直挂着「正在刷新…」)
        switchHint = "已切换到 " + repoName;
        Fx.status(switchHint);
        // 看过的仓库:先用上次的快照把界面填上(stale-while-revalidate),
        // 不用再等取数(实测 350~500ms);后台刷新一到就替换。
        // 快照来源有两处:本次运行的内存缓存,和上次运行落盘的磁盘快照(重开程序也能立刻上屏)
        seedSnapshotsFromDisk(repo);
        paintSnapshot(repo, repoName);
        paintHistorySnapshot(repo);
        // 取数尚未开始(可能还要等"停稳"),先把轮询压住:别让 5 秒那一拍插进来抢 IO
        refreshingEpoch = RepoManager.get().epoch();
        refreshingSince = System.currentTimeMillis();
    }

    /** 真正去取数(阶段一 + LFS + 按需的历史/暂存)。 */
    private void startFetch(Path repo) {
        settling.stop();
        fetchPending = true;
        RepoGuard guard = RepoGuard.capture();
        refreshingEpoch = RepoManager.get().epoch(); // 本轮整仓刷新的代号(轮询期间不再叠加)
        refreshingSince = System.currentTimeMillis();

        boolean allBranches = historyPanel.allBranchesSelected();
        int maxCommits = AppSettings.get().maxCommits();

        // 阶段一(快):状态 / 分支+引用指纹 —— 让界面先可用。
        // 两条命令在核心层并行取(见 NativeGit.readState):切仓时省下差不多一个进程的等待。
        Fx.bg("刷新仓库状态…", guard, () -> {
            try {
                return NativeGit.readState(repo);
            } catch (Exception ex) {
                // 后台刷新失败不打扰用户,仅记录输出面板
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
        }, data -> {
            refreshingEpoch = -1;
            fetchPending = false;        // 这一轮取数结束了(成功或失败)
            // 记下"刚整仓取过数":轮询用这个时刻避开紧跟其后的重复一拍(见 lightRefresh)
            fullRefreshDoneAt = System.currentTimeMillis();
            // 这一轮刷新已经结束(成功或失败),切换时的临时提示该撤了
            clearSwitchHint();
            if (data == null) return;
            // 历史指纹按**模式**收窄:「所有分支」用全部引用的指纹;平时只用 HEAD(+未推送集合),
            // status 自带、一个进程都不多起。轮询与这里必须用同一种格式,否则会互相触发假重载。
            String fp = allBranches ? data.fingerprint() : NativeGit.headFingerprint(data.status());
            applyStatus(repo, data.status(), data.branches(), fp);
            rememberSnapshot(repo, data.status(), data.branches(), fp, allBranches);

            // 提交历史:这里才拿到新的引用指纹,据此决定要不要重取。
            // 指纹 / 条数上限 / 「所有分支」/ 文件筛选都没变 ⇒ 缓存就是最新的,**一次 git 都不起**;
            // 变了才重载(「历史」页可见就立刻重载,不可见只置脏,等切到该页再补)。
            boolean historyFresh = historyCacheMatches(repo, fp, allBranches, maxCommits);
            if (historyTab.isSelected()) {
                if (!historyFresh) loadHistory(guard, repo, allBranches, maxCommits);
                historyDirty = false;
            } else {
                historyDirty = !historyFresh;
            }
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

        // 提交历史:**是否重取**的判定挪到阶段一回调里(见上面 applyStatus 之后),
        // 因为只有那里才拿得到本次刷新的引用指纹 —— 指纹没变就一次 git 都不起。
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

    /** 加载提交历史 + 未推送标记(后处理统一在 HistoryPanel.setCommits 里)。 */
    private void loadHistory(RepoGuard guard, Path repo, boolean allBranches, int maxCommits) {
        final String refsAtLoad = lastRefsFingerprint;
        // log 与 rev-list 在核心层并行取(见 NativeGit.readHistory):省下差不多一个进程的等待
        Fx.bg("读取提交历史…", guard, () -> NativeGit.readHistory(repo, maxCommits, allBranches, null),
                data -> {
                    rememberHistory(repo, data.log(), data.unpushed(), refsAtLoad, maxCommits);
                    historyPanel.setCommits(data.log(), data.unpushed());
                });
    }

    private void lightRefresh() {
        // 整仓刷新(切换仓库/手动刷新)还在途时跳过这一拍,别让轮询去和首屏取数抢线程;
        // 但"在途"最多认 10 秒——否则一个被丢弃的回调会让轮询永久停摆(界面看着就像卡住了)
        if (RepoManager.get().epoch() == refreshingEpoch
                && System.currentTimeMillis() - refreshingSince < 10_000) return;
        // 刚整仓取完数(切仓/手动刷新)马上又来一拍轮询是纯重复:数据刚取回来,这一拍还是那 2 个进程。
        // 连续切仓时"停稳 → 取数 → 轮询又立刻补一拍"正好撞在这个窗口里。
        if (System.currentTimeMillis() - fullRefreshDoneAt < POLL_AFTER_REFRESH_MS) return;
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) return;
        boolean allBranches = historyPanel.allBranchesSelected();
        Fx.bg("刷新状态…", guard, () -> {
            try {
                if (allBranches) {
                    // 「所有分支」:侧支/远程分支的新提交不动 HEAD,得盯全部引用;
                    // 指纹与状态互不依赖 —— 并行取,轮询这一拍也少等一个进程
                    org.easygit.core.Parallel.Both<String, StatusResult> both = org.easygit.core.Parallel.both(
                            () -> NativeGit.refsFingerprint(repo), () -> NativeGit.status(repo));
                    return new LightData(both.second(), both.first());
                }
                // 平时(没勾「所有分支」):历史与未推送集合只由 HEAD + 上游决定,
                // 指纹用 status 自带的 branch.oid / branch.ab —— **少起一个 git 进程**;
                // 别的工具建分支/打标签/抓远程也不会再被当成"历史变了"触发整份重载
                StatusResult st = NativeGit.status(repo);
                return new LightData(st, NativeGit.headFingerprint(st));
            } catch (Exception ex) {
                UiLog.line("✖ 读取仓库状态失败: " + ex.getMessage());
                return null;
            }
        }, data -> {
            if (data == null) return;
            // 历史相关的引用变了(新提交/amend/检出/上游移动)→ 重载历史;
            // 但用户没在看「历史」页时只置脏,别为了看不见的列表起 git 进程
            if (lastRefsFingerprint == null || !data.refs().equals(lastRefsFingerprint)) {
                lastRefsFingerprint = data.refs();
                if (historyTab.isSelected()) historyPanel.refresh();
                else historyDirty = true;
            }
            currentStatus = data.st();
            changesPanel.refresh(data.st());
            // 轮询这条路径也要记快照:启动时仓库会被再选一次(旧 guard 作废、首屏整仓刷新的回调被丢弃),
            // 界面上的数据其实来自轮询 —— 只在那里记快照的话,切回来就"没东西可先显示"(实测两次挂一次)
            rememberSnapshot(repo, data.st(), snapshotBranches, data.refs(), allBranches);
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

    /**
     * 一个仓库最近一次的「阶段一」数据:状态 + 分支 + **当时的历史指纹与模式**。
     *
     * 指纹按模式收窄(见 {@link NativeGit#headFingerprint}):「所有分支」是全部引用的指纹,
     * 平时是 HEAD(+未推送集合)的指纹 —— 上屏时只有**模式相同**的快照才能当基线,
     * 模式对不上宁可没有基线(下一拍轮询重载一次),也不能拿错格式的指纹把历史缓存判成"仍然有效"。
     */
    private record Snapshot(StatusResult status, List<BranchInfo> branches, String refs,
                            boolean allBranches, long savedAt) {}

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
     * 后台刷新一到就替换成最新值;基线指纹也一并对齐(模式相同时),轮询能照常发现离开期间的变化。
     */
    private void paintSnapshot(Path repo, String repoName) {
        Snapshot snap = snapshots.get(repo.toString());
        if (snap == null) return;
        boolean allBranches = historyPanel.allBranchesSelected();
        // 基线指纹必须和当前模式是**同一种格式**:快照是哪个模式存的就用哪种;
        // 模式对不上宁可没有基线(下一拍轮询会重载一次),也不能拿错格式的指纹。
        applyStatus(repo, snap.status(), snap.branches(),
                snap.allBranches() == allBranches ? snap.refs() : null);
        // 提示里带上"这份快照是多久之前的":持久化之后快照可能来自上一次运行,不说明白就会让人以为界面卡住了
        switchHint = "已切换到 " + repoName + "（先显示" + ageText(snap.savedAt()) + "的快照，正在刷新…）";
        Fx.status(switchHint);
    }

    /** 快照年龄的粗粒度说法。 */
    private static String ageText(long savedAt) {
        long ms = Math.max(0, System.currentTimeMillis() - savedAt);
        long min = ms / 60_000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 24) return h + " 小时前";
        return (h / 24) + " 天前";
    }

    /** 本次切换留下的临时提示(数据到位后要撤掉);null 表示没有。 */
    private String switchHint;

    /**
     * 撤掉切换时的临时提示。
     * 状态栏消息是常驻到下一次消息的,不主动撤就会一直挂着「正在刷新…」(用户实测反馈)。
     * 只撤**我们自己留下的那条**:期间若有别的操作写了新消息(如「提交成功 abc1234」),不动它。
     */
    private void clearSwitchHint() {
        String hint = switchHint;
        switchHint = null;
        if (hint != null && hint.equals(Fx.lastMessage())) Fx.status("");
    }

    private void rememberSnapshot(Path repo, StatusResult st, List<BranchInfo> branches,
                                  String refs, boolean allBranches) {
        snapshots.put(repo.toString(), new Snapshot(st, branches, refs, allBranches, System.currentTimeMillis()));
        while (snapshots.size() > SNAPSHOT_MAX) {
            snapshots.remove(snapshots.keySet().iterator().next());
        }
    }

    /** 一个仓库最近一次的提交历史(含未推送标记、加载时的引用指纹与筛选条件)。 */
    private record HistorySnapshot(List<CommitEntry> log, Set<String> unpushed, String refs,
                                   int maxCommits, boolean allBranches, String pathFilter,
                                   long savedAt) {}

    /** 记住的提交历史快照(按最近使用排序,上限 8 个仓库 —— 与状态快照的 LRU 对齐)。 */
    private final java.util.LinkedHashMap<String, HistorySnapshot> historySnapshots =
            new java.util.LinkedHashMap<>(16, 0.75f, true);
    /**
     * 历史快照的上限:**必须装得下用户管理的所有仓库**。原来只留 4 个,而仓库列表里有 6~7 个,
     * 切来切去必然每次都把最早的挤出去 —— 切回时历史就得整份重载(实测 157~174ms,旧版串行要 340ms),
     * 这正是"历史页太慢"的一大来源。2000 条提交的历史快照也就 ~300KB,8 个 = 2.4MB,不值得省。
     */
    private static final int HISTORY_SNAPSHOT_MAX = 8;

    /** 记一份历史快照。两条加载路径(主窗口整仓刷新、面板自己的刷新)都要走这里。 */
    private void rememberHistory(Path repo, List<CommitEntry> log, Set<String> unpushed,
                                 String refs, int maxCommits) {
        if (repo == null) return;
        historySnapshots.put(repo.toString(), new HistorySnapshot(log, unpushed, refs, maxCommits,
                historyPanel.allBranchesSelected(), historyPanel.pathFilter(), System.currentTimeMillis()));
        while (historySnapshots.size() > HISTORY_SNAPSHOT_MAX) {
            historySnapshots.remove(historySnapshots.keySet().iterator().next());
        }
    }

    /**
     * 从磁盘快照播种内存缓存(只在内存里没有这个仓库时)。
     *
     * 这是"持久化"的入口:重开程序后切到某个仓库(或直接启动进它)时,先用上次运行留下的样子把界面画上,
     * 不必再从空界面等一轮 git。加载不出任何东西(没有、损坏、过期、版本不符)就照常走刷新。
     */
    private void seedSnapshotsFromDisk(Path repo) {
        if (repo == null) return;
        if (snapshots.containsKey(repo.toString()) || historySnapshots.containsKey(repo.toString())) return;
        var saved = org.easygit.core.SnapshotStore.load(repo);
        if (saved.isEmpty()) return;
        var ps = saved.get();
        var st = ps.status();
        // 落盘的指纹是"当时的模式"的,而磁盘上没有记模式 —— 上屏时**不带基线**(refs=null):
        // 宁可让下一拍轮询重载一次,也不能拿错格式的指纹把历史缓存判成"仍然有效"。
        // (整仓刷新的窗口里轮询本来就压着,phase1 一到基线就是新的)
        if (st != null) {
            snapshots.put(repo.toString(), new Snapshot(
                    new StatusResult(st.oid(), st.branch(), st.upstream(), st.detached(),
                            st.ahead(), st.behind(), st.changes()),
                    st.branches() == null ? List.of() : st.branches(), null,
                    ps.history() != null && ps.history().allBranches(), ps.savedAt()));
        }
        var h = ps.history();
        if (h != null && h.log() != null && !h.log().isEmpty()) {
            historySnapshots.put(repo.toString(), new HistorySnapshot(h.log(), h.unpushed(), h.refs(),
                    h.maxCommits(), h.allBranches(), h.pathFilter(), ps.savedAt()));
        }
    }

    /**
     * 把某个仓库的内存快照落盘(重新打开程序时用它先上屏)。
     * 只在"离开这个仓库"和"关窗"时写:写入是有成本的文件 IO,不需要每次刷新都写。
     *
     * 两件事决定了它不能让界面等:
     * <ul>
     *   <li><b>组装在 FX 线程、写盘在后台</b>:一份 2000 条提交的快照序列化 + 写盘实测 ~63ms,
     *       连续切换时每切一次就掉几帧。这里只组装对象(便宜),写盘交给 {@code SnapshotStore.saveAsync}。</li>
     *   <li><b>没变化就不重写</b>:连点仓库时,中途那些仓库往往还没等来自己的刷新就走了,
     *       内容与上次落盘的一模一样 —— 再写一遍纯属白花磁盘 IO(靠 savedAt 判断)。</li>
     * </ul>
     */
    private void persistSnapshots(Path repo) {
        if (repo == null) return;
        Snapshot st = snapshots.get(repo.toString());
        HistorySnapshot h = historySnapshots.get(repo.toString());
        if (st == null && h == null) return;
        String stamp = (st == null ? "-" : st.savedAt()) + "|" + (h == null ? "-" : h.savedAt());
        if (stamp.equals(persistedStamps.get(repo.toString()))) return;   // 磁盘上已经是这一份,别重写
        persistedStamps.put(repo.toString(), stamp);
        try {
            RepoSnapshot.StatusSnap ss = st == null ? null : new RepoSnapshot.StatusSnap(
                    st.status().oid(), st.status().branch(), st.status().upstream(), st.status().detached(),
                    st.status().ahead(), st.status().behind(), st.status().changes(), st.branches());
            RepoSnapshot.HistorySnap hs = h == null ? null : new RepoSnapshot.HistorySnap(
                    h.refs(), h.maxCommits(), h.allBranches(), h.pathFilter(), h.log(), h.unpushed());
            org.easygit.core.SnapshotStore.saveAsync(repo, new RepoSnapshot(
                    RepoSnapshot.CURRENT_VERSION, System.currentTimeMillis(), repo.toString(),
                    st != null ? st.refs() : (h != null ? h.refs() : null), ss, hs));
        } catch (Exception ignored) {
            // 缓存写失败不该影响任何功能
        }
    }

    /** 已经落盘的那份快照的标记(仓库路径 → 状态/历史各自的 savedAt),用来跳过重复写盘。 */
    private final java.util.Map<String, String> persistedStamps = new java.util.HashMap<>();

    /**
     * 历史缓存是否仍然可用:引用指纹 + 条数上限 + 「所有分支」勾选 + 文件筛选全都一致。
     *
     * 历史只由引用决定(工作区改动不影响提交历史),所以指纹一致 ⇒ 这份列表就是最新的,
     * **一次 git 都不用起**。这就是"有缓存就少取数据"的正确形态:不是增量取,而是不取 ——
     * 增量取仍然要一个 git 进程(~100ms 起步,这台机器上进程启动就是主要成本),省不出来。
     */
    private boolean historyCacheMatches(Path repo, String refs, boolean allBranches, int maxCommits) {
        HistorySnapshot s = historySnapshots.get(repo.toString());
        return s != null && refs != null && refs.equals(s.refs())
                && s.maxCommits() == maxCommits
                && s.allBranches() == allBranches
                && java.util.Objects.equals(s.pathFilter(), historyPanel.pathFilter());
    }

    /**
     * 切回看过的仓库:先把上次的历史列表画上(与阶段一快照同一套 SWR 思路)。
     *
     * 「历史」页是懒加载的,切仓后第一次点进去要等 log + 未推送集合(约 250~500ms)才看到行;
     * 有快照就先画上,后台刷新一到再替换。
     *
     * 勾选状态或文件筛选不同的快照不能画 —— 否则会把"筛选后的历史"当成完整历史显示。
     */
    private void paintHistorySnapshot(Path repo) {
        HistorySnapshot snap = historySnapshots.get(repo.toString());
        if (snap == null) return;
        if (snap.allBranches() != historyPanel.allBranchesSelected()) return;
        if (!java.util.Objects.equals(snap.pathFilter(), historyPanel.pathFilter())) return;
        historyPanel.setCommits(snap.log(), snap.unpushed());
    }

    private record LightData(StatusResult st, String refs) {}
}
