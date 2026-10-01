package org.easygit.ui.panels;

import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.DiffModels.DiffFile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.RepoGuard;
import org.easygit.ui.views.DiffView;

/**
 * 提交详情(单列布局):
 * 提交信息块(描述/作者/Parent/refs/统计)在上,
 * 下方是通栏文件列表(列表/树形 + 通配符搜索);
 * 选中文件后整块切换为 文件视图/差异视图,可返回列表、追溯、按文件筛选历史。
 */
public class CommitDetailPanel extends VBox {

    private static final int BODY_LINE_HEIGHT = 20;

    private enum ViewMode { FILE, DIFF }

    private record TreeEnt(String name, String fullPath, DiffFile file, boolean folder) {}

    // 信息块
    private final Label body = new Label();
    private final ScrollPane bodyScroll = new ScrollPane(body);
    private int bodyVisualLines;
    private final Label authorLabel = new Label();
    private final Label dateLabel = new Label();
    private final Label parentLabel = new Label();
    private final Label statLabel = new Label();
    private final FlowPane refsPane = new FlowPane(6, 4);

    // 状态条(选中文件时):返回 + 视图切换 + 操作
    private final Button backBtn = new Button("← 返回文件列表");
    private final javafx.scene.control.ToggleButton fileBtn = new javafx.scene.control.ToggleButton("文件视图");
    private final javafx.scene.control.ToggleButton diffBtn = new javafx.scene.control.ToggleButton("差异视图");
    private final Button blameBtn = new Button("追溯此文件");
    private final Button filterBtn = new Button("按此文件筛选历史");
    private final HBox stateBar;

    // 文件列表条(未选中文件时):列表/树形 + 搜索
    private final javafx.scene.control.ToggleButton listBtn = new javafx.scene.control.ToggleButton("列表");
    private final javafx.scene.control.ToggleButton treeBtn = new javafx.scene.control.ToggleButton("树形");
    private final TextField search = new TextField();
    private final HBox fileBar;

    // 内容区:文件列表 或 查看器
    private final ListView<DiffFile> fileList = new ListView<>();
    private final TreeView<TreeEnt> fileTree = new TreeView<>();
    private final DiffView diffView = new DiffView();
    private final StackPane container = new StackPane();

    private CommitEntry current;
    private List<DiffFile> files = List.of();
    private DiffFile selected;
    private ViewMode mode = ViewMode.FILE;
    private boolean updatingUi = false;

    private java.util.function.Consumer<String> blameOpener;
    private java.util.function.Consumer<String> historyFilter;

