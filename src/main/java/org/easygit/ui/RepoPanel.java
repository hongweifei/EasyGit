package org.easygit.ui;

import org.easygit.core.AppSettings;
import org.easygit.core.RepoManager;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库面板:按分组展示所有受管理的仓库,单击切换,右键管理(改名/分组/移除)。
 */
public class RepoPanel extends VBox {

    /**
     * kind=SECTION 时 group 是原始分组名(空串表示未分组),count 是组内仓库数;
     * kind=REPO 时 group 是该仓库所属分组,repo 为仓库条目。
     */
    private record Node(String kind, String group, int count, AppSettings.RepoEntry repo) {
        static final String ROOT = "root";
        static final String SECTION = "section";
        static final String REPO = "repo";
    }

    private final TreeView<Node> tree = new TreeView<>();
    private final javafx.stage.Window owner;

    public RepoPanel(javafx.stage.Window owner) {
        this.owner = owner;
        setSpacing(4);
        setPadding(new javafx.geometry.Insets(6, 10, 4, 10));

        Label title = new Label("仓库");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button add = new Button("＋");
        add.setTooltip(new Tooltip("添加仓库(选择目录 / 之后可在右键菜单改名和分组)"));
        add.setOnAction(e -> Dialogs.addRepoDialog(owner));
        HBox header = new HBox(6, title, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.setCellFactory(v -> new Cell());
        VBox.setVgrow(tree, Priority.ALWAYS);

        getChildren().addAll(header, tree);

        rebuild();
        RepoManager.get().addListener(p -> Platform.runLater(this::rebuild));
    }

    private void rebuild() {
        TreeItem<Node> root = new TreeItem<>(new Node(Node.ROOT, null, 0, null));

        List<AppSettings.RepoEntry> repos = RepoManager.get().managedRepos();
        Map<String, List<AppSettings.RepoEntry>> byGroup = new LinkedHashMap<>();
        List<String> groupOrder = new ArrayList<>(RepoManager.get().groups());
        if (!groupOrder.isEmpty() || !repos.isEmpty()) groupOrder.add(""); // 未分组排最后
        for (String g : groupOrder) byGroup.put(g, new ArrayList<>());
        for (AppSettings.RepoEntry e : repos) {
            byGroup.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<String, List<AppSettings.RepoEntry>> en : byGroup.entrySet()) {
            List<AppSettings.RepoEntry> list = en.getValue();
            if (list.isEmpty()) continue;
            String g = en.getKey();
            TreeItem<Node> section = new TreeItem<>(new Node(Node.SECTION, g, list.size(), null));
            // 默认展开:否则每次刷新(增删/改名/切仓库)后所有分组都会收起,仓库全被藏起来
            section.setExpanded(true);
            for (AppSettings.RepoEntry e : list) {
                section.getChildren().add(new TreeItem<>(new Node(Node.REPO, g, 0, e)));
            }
            root.getChildren().add(section);
        }
        tree.setRoot(root);
    }

    private class Cell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null || n.kind().equals(Node.ROOT)) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setOnMouseClicked(null);
                return;
            }
            if (n.kind().equals(Node.SECTION)) {
                String title = n.group().isEmpty() ? "未分组" : n.group() + " (" + n.count() + ")";
                Label l = new Label(title);
                l.getStyleClass().add("section-title");
                setGraphic(l);
                setText(null);
                setTooltip(null);
                setOnMouseClicked(null);
                // "未分组"不是可管理的分组,不提供菜单;真正的分组可重命名/解散
                setContextMenu(n.group().isEmpty() ? null : sectionMenu(n.group()));
                return;
            }
            // 仓库节点
            AppSettings.RepoEntry e = n.repo();
            Path path = Path.of(e.path());
            boolean isCurrent = RepoManager.get().current() != null
                    && RepoManager.get().current().toString().equals(e.path());

            Label name = new Label(e.name());
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            HBox box = new HBox(6, name);
            box.setAlignment(Pos.CENTER_LEFT);
            if (isCurrent) {
                Label dot = new Label("●");
                dot.getStyleClass().add("chip-branch");
                box.getChildren().add(dot);
            }
            setGraphic(box);
            setText(null);
            setTooltip(new Tooltip(e.name() + "\n" + e.path()));
            setOnMouseClicked(ev -> {
                // 仅左键单击切换,右键留给上下文菜单
                if (ev.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && ev.getClickCount() == 1 && !isCurrent) {
                    if (!RepoManager.get().open(path)) {
                        Fx.error("切换仓库失败", "该目录不再是 git 仓库: " + e.path(), null);
                    }
                }
            });
            setContextMenu(repoMenu(e));
        }
    }

    private ContextMenu repoMenu(AppSettings.RepoEntry e) {
        boolean isCurrent = RepoManager.get().current() != null
                && RepoManager.get().current().toString().equals(e.path());
        ContextMenu menu = new ContextMenu();
        if (!isCurrent) {
            menu.getItems().add(mi("切换到此仓库", () -> RepoManager.get().open(Path.of(e.path()))));
        }
        menu.getItems().addAll(
                mi("重命名…", () -> {
                    String name = Dialogs.askText("重命名仓库", "显示名称:", e.name());
                    if (name != null && !name.isBlank() && !name.equals(e.name())) {
                        RepoManager.get().renameRepo(e.path(), name.strip());
                    }
                }),
                mi("移动到分组…", () -> {
                    String g = askGroup(e.group());
                    if (g != null) RepoManager.get().setRepoGroup(e.path(), g);
                }),
                mi("在文件管理器中显示", () -> revealInFileManager(e.path())),
                mi("复制路径", () -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(e.path());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                }),
                mi("从列表移除", () -> {
                    if (!Fx.confirm("移除仓库", "从 EasyGit 列表移除 " + e.name() + "?\n(不会删除磁盘上的文件)")) return;
                    RepoManager.get().removeRepo(e.path());
                })
        );
        return menu;
    }

    /** 分组节点的右键菜单:重命名(可并入已有分组)、解散(仓库移到未分组)。 */
    private ContextMenu sectionMenu(String group) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                mi("重命名分组…", () -> {
                    String name = Dialogs.askText("重命名分组", "分组名:", group);
                    if (name == null) return;
                    String t = name.strip();
                    if (t.isEmpty() || t.equals(group)) return;
                    RepoManager.get().renameGroup(group, t);
                }),
                mi("解散分组(仓库移到未分组)", () -> {
                    if (!Fx.confirm("解散分组",
                            "解散分组「" + group + "」?\n组内仓库将移到未分组(不会删除仓库)。")) return;
                    RepoManager.get().dissolveGroup(group);
                })
        );
        return menu;
    }

    /** 分组选择:可选已有分组、新建分组或未分组;预选仓库当前所在分组。返回 null=取消。 */
    private String askGroup(String current) {
        List<String> groups = RepoManager.get().groups();
        javafx.scene.control.ComboBox<String> combo = new javafx.scene.control.ComboBox<>();
        combo.setEditable(true);
        combo.getItems().add("(未分组)");
        combo.getItems().addAll(groups);
        if (current == null || current.isBlank()) combo.getSelectionModel().selectFirst();
        else combo.getSelectionModel().select(current);
        combo.setPrefWidth(260);

        javafx.scene.control.Dialog<String> d = new javafx.scene.control.Dialog<>();
        d.setTitle("移动到分组");
        d.setHeaderText("选择已有分组、输入新分组名,或选(未分组)");
        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(8, new Label("分组:"), combo);
        root.setPadding(new javafx.geometry.Insets(8));
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        d.setResultConverter(bt -> bt == javafx.scene.control.ButtonType.OK ? combo.getValue() : null);
        return d.showAndWait().map(v -> {
            if (v == null || v.isBlank() || v.equals("(未分组)")) return "";
            return v.strip();
        }).orElse(null);
    }

    /** 跨平台定位文件:Windows 资源管理器 / macOS Finder / Linux 文件管理器。 */
    private static void revealInFileManager(String path) {
        java.io.File file = new java.io.File(path);
        // 首选 Java 桌面 API(macOS/Windows/Linux 桌面环境均支持)
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(file);
                    return;
                }
            }
        } catch (Exception ignored) {
            // 继续走命令行兜底
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + path).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", path).start();
            } else {
                java.io.File parent = file.getParentFile();
                new ProcessBuilder("xdg-open", (parent != null ? parent : file).getAbsolutePath()).start();
            }
        } catch (Exception ex) {
            Fx.error("打开失败", ex.getMessage(), null);
        }
    }

    private static MenuItem mi(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }
}
