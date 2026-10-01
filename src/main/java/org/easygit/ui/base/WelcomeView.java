package org.easygit.ui.base;

import org.easygit.core.AppSettings;
import org.easygit.core.AppVersion;
import org.easygit.core.GitProcess;
import org.easygit.core.RepoManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/**
 * 欢迎页:未打开仓库时占满内容区 —— 品牌区 + 三个快捷入口卡片 + 最近仓库列表 + 版本信息。
 *
 * 每次"未打开仓库"时由 {@code MainWindow} 重建(仓库列表可能已变),所以是一段纯构建代码,
 * 没有对外状态。打开/克隆/初始化三个动作由调用方注入,避免这里反向依赖对话框。
 */
public final class WelcomeView {

    private WelcomeView() {}

    /**
     * @param onOpen   打开本地仓库
     * @param onClone  从 URL 克隆
     * @param onInit   初始化新仓库
     */
    public static VBox build(Runnable onOpen, Runnable onClone, Runnable onInit) {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        Label title = new Label("EasyGit");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("原生 JavaFX Git 客户端 · 快速 · 轻量");
        sub.getStyleClass().add("welcome-sub");

        // 快捷入口:卡片式 hero(设计语言 v1),替代三个长条按钮
        HBox actions = new HBox(12,
                actionCard("⌂", "打开仓库", "浏览本地已有的 git 仓库", onOpen),
                actionCard("⇩", "克隆仓库", "从远程 URL 克隆(支持 LFS)", onClone),
                actionCard("✚", "初始化新仓库", "在空目录创建新的仓库", onInit));
        actions.setAlignment(Pos.CENTER);

        Label recentTitle = new Label("我的仓库:");
        recentTitle.getStyleClass().add("h2");
        ListView<AppSettings.RepoEntry> recent = new ListView<>();
        recent.setPrefSize(460, 180);
        recent.setPlaceholder(new Label("还没有仓库,点击上方按钮添加"));
        recent.getItems().addAll(AppSettings.get().repos());
        recent.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            private VBox cell;
            private Label name, path;
            @Override
            protected void updateItem(AppSettings.RepoEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) { setText(null); setGraphic(null); return; }
                if (cell == null) {
                    setText(null);
                    name = new Label();
                    name.getStyleClass().add("file-name");
                    path = new Label();
                    path.getStyleClass().add("row-sub");
                    cell = new VBox(1, name, path);
                }
                name.setText((e.group().isBlank() ? "未分组" : e.group()) + " · " + e.name());
                path.setText(e.path());
                setGraphic(cell);
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

    /** 快捷入口卡片:面板底 + 细描边,hover 描边转 accent。 */
    private static VBox actionCard(String glyph, String title, String desc, Runnable action) {
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
}
