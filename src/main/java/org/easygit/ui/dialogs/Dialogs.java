package org.easygit.ui.dialogs;

import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.PushPlan;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;

/**
 * 通用对话框:打开/克隆/初始化仓库、新建分支、合并、打标签。
 */
public final class Dialogs {
    private Dialogs() {}

    // ---------- 添加仓库(带命名与分组) ----------

    public static void addRepoDialog(javafx.stage.Window owner) {
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("选择要添加的仓库目录");
        Path cur = RepoManager.get().current();
        if (cur != null) dc.setInitialDirectory(cur.toFile());
        else dc.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        java.io.File dir = dc.showDialog(owner);
        if (dir == null) return;
        Path root = RepoManager.findRepoRoot(dir.toPath());
        if (root == null) {
            Fx.error("添加仓库失败", "所选目录(及其父目录)不是 git 仓库", dir.getAbsolutePath());
            return;
        }
        String defaultName = root.getFileName() == null ? root.toString() : root.getFileName().toString();

        TextField name = new TextField(defaultName);
        name.setPrefColumnCount(30);
        ComboBox<String> group = new ComboBox<>();
        group.setEditable(true);
        group.getItems().add("(未分组)");
        group.getItems().addAll(RepoManager.get().groups());
        group.getSelectionModel().selectFirst();
        group.setPrefWidth(220);

        Dialog<String[]> d = new Dialog<>();

        Fx.icon(d);
        d.setTitle("添加仓库");
        d.setHeaderText(root.toString());
        VBox rootBox = new VBox(8, new Label("显示名称:"), name, new Label("分组(可选已有或输入新分组):"), group);
        rootBox.setPadding(new Insets(8));
        d.getDialogPane().setContent(rootBox);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        d.setResultConverter(bt -> bt == ButtonType.OK
                ? new String[]{name.getText().strip(), group.getValue() == null ? "" : group.getValue().strip()}
                : null);
        d.showAndWait().ifPresent(r -> {
            String g = (r[1].isEmpty() || r[1].equals("(未分组)")) ? "" : r[1];
            RepoManager.get().addRepo(root, r[0].isEmpty() ? defaultName : r[0], g);
            Fx.status("已添加仓库 " + root);
        });
    }

    // ---------- 打开仓库 ----------

    public static void openRepo(javafx.stage.Window owner) {
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("选择仓库目录(自动向上查找 .git)");
        Path cur = RepoManager.get().current();
        if (cur != null) dc.setInitialDirectory(cur.toFile());
        else dc.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        java.io.File dir = dc.showDialog(owner);
        if (dir == null) return;
        if (RepoManager.get().open(dir.toPath())) {
            Fx.status("已打开仓库 " + dir);
        } else {
            Fx.error("打开失败", "所选目录(及其父目录)不是 git 仓库", dir.getAbsolutePath());
        }
    }

    // ---------- 克隆 ----------