    public CommitDetailPanel() {
        setSpacing(6);
        setPadding(new Insets(8));

        // ---- 提交信息块 ----
        body.getStyleClass().add("commit-body");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.setPadding(new Insets(0, 4, 0, 0));
        // 正文全量展开,放进可滚动区域:短消息自适应高度,长消息占详情区约 55% 并内部滚动
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        bodyScroll.getStyleClass().add("body-scroll");
        bodyScroll.setPrefHeight(80);
        // 详情区空间不足时 VBox 会把信息块压到最小——ScrollPane 最小≈一行,
        // 提交消息会被压得看不了;钉住"最小=首选"(短消息全文展示,长消息
        // 保持 160~280 的可视高度内部滚动),压缩由下方文件区承担
        bodyScroll.setMinHeight(Region.USE_PREF_SIZE);
        heightProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.doubleValue() > 0 && bodyVisualLines > 5) {
                updateBodyHeight();
            }
        });

        authorLabel.getStyleClass().add("file-name");
        authorLabel.getStyleClass().add("h2");
        dateLabel.getStyleClass().add("dim");
        parentLabel.getStyleClass().add("dim");
        statLabel.getStyleClass().add("file-stat");
        refsPane.setMaxWidth(Double.MAX_VALUE);

        VBox infoBlock = new VBox(4,
                authorLabel, dateLabel, parentLabel, statLabel, refsPane,
                bodyScroll);
        infoBlock.setSpacing(3);

        // ---- 状态条(查看文件时) ----
        javafx.scene.control.ToggleGroup viewGroup = new javafx.scene.control.ToggleGroup();
        fileBtn.setToggleGroup(viewGroup);
        diffBtn.setToggleGroup(viewGroup);
        fileBtn.setSelected(true);
        fileBtn.setOnAction(e -> {
            mode = ViewMode.FILE;
            renderViewer();
        });
        diffBtn.setOnAction(e -> {
            mode = ViewMode.DIFF;
            renderViewer();
        });
        blameBtn.setOnAction(e -> {
            if (selected != null && blameOpener != null) blameOpener.accept(displayPath(selected));
        });
        filterBtn.setOnAction(e -> {
            if (selected != null && historyFilter != null) historyFilter.accept(displayPath(selected));
        });
        // 按钮不被长路径压缩
        backBtn.setMinWidth(Control.USE_PREF_SIZE);
        fileBtn.setMinWidth(Control.USE_PREF_SIZE);
        diffBtn.setMinWidth(Control.USE_PREF_SIZE);
        blameBtn.setMinWidth(Control.USE_PREF_SIZE);
        filterBtn.setMinWidth(Control.USE_PREF_SIZE);
        backBtn.setOnAction(e -> backToList());
        // 差异视图的"统一/并排"切换靠右放置;纯文件模式下 DiffView 内部会自动禁用它
        Region stateSpacer = new Region();
        HBox.setHgrow(stateSpacer, Priority.ALWAYS);
        stateBar = new HBox(6, backBtn, vSeparator(), fileBtn, diffBtn,
                blameBtn, filterBtn, stateSpacer, diffView.makeLayoutSwitch());
        stateBar.setAlignment(Pos.CENTER_LEFT);

        // ---- 文件列表条 ----
        javafx.scene.control.ToggleGroup layoutGroup = new javafx.scene.control.ToggleGroup();
        listBtn.setToggleGroup(layoutGroup);
        treeBtn.setToggleGroup(layoutGroup);
        listBtn.setSelected(true);
        listBtn.setOnAction(e -> updateContainer());
        treeBtn.setOnAction(e -> updateContainer());
        search.setPromptText("支持通配符搜索,如 *.java");
        search.textProperty().addListener((o, ov, nv) -> {
            if (!updatingUi) rebuildFileViews();
        });
        Region searchSpacer = new Region();
        HBox.setHgrow(searchSpacer, Priority.ALWAYS);
        fileBar = new HBox(6, listBtn, treeBtn, searchSpacer, search);
        fileBar.setAlignment(Pos.CENTER_LEFT);

        // ---- 内容区 ----
        fileList.setFixedCellSize(26);
        fileList.setPlaceholder(new Label("该提交没有文件变更"));
        fileList.setCellFactory(v -> new FileCell());
        fileList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (updatingUi || nv == null) return;
            selectFile(nv);
        });

        fileTree.setShowRoot(false);
        fileTree.setCellFactory(v -> new TreeCellOf());
        fileTree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (updatingUi || nv == null || nv.getValue() == null) return;
            TreeEnt ent = nv.getValue();
            if (!ent.folder() && ent.file() != null) selectFile(ent.file());
        });

        container.getChildren().addAll(fileList, fileTree, diffView);
        fileTree.setVisible(false);
        fileTree.setManaged(false);
        diffView.setVisible(false);
        diffView.setManaged(false);
        VBox.setVgrow(container, Priority.ALWAYS);

        stateBar.setVisible(false);
        stateBar.setManaged(false);

        getChildren().addAll(infoBlock, vSeparator(), stateBar, fileBar, container);
    }

    private static javafx.scene.control.Separator vSeparator() {
        javafx.scene.control.Separator s = new javafx.scene.control.Separator();
        s.setOrientation(javafx.geometry.Orientation.VERTICAL);
        return s;
    }

    public void setBlameOpener(java.util.function.Consumer<String> c) { this.blameOpener = c; }

    public void setHistoryFilter(java.util.function.Consumer<String> c) { this.historyFilter = c; }

    public void clear() {
        current = null;
        selected = null;
        files = List.of();
        body.setText("");
        bodyScroll.setVisible(false);
        bodyScroll.setManaged(false);
        authorLabel.setText("");
        dateLabel.setText("");
        parentLabel.setText("");
        statLabel.setText("");
        refsPane.getChildren().clear();
        fileList.getItems().clear();
        fileTree.setRoot(null);
        diffView.clear();
        setState(false);
    }

    /** 异步加载提交的 diff 并渲染。 */
    public void show(CommitEntry c) {
        if (c == null) {
            clear();
            return;
        }
        current = c;
        selected = null;
        files = List.of();
        updatingUi = false;

        renderMessage();
        authorLabel.setText("👤 " + c.author + " <" + c.email + ">");
        dateLabel.setText("时间: " + c.timeText());
        String parent = c.parents.isEmpty() ? "(根提交)" : abbreviateMiddle(c.parents.get(0), 20);
        parentLabel.setText("Parent: " + parent);
        if (!c.parents.isEmpty()) parentLabel.setTooltip(new Tooltip(c.parents.get(0)));
        statLabel.setText("统计中…");
        refsPane.getChildren().clear();
        for (String r : c.refs) {
            Label chip = new Label(r.startsWith("tag:") ? r : r.replace("HEAD -> ", "★ "));
            chip.getStyleClass().add("chip");
            if (r.startsWith("tag:")) chip.getStyleClass().add("chip-tag");
            else if (r.startsWith("HEAD")) chip.getStyleClass().add("chip-head");
            else if (r.contains("/")) chip.getStyleClass().add("chip-remote");
            else chip.getStyleClass().add("chip-branch");
            refsPane.getChildren().add(chip);
        }

        search.clear();
        fileList.getItems().clear();
        fileTree.setRoot(null);
        diffView.clear();
        setState(false);

        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) return;
        Fx.bg("读取提交差异…", guard, () -> org.easygit.core.NativeGit.diffCommit(repo, c), list -> {
            if (current != c) return; // 选择已切换,丢弃过期结果
            files = list;
            long add = list.stream().mapToLong(f -> f.added).sum();
            long del = list.stream().mapToLong(f -> f.deleted).sum();
            long addFiles = list.stream().filter(f -> f.newFile).count();
            long delFiles = list.stream().filter(f -> f.deletedFile).count();
            long modFiles = list.size() - addFiles - delFiles;
            statLabel.setText(list.size() + " 文件 · " + addFiles + " 新增 · "
                    + modFiles + " 修改 · " + delFiles + " 删除 · +" + add + " -" + del);
            rebuildFileViews();
        });
    }

    // ---------- 状态切换 ----------

    private void setState(boolean viewingFile) {
        stateBar.setVisible(viewingFile);
        stateBar.setManaged(viewingFile);
        fileBar.setVisible(!viewingFile);
        fileBar.setManaged(!viewingFile);
        // 只切换可见性,不移除节点,避免在布局周期中拆掉 VirtualFlow
        updateContainer();
    }

    private void updateContainer() {
        boolean viewingFile = selected != null;
        boolean treeMode = treeBtn.isSelected();
        diffView.setVisible(viewingFile);
        diffView.setManaged(viewingFile);
        fileList.setVisible(!viewingFile && !treeMode);
        fileList.setManaged(!viewingFile && !treeMode);
        fileTree.setVisible(!viewingFile && treeMode);
        fileTree.setManaged(!viewingFile && treeMode);
    }

    private void selectFile(DiffFile f) {
        selected = f;
        // 延迟到下一帧:选择事件发生在布局周期内,立即切换内容会触发
        // VirtualFlow "index exceeds maxCellCount" 告警
        javafx.application.Platform.runLater(() -> {
            if (selected != f) return; // 选择已变化
            setState(true);
            renderViewer();
        });
    }

    private void backToList() {
        selected = null;
        diffView.clear();
        setState(false);
    }

    private void renderViewer() {
        if (selected == null) return;
        if (mode == ViewMode.DIFF) {
            diffView.showFile(selected);
            return;
        }
        // 文件视图
        if (selected.deletedFile) {
            diffView.showPlainFile(displayPath(selected),
                    List.of("(该提交删除了此文件,可切换差异视图查看删除内容)"));
            return;
        }
        if (selected.binary) {
            diffView.showPlainFile(displayPath(selected), List.of("(二进制文件,不显示内容)"));
            return;
        }
        String relPath = selected.newPath.isEmpty() ? selected.oldPath : selected.newPath;
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null || current == null) return;
        final DiffFile requested = selected;
        Fx.bg("读取文件内容…", guard, () -> org.easygit.core.NativeGit.fileContentAt(repo, current.id, relPath),
                content -> {
                    if (current == null || selected != requested) return; // 选择已切换,丢弃过期结果
                    diffView.showPlainFile(relPath, List.of(content.split("\n", -1)));
                });
    }

    // ---------- 文件列表 / 树 ----------

    private void rebuildFileViews() {
        updatingUi = true;
        List<DiffFile> filtered = new ArrayList<>();
        for (DiffFile f : files) {
            if (matches(displayPath(f), search.getText())) filtered.add(f);
        }
        fileList.getItems().setAll(filtered);
        fileTree.setRoot(buildTree(filtered));
        // 选择失效处理
        if (selected != null && !filtered.contains(selected)) {
            selected = null;
            setState(false);
            diffView.clear();
        }
        updatingUi = false;
    }

    /** 文件树构建用的临时节点。 */
    private static final class TrieNode {
        String name = "";
        boolean file = false;
        DiffFile fileRef;
        final java.util.TreeMap<String, TrieNode> children = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        boolean isFolder() { return !file && !children.isEmpty(); }
    }

    /** 树形展开/收起状态(按路径),跨重建保留。 */
    private final Map<String, Boolean> treeExpandState = new HashMap<>();

    /**
     * IDEA 风格树形:
     * 1) 目录在前、文件在后,各自按名称排序(忽略大小写);
     * 2) 只含单个子目录的目录链合并为一个节点,段间用"."连接(Compact Middle Packages),
     *    例如 src/main/java/net/cyue/agent/host 合并为 src.main.java.net.cyue.agent.host;
     * 3) 展开状态在搜索/重建后保持。
     */
    private TreeItem<TreeEnt> buildTree(List<DiffFile> list) {
        TrieNode root = new TrieNode();
        for (DiffFile f : list) {
            String[] parts = treePath(f).split("/");
            TrieNode cur = root;
            for (int i = 0; i < parts.length; i++) {
                final boolean leaf = i == parts.length - 1;
                final DiffFile ref = f;
                cur = cur.children.computeIfAbsent(parts[i], k -> {
                    TrieNode n = new TrieNode();
                    n.name = k;
                    if (leaf) {
                        n.file = true;
                        n.fileRef = ref;
                    }
                    return n;
                });
            }
        }
        return folderItem("<root>", root, "");
    }

    private static String treePath(DiffFile f) {
        return f.newPath.isEmpty() ? f.oldPath : f.newPath;
    }

    private TreeItem<TreeEnt> folderItem(String name, TrieNode node, String path) {
        // 合并只含单个子目录的目录链
        List<String> segs = new ArrayList<>();
        segs.add(name);
        TrieNode cur = node;
        String curPath = path;
        while (cur.children.size() == 1) {
            var e = cur.children.firstEntry();
            if (e.getValue().file) break; // 唯一子是文件,不合并
            segs.add(e.getKey());
            curPath = curPath.isEmpty() ? e.getKey() : curPath + "/" + e.getKey();
            cur = e.getValue();
        }
        TreeItem<TreeEnt> item = new TreeItem<>(new TreeEnt(String.join(".", segs), curPath, null, true));
        Boolean st = treeExpandState.get(curPath);
        item.setExpanded(st == null || st);
        final String pathKey = curPath;
        item.expandedProperty().addListener((o, ov, nv) -> treeExpandState.put(pathKey, nv));

        // 目录在前、文件在后
        List<TreeItem<TreeEnt>> folders = new ArrayList<>();
        List<TreeItem<TreeEnt>> fileItems = new ArrayList<>();
        for (var e : cur.children.entrySet()) {
            String childName = e.getKey();
            TrieNode ch = e.getValue();
            String childPath = curPath.isEmpty() ? childName : curPath + "/" + childName;
            if (ch.isFolder()) {
                folders.add(folderItem(childName, ch, childPath));
            } else {
                fileItems.add(new TreeItem<>(new TreeEnt(childName, treePath(ch.fileRef), ch.fileRef, false)));
            }
        }
        item.getChildren().addAll(folders);
        item.getChildren().addAll(fileItems);
        return item;
    }

    // ---------- 渲染辅助 ----------

    private void renderMessage() {
        if (current == null) {
            bodyScroll.setVisible(false);
            bodyScroll.setManaged(false);
            return;
        }
        String messageText = current.subject + (current.body.isBlank() ? "" : "\n\n" + current.body.strip());
        if (messageText.isBlank()) {
            bodyScroll.setVisible(false);
            bodyScroll.setManaged(false);
            return;
        }
        bodyScroll.setVisible(true);
        bodyScroll.setManaged(true);
        body.setText(messageText);
        // 估算可视行数(硬换行 + 长行自动折行)
        int hardLines = messageText.split("\n", -1).length;
        bodyVisualLines = Math.max(hardLines, (int) Math.ceil(messageText.length() / 50.0));
        if (bodyVisualLines <= 5) {
            // 短消息:按行数自适应,不留大片空白
            bodyScroll.setPrefHeight(Math.min(160, bodyVisualLines * BODY_LINE_HEIGHT + 14));
        } else {
            updateBodyHeight();
        }
    }

    /** 长消息高度:详情区的 30%,最小 160,上限 280 —— 不占用太多纵向空间,内部滚动。 */
    private void updateBodyHeight() {
        double h = getHeight() * 0.3;
        bodyScroll.setPrefHeight(Math.max(160, Math.min(280, h)));
    }

    private static String displayPath(DiffFile f) {
        return f.displayPath();
    }

    private static String abbreviateMiddle(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int keep = Math.max(4, (max - 1) / 2);
        return s.substring(0, keep) + "…" + s.substring(s.length() - keep);
    }

    private static boolean matches(String path, String q) {
        if (q == null || q.isBlank()) return true;
        q = q.strip().toLowerCase();
        String lp = path.toLowerCase();
        if (q.contains("*") || q.contains("?")) {
            StringBuilder re = new StringBuilder();
            for (char c : q.toCharArray()) {
                if (c == '*') re.append(".*");
                else if (c == '?') re.append('.');
                else {
                    if ("\\.^$|()[]{}".indexOf(c) >= 0) re.append('\\');
                    re.append(c);
                }
            }
            return lp.matches(re.toString());
        }
        return lp.contains(q);
    }

    private static Color extColor(String path) {
        String p = path.toLowerCase();
        String ext = p.contains(".") ? p.substring(p.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "java", "kt", "kts", "groovy", "scala" -> Color.web("#58a6ff");
            case "gradle", "xml", "properties", "yml", "yaml", "json", "toml" -> Color.web("#d29922");
            case "md", "txt", "adoc" -> Color.web("#a371f7");
            case "png", "jpg", "jpeg", "gif", "ico", "svg", "webp" -> Color.web("#3fb950");
            case "c", "h", "cpp", "hpp", "rs", "go", "py", "rb" -> Color.web("#f85149");
            default -> Color.web("#9aa4af");
        };
    }

    private static Label dot(String path) {
        Label l = new Label("●");
        l.setTextFill(extColor(path));
        return l;
    }

    // ---------- 单元格 ----------

    private class FileCell extends ListCell<DiffFile> {
        @Override
        protected void updateItem(DiffFile f, boolean empty) {
            super.updateItem(f, empty);
            if (empty || f == null) {
                setGraphic(null);
                setText(null);
                setTooltip(null);
                return;
            }
            // 全名交给 Label,由 LEADING_ELLIPSIS 按可用宽度自适应:变窄自动截断,
            // 变宽自动展开(手工按当时宽度截死的话,拉伸后文件名永远停在省略状态)。
            Label path = new Label(displayPath(f));
            path.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            path.setMinWidth(0);
            path.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(path, Priority.ALWAYS);
            // 增删行数徽标(设计语言:语义色 + 等宽字)
            Label addStat = new Label("+" + f.added);
            addStat.getStyleClass().addAll("mono", "stat-add");
            Label delStat = new Label("-" + f.deleted);
            delStat.getStyleClass().addAll("mono", "stat-del");
            HBox box = new HBox(8, dot(displayPath(f)), path, addStat, delStat);
            box.setAlignment(Pos.CENTER_LEFT);
            if (getListView() != null) {
                box.prefWidthProperty().bind(getListView().widthProperty().subtract(30));
            }
            setGraphic(box);
            setTooltip(Fx.tip(displayPath(f) + "   +" + f.added + " -" + f.deleted));
        }
    }

    private class TreeCellOf extends TreeCell<TreeEnt> {
        @Override
        protected void updateItem(TreeEnt ent, boolean empty) {
            super.updateItem(ent, empty);
            if (empty || ent == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            if (ent.folder()) {
                setGraphic(new Label("📁 " + ent.name()));
                setText(null);
                // 合并中间目录后的名字很长(窄栏会被裁),悬停给真实目录路径
                setTooltip(Fx.tip(ent.fullPath()));
            } else {
                int depth = (getTreeView() != null && getTreeItem() != null)
                        ? getTreeView().getTreeItemLevel(getTreeItem()) : 0;
                Label p = new Label(ent.fullPath());
                p.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
                p.setMinWidth(0);
                p.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(p, Priority.ALWAYS);
                HBox box = new HBox(8, dot(ent.fullPath()), p);
                box.setAlignment(Pos.CENTER_LEFT);
                if (getTreeView() != null) {
                    box.prefWidthProperty().bind(getTreeView().widthProperty().subtract(46 + depth * 14));
                }
                setGraphic(box);
                setTooltip(Fx.tip(ent.fullPath()));
            }
        }
    }
}
