package org.easygit.ui;

import org.easygit.core.JGitService;
import org.easygit.core.model.StashEntry;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Stash 面板:创建 / 应用 / pop / 删除。
 */
public class StashPanel extends VBox {

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ListView<StashEntry> list = new ListView<>();
    private final TextField message = new TextField();
    private final Runnable refreshAll;

    public StashPanel(Runnable refreshAll) {
        this.refreshAll = refreshAll;
        setSpacing(0);
        setPadding(javafx.geometry.Insets.EMPTY);

        message.setPromptText("stash 说明(可选)");
        HBox.setHgrow(message, Priority.ALWAYS);
        Button create = new Button("创建 Stash");
        create.getStyleClass().add("primary");
        create.setOnAction(e -> create());
        HBox footer = new HBox(8, message, create);
        footer.setAlignment(Pos.CENTER_LEFT);

        list.setPlaceholder(new Label("没有 stash 记录"));
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                StashEntry s = list.getSelectionModel().getSelectedItem();
                if (s != null) apply(s, false);
            }
        });
        list.setContextMenu(new ContextMenu(
                mi("应用(保留 stash)", () -> {
                    StashEntry s = list.getSelectionModel().getSelectedItem();
                    if (s != null) apply(s, false);
                }),
                mi("应用并删除(pop)", () -> {
                    StashEntry s = list.getSelectionModel().getSelectedItem();
                    if (s != null) apply(s, true);
                }),
                mi("删除", () -> {
                    StashEntry s = list.getSelectionModel().getSelectedItem();
                    if (s != null) drop(s);
                })
        ));
        VBox.setVgrow(list, Priority.ALWAYS);

        // 内容统一 12px 侧边距(与面板头/其他页同一基准线)
        VBox content = new VBox(6, list, footer);
        content.setPadding(new javafx.geometry.Insets(0, 12, 6, 12));
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(Fx.panelHead("Stash"), content);
    }

    private static Path repo() { return org.easygit.core.RepoManager.get().current(); }

    public void refresh(List<StashEntry> entries) {
        list.getItems().setAll(entries);
    }

    private void create() {
        Fx.bg("创建 stash…", () -> {
            String m = message.getText();
            String sha = new JGitService(repo()).stash(m == null ? "" : m, true);
            String msg = sha == null ? "没有可暂存的改动"
                    : "已创建 stash " + sha.substring(0, Math.min(8, sha.length()));
            UiLog.op("git stash push" + (m != null && !m.isBlank() ? " -m \"" + m.strip() + "\"" : "")
                    + (true ? " -u" : ""), "", msg);
            return msg;
        }, msg -> {
            message.clear();
            Fx.status(msg);
            refreshAll.run();
        });
    }

    private void apply(StashEntry s, boolean drop) {
        Fx.bg("应用 stash…", () -> {
            JGitService svc = new JGitService(repo());
            svc.stashApply(s.index);
            if (drop) svc.stashDrop(s.index);
            String msg = (drop ? "已 pop " : "已应用 ") + s.ref();
            UiLog.op("git stash " + (drop ? "pop" : "apply") + " " + s.ref(), "", msg);
            return msg;
        }, msg -> {
            Fx.status(msg);
            refreshAll.run();
        });
    }

    private void drop(StashEntry s) {
        if (!Fx.confirm("删除 stash", "确定删除 " + s.ref() + " " + s.message + "?")) return;
        Fx.bg("删除 stash…", () -> {
            new JGitService(repo()).stashDrop(s.index);
            String msg = "已删除 " + s.ref();
            UiLog.op("git stash drop " + s.ref(), "", msg);
            return msg;
        }, msg -> {
            Fx.status(msg);
            refreshAll.run();
        });
    }

    private static MenuItem mi(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private class Cell extends ListCell<StashEntry> {
        @Override
        protected void updateItem(StashEntry s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(s.message);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label meta = new Label(DTF.format(Instant.ofEpochSecond(s.time)));
            meta.getStyleClass().add("commit-meta");
            Label idx = new Label(s.ref());
            idx.getStyleClass().add("chip");
            HBox box = new HBox(8, idx, name, meta);
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
        }
    }
}