    public static void cloneRepo(javafx.stage.Window owner) {
        Dialog<Path> dialog = new Dialog<>();
        Fx.icon(dialog);
        dialog.setTitle("克隆仓库");
        dialog.setHeaderText("从 URL 克隆仓库");
        TextField url = new TextField();
        url.setPromptText("https://github.com/user/repo.git");
        url.setPrefColumnCount(40);
        Label dirLabel = new Label("(未选择)");
        Button choose = new Button("选择目录…");
        choose.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("选择克隆目标父目录");
            dc.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
            java.io.File d = dc.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (d != null) dirLabel.setText(d.getAbsolutePath());
        });
        HBox dirBox = new HBox(8, choose, dirLabel);
        CheckBox pullLfs = new CheckBox("克隆后拉取 LFS 文件(git lfs pull)");
        pullLfs.setSelected(true);
        VBox root = new VBox(8, new Label("仓库 URL:"), url, new Label("目标位置:"), dirBox, pullLfs);
        root.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String u = url.getText().strip();
            String parent = dirLabel.getText();
            if (u.isEmpty() || parent.equals("(未选择)")) {
                Fx.error("克隆", "请填写 URL 并选择目标目录", null);
                return null;
            }
            String name = u.endsWith(".git") ? u.substring(u.lastIndexOf('/') + 1, u.length() - 4)
                    : u.substring(u.lastIndexOf('/') + 1);
            if (name.isBlank()) name = "cloned-repo";
            return Paths.get(parent, name);
        });

        dialog.showAndWait().ifPresent(target -> {
            if (Files.exists(target)) {
                Fx.error("克隆失败", "目标目录已存在: " + target, null);
                return;
            }
            Fx.bg("克隆仓库中…", () -> {
                org.easygit.core.GitProcess.GitResult r = NativeGit.clone(url.getText().strip(), target);
                UiLog.op("git clone " + url.getText().strip(), r.out(), r.err());
                if (!r.ok()) throw new RuntimeException("克隆失败:\n" + r.message());
                // 按需拉取 LFS 对象(普通 clone 只拿到指针)
                if (pullLfs.isSelected() && org.easygit.core.LfsService.installed()
                        && org.easygit.core.LfsService.repoUsesLfs(target)) {
                    org.easygit.core.GitProcess.GitResult lfs = org.easygit.core.LfsService.pull(target);
                    if (!lfs.ok()) throw new RuntimeException("LFS 对象拉取失败:\n" + lfs.message()
                            + "\n仓库已克隆完成,可稍后通过 LFS 菜单重试。");
                }
                return target;
            }, t -> {
                Fx.status("克隆完成: " + t);
                RepoManager.get().open(t);
            });        });
    }

    // ---------- 初始化 ----------

    public static void initRepo(javafx.stage.Window owner) {
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("选择要初始化为 git 仓库的目录");
        dc.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        java.io.File dir = dc.showDialog(owner);
        if (dir == null) return;
        Fx.bg("初始化仓库…", () -> {
            org.easygit.core.GitProcess.GitResult r = NativeGit.init(dir.toPath());
            if (!r.ok()) throw new RuntimeException("初始化失败:\n" + r.message());
            return RepoManager.findRepoRoot(dir.toPath());
        }, root -> {
            Fx.status("已初始化仓库 " + root);
            RepoManager.get().open(root);
        });
    }

    // ---------- 新建分支 ----------

    /** 返回分支名;取消返回 null。 */
    public static String newBranch(String defaultName) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog(defaultName);
        Fx.icon(d);
        d.setTitle("新建分支");
        d.setHeaderText("输入新分支名称(将创建并检出)");
        d.setContentText("分支名:");
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
    }

    public static String askText(String title, String header, String defaultText) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog(defaultText);
        Fx.icon(d);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
    }

    // ---------- 合并 ----------

    public static void mergeDialog(java.util.List<org.easygit.core.model.BranchInfo> branches, Runnable refreshAll) {
        if (branches.isEmpty()) {
            Fx.info("合并", "没有可合并的分支");
            return;
        }
        Dialog<String> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("合并分支");
        d.setHeaderText("选择要合并到当前分支的分支");
        ComboBox<org.easygit.core.model.BranchInfo> combo = new ComboBox<>();
        branches.stream().filter(b -> b.kind == org.easygit.core.model.BranchInfo.Kind.LOCAL && !b.current)
                .forEach(combo.getItems()::add);
        branches.stream().filter(b -> b.kind == org.easygit.core.model.BranchInfo.Kind.REMOTE)
                .forEach(combo.getItems()::add);
        combo.setPrefWidth(320);
        ComboBox<String> ffMode = new ComboBox<>();
        ffMode.getItems().addAll("自动(ff 或产生合并提交)", "总是产生合并提交(--no-ff)", "仅快进(--ff-only)");
        ffMode.getSelectionModel().selectFirst();
        VBox root = new VBox(8, new Label("分支:"), combo, new Label("快进模式(自动模式生效):"), ffMode);
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        d.setResultConverter(bt -> bt == ButtonType.OK && combo.getValue() != null
                ? combo.getValue().fullName : null);
        d.showAndWait().ifPresent(ref -> {
            String name = combo.getValue().name;
            Fx.bg("合并…", () -> new org.easygit.core.JGitService(RepoManager.get().current()).merge(ref),
                    outcome -> {
                        UiLog.line("合并 " + name + ": " + outcome.message());
                        Fx.status(outcome.message());
                        refreshAll.run();
                    });
        });
    }

    // ---------- 拉取:分叉时选合并还是变基 ----------

    /**
     * 本地与远端各有新提交时,问用户用哪种方式拉取。
     *
     * @return "merge"(合并,产生合并提交) / "rebase"(变基,历史保持线性) / null(取消)
     */
    public static String pullStrategy(int ahead, int behind, boolean dirty) {
        Dialog<String> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("拉取方式");
        d.setHeaderText("本地有 " + ahead + " 个提交未推送,远端有 " + behind + " 个新提交");
        Label body = new Label((dirty ? "注意:工作区还有未提交的改动,可能会挡住这次拉取。\n\n" : "")
                + "合并拉取:两端历史都保留,产生一个合并提交(适合已经在共享的分支上工作)。\n"
                + "变基拉取:把这 " + ahead + " 个本地提交重放到远端之后,历史保持一条直线"
                + "(适合还没推送过的本地提交)。");
        body.setWrapText(true);
        body.setMaxWidth(420);
        VBox root = new VBox(8, body);
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        ButtonType merge = new ButtonType("合并拉取", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType rebase = new ButtonType("变基拉取", javafx.scene.control.ButtonBar.ButtonData.APPLY);
        ButtonType cancel = new ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().addAll(merge, rebase, cancel);
        d.setResultConverter(bt -> bt == merge ? "merge" : bt == rebase ? "rebase" : null);
        return d.showAndWait().orElse(null);
    }

    /** 推送分叉预警:远端有别人推的新提交。返回 "pull"(先拉取) / "force"(强制覆盖) / null(取消)。 */
    public static String pushDiverged(int ahead, int behind) {
        Dialog<String> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("推送方式");
        d.setHeaderText("远端已有 " + behind + " 个新提交,本地有 " + ahead + " 个提交待推送");
        Label body = new Label("直接推送会被 git 拒绝(会覆盖别人的提交)。\n\n"
                + "拉取后再推送:把远端的新提交合并/变基进来,再推你的提交(推荐)。\n"
                + "强制推送:用本地历史覆盖远端,远端上那 " + behind
                + " 个提交会被丢弃(仅确认没人用时使用)。");
        body.setWrapText(true);
        body.setMaxWidth(420);
        VBox root = new VBox(8, body);
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        ButtonType pull = new ButtonType("拉取后再推送", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType force = new ButtonType("强制推送", javafx.scene.control.ButtonBar.ButtonData.APPLY);
        ButtonType cancel = new ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().addAll(pull, force, cancel);
        d.setResultConverter(bt -> bt == pull ? "pull" : bt == force ? "force" : null);
        return d.showAndWait().orElse(null);
    }

    // ---------- 推送到指定远程 ----------

    /**
     * 推送对话框:任选本地分支 + 任选远程,可选设置上游/强制。defaultBranch 为 null 时自动选中当前分支。
     *
     * pre/pullFlow 由「推送」按钮的一键流程传入:pre 携带预探测结果(状态标签直接显示),
     * pullFlow 用于分叉时的「拉取后再推送」链接。其它入口(分支右键)传 null,对话框自己算状态。
     */
    public static void pushDialog(String defaultBranch, Runnable refreshAll,
                                  org.easygit.ui.panels.PushFlow.Preflight pre,
                                  org.easygit.ui.panels.PullFlow pullFlow) {
        Path repo = RepoManager.get().current();
        if (repo == null) {
            Fx.info("推送", "请先打开仓库");
            return;
        }
        java.util.List<BranchInfo> locals;
        java.util.Map<String, String> remotes;
        if (pre != null) {
            locals = pre.locals();
            remotes = pre.remotes();
            if (pre.detached()) {
                Fx.info("推送", "当前处于游离 HEAD(detached)状态。\n请先检出要推送的分支,再推送。");
                return;
            }
        } else {
            try {
            locals = NativeGit.branches(repo).stream()
                    .filter(b -> b.kind == BranchInfo.Kind.LOCAL).toList();
            remotes = NativeGit.remotes(repo);
        } catch (Exception ex) {
            Fx.error("推送", "读取分支/远程失败: " + ex.getMessage(), null);
            return;
            }
        }
        if (locals.isEmpty()) {
            Fx.info("推送", "仓库还没有本地分支");
            return;
        }
        if (remotes.isEmpty()) {
            // 没有远程:引导现场添加,而不是报错
            if (!Fx.confirm("没有远程仓库", "该仓库还没有配置远程。\n\n是否现在添加一个?(添加后继续推送)")) return;
            remoteManageDialog(null, repo, refreshAll);
            remotes = NativeGit.remotes(repo);
            if (remotes.isEmpty()) return; // 用户没有添加
        }

        Dialog<ButtonType> d = new Dialog<>();

        Fx.icon(d);
        d.setTitle("推送分支到远程");
        d.setHeaderText("选择要推送的分支和目标远程");

        ComboBox<BranchInfo> branchCombo = new ComboBox<>(FXCollections.observableArrayList(locals));
        branchCombo.setPrefWidth(340);
        branchCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BranchInfo b) {
                if (b == null) return "";
                return b.name + (b.upstream == null ? "" : "   (上游 " + b.upstream + ")")
                        + (b.current ? "   ● 当前" : "");
            }
            @Override public BranchInfo fromString(String s) { return null; }
        });
        BranchInfo preBranch = defaultBranch != null
                ? locals.stream().filter(b -> b.name.equals(defaultBranch)).findFirst().orElse(null) : null;
        BranchInfo cur = locals.stream().filter(b -> b.current).findFirst().orElse(null);
        branchCombo.getSelectionModel().select(preBranch != null ? preBranch : cur);

        ComboBox<String> remoteCombo = new ComboBox<>(FXCollections.observableArrayList(remotes.keySet()));
        remoteCombo.setPrefWidth(300);
        String upRemote = null;
        BranchInfo sel0 = branchCombo.getValue();
        if (sel0 != null && sel0.upstream != null && sel0.upstream.contains("/")) {
            upRemote = sel0.upstream.substring(0, sel0.upstream.indexOf('/'));
        }
        remoteCombo.getSelectionModel().select(
                upRemote != null && remotes.containsKey(upRemote)
                        ? upRemote
                        : remotes.keySet().iterator().next());
        Button manageRemote = new Button("管理…");
        manageRemote.setTooltip(new Tooltip("添加 / 编辑 / 重命名 / 删除远程"));
        manageRemote.setOnAction(e -> {
            remoteManageDialog(d.getDialogPane().getScene().getWindow(), repo, refreshAll);
            // 重新加载远程列表并尽量保持选择
            String prev = remoteCombo.getValue();
            var again = NativeGit.remotes(repo);
            remoteCombo.getItems().setAll(again.keySet());
            remoteCombo.getSelectionModel().select(
                    again.containsKey(prev) ? prev
                            : (again.isEmpty() ? null : again.keySet().iterator().next()));
        });
        HBox remoteRow = new HBox(8, remoteCombo, manageRemote);
        HBox.setHgrow(remoteCombo, Priority.ALWAYS);

        CheckBox setUpstream = new CheckBox("设置上游(推送后跟踪该远程分支)");
        CheckBox force = new CheckBox("强制推送(--force-with-lease,覆盖远端)");
        setUpstream.setSelected(branchCombo.getValue() != null && branchCombo.getValue().upstream == null);

        // 状态标签:随分支选择显示 领先/落后/分叉,让"强推覆盖"的决策有依据
        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("dim");
        statusLabel.setMaxWidth(430);
        final PushPlan[] latestPlan = {null};
        Runnable updateStatus = () -> {
            BranchInfo b = branchCombo.getValue();
            if (b == null) { statusLabel.setText(""); latestPlan[0] = null; return; }
            statusLabel.setText("读取分支状态…");
            Fx.bg("读取分支状态…", () -> NativeGit.pushPlan(repo, b.name), plan -> {
                if (branchCombo.getValue() == null
                        || !branchCombo.getValue().name.equals(plan.branch())) return; // 选择已变,丢弃
                latestPlan[0] = plan;
                if (plan.noUpstream()) {
                    setUpstream.setSelected(true);
                    statusLabel.setText("该分支还没有上游,推送时将自动建立(origin/" + plan.branch() + ")");
                } else if (plan.synced()) {
                    statusLabel.setText("已与远端同步,没有需要推送的提交");
                } else if (plan.diverged()) {
                    statusLabel.setText("⚠ 远端有 " + plan.behind() + " 个新提交,本地有 " + plan.ahead()
                            + " 个待推送:直接推送会被拒绝。\n可勾选「强制推送」覆盖远端,或取消后先「拉取」。");
                } else {
                    statusLabel.setText("本地领先远端 " + plan.ahead() + " 个提交,可直接推送");
                }
            });
        };
        branchCombo.valueProperty().addListener((o, ov, nv) -> updateStatus.run());

        VBox root = new VBox(8,
                new Label("要推送的分支:"), branchCombo,
                statusLabel,
                new Label("目标远程:"), remoteRow,
                setUpstream, force);
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            BranchInfo b = branchCombo.getValue();
            String remote = remoteCombo.getValue();
            if (b == null || remote == null) return;
            boolean su = setUpstream.isSelected(), fc = force.isSelected();

            org.easygit.ui.panels.PushFlow flow = new org.easygit.ui.panels.PushFlow(
                    new org.easygit.ui.panels.PushFlow.Host() {
                        @Override public void refreshAll() { refreshAll.run(); }
                        @Override public void openPushDialog(org.easygit.ui.panels.PushFlow.Preflight p) { }
                    }, pullFlow);
            // 分叉且未勾强制:先弹预警分流(拉取后再推 / 强制覆盖 / 取消)。
            // 优先用状态标签算好的 plan(打开对话框时已算);选择没变就直接用。
            PushPlan plan = latestPlan[0] != null && latestPlan[0].branch().equals(b.name)
                    ? latestPlan[0] : NativeGit.pushPlan(repo, b.name);
            if (plan.diverged() && !fc) {
                String choice = pushDiverged(plan.ahead(), plan.behind());
                if (choice == null) return;
                if ("force".equals(choice)) { flow.execute(repo, b.name, remote, su, true); return; }
                if (pullFlow != null) {
                    pullFlow.pull(() -> flow.execute(repo, b.name, remote, su, false));
                    return;
                }
                Fx.info("建议先拉取", "远端有新提交,先「拉取」合并后再推送。");
                return;
            }
            flow.execute(repo, b.name, remote, su, fc);
        });
    }

    // ---------- 选择远程 ----------

    /** 选择一个远程;无远程时引导添加。取消返回 null。 */
    public static String chooseRemote(Path repo) {
        var remotes = NativeGit.remotes(repo);
        if (remotes.isEmpty()) {
            if (!Fx.confirm("没有远程", "该仓库还没有配置远程。是否现在添加?")) return null;
            remoteManageDialog(null, repo, () -> {});
            remotes = NativeGit.remotes(repo);
            if (remotes.isEmpty()) return null;
        }
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(remotes.keySet()));
        combo.getSelectionModel().selectFirst();
        combo.setPrefWidth(260);
        Dialog<String> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("选择远程");
        d.setHeaderText("选择要使用的远程");
        VBox root = new VBox(8, new Label("远程:"), combo);
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        d.setResultConverter(bt -> bt == ButtonType.OK ? combo.getValue() : null);
        return d.showAndWait().orElse(null);
    }

    // ---------- 撤回到某个提交 ----------

    /** 把当前分支重置到指定提交(soft/mixed/hard)。 */
    public static void resetToDialog(org.easygit.core.model.CommitEntry c, Runnable refreshAll) {
        Path repo = RepoManager.get().current();
        if (repo == null) return;

        javafx.scene.control.ComboBox<String> mode = new javafx.scene.control.ComboBox<>();
        mode.getItems().addAll("mixed", "soft", "hard");
        mode.getSelectionModel().selectFirst();
        mode.setPrefWidth(240);

        javafx.scene.control.Label hint = new javafx.scene.control.Label("""
                mixed:该提交之后的改动保留在工作区(未暂存) —— 最常用
                soft:该提交之后的改动保留在暂存区
                hard:彻底丢弃该提交之后的所有提交与未提交改动 —— 不可恢复!""");
        hint.getStyleClass().add("dim");
        hint.setWrapText(true);

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(8,
                new Label("重置方式:"), mode, hint);
        root.setPadding(new Insets(8));

        javafx.scene.control.Dialog<ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle("撤回到此提交");
        d.setHeaderText("把当前分支重置到 " + c.abbr + "  " + c.subject);
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            String m = mode.getValue();
            if (m == null) return;
            if (m.equals("hard") && !Fx.confirm("危险操作",
                    "hard 重置将永久丢弃 " + c.abbr + " 之后的所有提交,\n以及所有未提交的改动(包括暂存区)!\n\n确定继续?")) {
                return;
            }
            Fx.bg("撤回中…", () -> {
                var r = NativeGit.reset(repo, c.id, m);
                UiLog.op("git reset --" + m + " " + c.abbr, r.out(), r.err());
                if (!r.ok()) throw new RuntimeException(r.message());
                return "已撤回到 " + c.abbr + "(" + m + ")";
            }, msg -> {
                Fx.status(msg);
                refreshAll.run();
            });
        });
    }

    // ---------- 远程管理 ----------

    /** 远程管理对话框:添加 / 编辑 URL / 重命名 / 删除。 */
    public static void remoteManageDialog(javafx.stage.Window owner, Path repo, Runnable refreshAll) {
        Dialog<Void> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("管理远程");
        d.setHeaderText("当前仓库的远程配置");

        ListView<String> list = new ListView<>();
        list.setPrefSize(480, 200);
        list.setPlaceholder(new Label("还没有配置远程"));
        Runnable reload = () -> {
            var remotes = NativeGit.remotes(repo);
            var items = javafx.collections.FXCollections.<String>observableArrayList();
            remotes.forEach((n, u) -> items.add(n + "  →  " + u));
            list.getItems().setAll(items);
        };

        Button add = new Button("添加…");
        add.setOnAction(e -> {
            javafx.scene.control.TextField name = new javafx.scene.control.TextField();
            name.setPromptText("如 origin");
            javafx.scene.control.TextField url = new javafx.scene.control.TextField();
            url.setPromptText("https://gitee.com/user/repo.git");
            url.setPrefColumnCount(40);
            javafx.scene.layout.VBox root = new VBox(8, new Label("名称:"), name, new Label("URL:"), url);
            root.setPadding(new Insets(8));
            Dialog<ButtonType> ad = new Dialog<>();
            Fx.icon(ad);
            ad.setTitle("添加远程");
            ad.getDialogPane().setContent(root);
            ad.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            ad.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                String n = name.getText().strip();
                String u = url.getText().strip();
                if (n.isEmpty() || u.isEmpty() || n.contains(" ")) {
                    Fx.error("添加失败", "名称和 URL 不能为空,名称不能含空格", null);
                    return;
                }
                var r = NativeGit.remoteAdd(repo, n, u);
                UiLog.op("git remote add " + n + " " + u, r.out(), r.err());
                if (!r.ok()) { Fx.error("添加失败", r.message(), null); return; }
                Fx.status("已添加远程 " + n + ",正在抓取…");
                refreshAll.run();
                reload.run();
                // 添加后自动抓取该远程,远程分支立即可见
                Fx.bg("抓取远程 " + n + "…", () -> org.easygit.core.NativeGit.fetchRemote(repo, n), fr -> {
                    UiLog.op("git fetch " + n, fr.out(), fr.err());
                    Fx.status(fr.ok() ? "远程 " + n + " 已抓取" : "抓取失败: " + fr.message());
                    refreshAll.run();
                });
            });
        });

        Button edit = new Button("编辑 URL…");
        edit.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String n = sel.substring(0, sel.indexOf("  ")).strip();
            String oldUrl = NativeGit.remotes(repo).getOrDefault(n, "");
            String u = askText("编辑远程 URL", "远程 " + n + " 的新 URL:", oldUrl);
            if (u == null || u.isBlank() || u.equals(oldUrl)) return;
            var r = NativeGit.remoteSetUrl(repo, n, u.strip());
            UiLog.op("git remote set-url " + n, r.out(), r.err());
            if (!r.ok()) { Fx.error("修改失败", r.message(), null); return; }
            Fx.status("已更新远程 " + n);
            refreshAll.run();
            reload.run();
        });

        Button rename = new Button("重命名…");
        rename.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String n = sel.substring(0, sel.indexOf("  ")).strip();
            String nn = askText("重命名远程", "远程 " + n + " 的新名称:", n);
            if (nn == null || nn.isBlank() || nn.contains(" ") || nn.equals(n)) return;
            var r = NativeGit.remoteRename(repo, n, nn.strip());
            UiLog.op("git remote rename " + n + " → " + nn.strip(), r.out(), r.err());
            if (!r.ok()) { Fx.error("重命名失败", r.message(), null); return; }
            Fx.status("已重命名远程 " + n + " → " + nn.strip());
            refreshAll.run();
            reload.run();
        });

        Button del = new Button("删除");
        del.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String n = sel.substring(0, sel.indexOf("  ")).strip();
            if (!Fx.confirm("删除远程", "确定删除远程 " + n + "?\n(仅移除配置,不影响已拉取的数据)")) return;
            var r = NativeGit.remoteRemove(repo, n);
            UiLog.op("git remote remove " + n, r.out(), r.err());
            if (!r.ok()) { Fx.error("删除失败", r.message(), null); return; }
            Fx.status("已删除远程 " + n);
            refreshAll.run();
            reload.run();
        });

        HBox buttons = new HBox(8, add, edit, rename, del);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(8, list, buttons);
        root.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().setPrefSize(560, 340);
        reload.run();
        d.showAndWait();
    }

    // ---------- 修改提交消息 ----------

    /** 修改一条提交的提交消息(历史提交会自动改写其后代提交链,先确认)。 */
    public static void editCommitMessage(org.easygit.core.model.CommitEntry c, Runnable refreshAll) {
        Path repo = RepoManager.get().current();
        if (repo == null) return;

        javafx.scene.control.TextArea ta = new javafx.scene.control.TextArea(
                c.subject + (c.body.isBlank() ? "" : "\n\n" + c.body.strip()));
        ta.setPrefRowCount(14);
        ta.setPrefColumnCount(66);
        ta.setFont(javafx.scene.text.Font.font("Consolas", 13));

        VBox root = new VBox(8, new Label("新的提交消息(第一行是标题,空行后是正文):"), ta);
        root.setPadding(new Insets(8));
        Dialog<ButtonType> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("修改提交消息");
        d.setHeaderText(c.abbr + "  " + c.subject);
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        ((Button) d.getDialogPane().lookupButton(ButtonType.OK)).setText("修改");

        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            String msg = ta.getText();
            if (msg == null || msg.isBlank()) {
                Fx.error("无法修改", "提交消息不能为空", null);
                return;
            }
            // 统计受影响提交数,做明确警示
            Fx.bg("统计受影响提交…", () -> NativeGit.countRange(repo, c.id + "..HEAD"), affected -> {
                if (affected < 0) {
                    Fx.error("无法修改", "统计受影响提交失败(该提交可能不在当前分支历史上)", null);
                    return;
                }
                String warn;
                if (affected <= 1) {
                    warn = "该提交是当前分支的最新提交,只有它自己的 SHA 会改变。";
                } else {
                    warn = "⚠ 将改写从该提交到分支头部共 " + affected + " 条提交,它们的 SHA 全部改变。\n\n"
                            + "• 已推送到远程的提交,之后需要用「强制推送」覆盖远程\n"
                            + "• 其他包含这些提交的分支/标签不受影响,仍指向旧提交\n"
                            + "• 本次改写不修改任何文件内容,工作区无需干净";
                }
                if (!Fx.confirm("确认改写提交历史", warn + "\n\n确定继续?")) return;
                Fx.bg("改写提交历史…", () ->
                                new org.easygit.core.JGitService(repo).rewriteCommitMessage(c.id, msg),
                        newSha -> {
                            Fx.status("提交消息已修改 " + c.abbr + " → " + newSha.substring(0, Math.min(8, newSha.length())));
                            refreshAll.run();
                        });
            });
        });
    }

    // ---------- Git LFS ----------

    /** LFS 状态对话框。 */
    public static void lfsStatusDialog(javafx.stage.Window owner, org.easygit.core.LfsService.LfsInfo info) {
        Dialog<Void> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("Git LFS 状态");
        d.setHeaderText("Git LFS");

        VBox root = new VBox(8);
        root.setPadding(new Insets(8));
        root.getChildren().add(new Label("Git LFS: "
                + (info.installed() ? (info.version().isEmpty() ? "已安装" : info.version()) : "未安装(请安装 Git for Windows 或单独安装 git-lfs)")));
        root.getChildren().add(new Label("全局过滤器: " + (info.hooked() ? "已初始化" : "未初始化(菜单里执行「初始化 Git LFS」)")));
        root.getChildren().add(new Label("本仓库: " + (info.repoUses() ? "使用 LFS" : "未使用 LFS(没有 filter=lfs 的跟踪规则)")));

        if (info.repoUses()) {
            long downloaded = info.files().stream().filter(f -> f.downloaded()).count();
            root.getChildren().add(new Label("LFS 文件: " + info.files().size()
                    + " 个(完整对象 " + downloaded + " / 仅指针 " + (info.files().size() - downloaded) + ")"));
            javafx.scene.control.ListView<org.easygit.core.LfsService.LfsFile> files =
                    new javafx.scene.control.ListView<>();
            files.setPrefSize(560, 200);
            files.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(org.easygit.core.LfsService.LfsFile f, boolean empty) {
                    super.updateItem(f, empty);
                    if (empty || f == null) { setGraphic(null); setText(null); return; }
                    String mark = f.downloaded() ? "● 完整" : "○ 指针";
                    String size = f.size() >= 0 ? humanSize(f.size()) : "";
                    setText(mark + "  " + f.path() + (size.isEmpty() ? "" : "  " + size));
                }
            });
            files.getItems().addAll(info.files());
            root.getChildren().addAll(new Label("LFS 文件:"), files);
        }
        if (!info.envText().isBlank()) {
            TextArea env = new TextArea(info.envText());
            env.setEditable(false);
            env.setPrefRowCount(8);
            env.setFont(javafx.scene.text.Font.font("Consolas", 12));
            root.getChildren().addAll(new Label("git lfs env:"), env);
        }
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().setPrefSize(640, 560);
        d.showAndWait();
    }

    /** LFS 跟踪规则管理(读改 .gitattributes)。 */
    public static void lfsTrackRulesDialog(javafx.stage.Window owner, Path repo, Runnable refreshAll) {
        Dialog<Void> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("Git LFS 跟踪规则");
        d.setHeaderText("管理 .gitattributes 中的 LFS 跟踪规则");

        javafx.scene.control.ListView<String> patterns = new javafx.scene.control.ListView<>();
        patterns.setPrefSize(420, 220);
        patterns.setPlaceholder(new Label("暂无规则,例如添加 *.psd"));

        TextField input = new TextField();
        input.setPromptText("例如 *.psd 或 assets/models/*.fbx");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button add = new Button("添加规则");
        add.setOnAction(e -> {
            String p = input.getText();
            if (p == null || p.isBlank()) return;
            // track 很快,同步执行即可
            org.easygit.core.GitProcess.GitResult r =
                    org.easygit.core.LfsService.track(repo, p.strip());
            if (!r.ok()) {
                Fx.error("添加失败", r.message(), null);
                return;
            }
            input.clear();
            org.easygit.core.LfsService.resetCaches();
            Fx.status("已添加跟踪规则 " + p.strip() + ",记得提交 .gitattributes");
            refreshAll.run();
            patterns.getItems().setAll(org.easygit.core.LfsService.trackPatterns(repo));
        });
        Button remove = new Button("移除所选规则");
        remove.setOnAction(e -> {
            String sel = patterns.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (!Fx.confirm("移除规则", "移除跟踪规则 " + sel + "?\n(只修改 .gitattributes,已提交的对象不受影响)")) return;
            org.easygit.core.GitProcess.GitResult r =
                    org.easygit.core.LfsService.untrack(repo, sel);
            if (!r.ok()) {
                Fx.error("移除失败", r.message(), null);
                return;
            }
            org.easygit.core.LfsService.resetCaches();
            Fx.status("已移除规则 " + sel + ",记得提交 .gitattributes");
            refreshAll.run();
            patterns.getItems().setAll(org.easygit.core.LfsService.trackPatterns(repo));
        });

        patterns.getItems().setAll(org.easygit.core.LfsService.trackPatterns(repo));

        VBox root = new VBox(8,
                new Label("当前规则:"), patterns,
                new HBox(8, input, add),
                remove,
                new Label("说明:规则保存在仓库根目录 .gitattributes(filter=lfs),修改后需要提交。"));
        root.setPadding(new Insets(8));
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }

    // ---------- 标签 ----------

    /** 返回 {名称, 说明};取消返回 null。 */
    public static String[] tag(String defaultTarget) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog("");
        Fx.icon(d);
        d.setTitle("创建标签");
        d.setHeaderText("为 " + defaultTarget + " 创建标签");
        d.setContentText("标签名:");
        String name = d.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
        if (name == null) return null;
        return new String[]{name, ""};
    }
}
