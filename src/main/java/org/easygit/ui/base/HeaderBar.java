package org.easygit.ui.base;

import org.easygit.core.AppSettings;
import org.easygit.core.RepoManager;
import org.easygit.ui.dialogs.Dialogs;
import org.easygit.ui.dialogs.SettingsDialog;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 顶部命令栏:左侧仓库切换器,右侧按使用频率分组的动作。
 *
 * 高频(拉取/推送/刷新)直接是文字按钮;低频(抓取/Blame/LFS/输出面板/主题)收进「⋯」。
 * 这里只负责"发出意图",具体动作由 {@link Actions} 回调给宿主窗口执行,
 * 因此本类不持有主窗口引用,也不会参与刷新编排。
 */
public final class HeaderBar extends HBox {

    /** 命令栏需要的宿主能力。 */
    public interface Actions {
        void pull();
        void push();
        void refresh();
        void fetch();
        void blame();
        void toggleOutput();
        void toggleTheme();
    }

    private final MenuButton repoSwitcher = new MenuButton("未打开仓库");
    private final Stage stage;

    public HeaderBar(Stage stage, Actions actions) {
        super(6);
        this.stage = stage;
        getStyleClass().add("header-bar");
        setAlignment(Pos.CENTER_LEFT);
        repoSwitcher.getStyleClass().add("repo-switcher");

        // 高频组:真实文字钮,不用神秘图标
        Button pullBtn = ghost("拉取", actions::pull);
        Button pushBtn = ghost("推送", actions::push);
        Button refreshBtn = ghost("刷新", actions::refresh);

        // 低频收纳:抓取 / Blame / LFS / 输出 / 主题
        MenuButton more = new MenuButton("⋯");
        MenuItem fetch = new MenuItem("抓取(fetch)");
        fetch.setOnAction(e -> actions.fetch());
        MenuItem blame = new MenuItem("Blame 文件(输入路径)…");
        blame.setOnAction(e -> actions.blame());
        MenuItem outputItem = new MenuItem("显示 / 隐藏输出面板");
        outputItem.setOnAction(e -> actions.toggleOutput());
        MenuItem themeItem = new MenuItem("切换亮暗主题");
        themeItem.setOnAction(e -> actions.toggleTheme());
        more.getItems().addAll(fetch, blame, lfsMenu(actions::refresh), new SeparatorMenuItem(),
                outputItem, themeItem);

        Button settingsBtn = ghost("设置", () -> SettingsDialog.show(stage, null));

        Separator groupSep = new Separator();
        groupSep.setOrientation(Orientation.VERTICAL);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(repoSwitcher, spacer,
                pullBtn, pushBtn, refreshBtn, groupSep, more, settingsBtn);
    }

    private static Button ghost(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost");
        b.setOnAction(e -> action.run());
        return b;
    }

    /** 显示"未打开仓库"或"仓库名 · 分组",并把完整路径放进悬停提示。 */
    public void setRepo(String label, String fullPath) {
        repoSwitcher.setText(label);
        repoSwitcher.setTooltip(new javafx.scene.control.Tooltip(fullPath));
    }

    /** 依据受管仓库列表重建切换器条目(打开/克隆/初始化固定收在末尾)。 */
    public void rebuildRepoSwitcher(Stage stage) {
        repoSwitcher.getItems().clear();
        Path cur = RepoManager.get().current();
        String curPath = cur == null ? "" : cur.toString();
        for (AppSettings.RepoEntry e : AppSettings.get().repos()) {
            boolean current = e.path().equals(curPath);
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

    /** LFS 子菜单。每个动作都在后台跑并给出中文结果。 */
    private Menu lfsMenu(Runnable refreshAll) {
        Menu lfsMenu = new Menu("Git LFS");
        MenuItem status = new MenuItem("LFS 状态…");
        MenuItem pull = new MenuItem("拉取 LFS 对象");
        MenuItem fetch = new MenuItem("抓取 LFS 对象");
        MenuItem track = new MenuItem("管理跟踪规则…");
        MenuItem prune = new MenuItem("清理本地对象缓存…");
        MenuItem install = new MenuItem("初始化 Git LFS(git lfs install)");

        status.setOnAction(e -> Fx.bg("读取 LFS 状态…", () -> {
            Path repo = requireRepo();
            return org.easygit.core.LfsService.gather(repo);
        }, info -> Dialogs.lfsStatusDialog(stage, info)));

        pull.setOnAction(e -> {
            if (!Fx.confirm("拉取 LFS 对象", "将当前分支所有 LFS 指针替换为完整文件(git lfs pull)?")) return;
            runLfs("拉取 LFS 对象…", "LFS 对象拉取完成", refreshAll, org.easygit.core.LfsService::pull);
        });
        fetch.setOnAction(e -> runLfs("抓取 LFS 对象…", "LFS 对象抓取完成", refreshAll,
                org.easygit.core.LfsService::fetch));
        track.setOnAction(e -> {
            Path repo = RepoManager.get().current();
            if (repo == null) { Fx.info("LFS", "请先打开仓库"); return; }
            Dialogs.lfsTrackRulesDialog(stage, repo, refreshAll);
        });
        prune.setOnAction(e -> {
            if (!Fx.confirm("清理 LFS 缓存", "清理本地 LFS 对象缓存中可安全移除的对象(git lfs prune)?\n"
                    + "已推送到远程的旧版本对象会被删除,需要时可重新下载。")) return;
            runLfs("清理 LFS 缓存…", "LFS 缓存清理完成", refreshAll, org.easygit.core.LfsService::prune);
        });
        install.setOnAction(e -> Fx.bg("初始化 Git LFS…", () -> {
            var r = org.easygit.core.LfsService.install();
            if (!r.ok()) throw new IllegalStateException(r.message());
            org.easygit.core.LfsService.resetCaches();
            return true;
        }, ok -> Fx.info("Git LFS", "已初始化(git lfs install),之后 add/commit/push 会自动处理 LFS 文件。")));

        lfsMenu.getItems().addAll(status, pull, fetch, track, prune, new SeparatorMenuItem(), install);
        return lfsMenu;
    }

    /** LFS 的通用后台执行:失败抛异常(走统一错误框),成功清缓存 + 提示 + 刷新。 */
    private void runLfs(String busy, String doneText, Runnable refreshAll,
                        java.util.function.Function<Path, org.easygit.core.GitProcess.GitResult> op) {
        Fx.bg(busy, () -> {
            var r = op.apply(requireRepo());
            if (!r.ok()) throw new IllegalStateException(r.message());
            org.easygit.core.LfsService.resetCaches();
            return true;
        }, ok -> {
            Fx.status(doneText);
            refreshAll.run();
        });
    }

    private static Path requireRepo() {
        Path repo = RepoManager.get().current();
        if (repo == null) throw new IllegalStateException("请先打开仓库");
        return repo;
    }
}
