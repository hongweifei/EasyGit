package org.easygit.ui;

import org.easygit.core.AppSettings;
import org.easygit.core.GitLocator;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * 设置对话框:Git 配置(当前仓库/全局,常用键快捷编辑 + 全量键值管理) + 应用设置。
 */
public final class SettingsDialog {

    private SettingsDialog() {}

    public static void show(javafx.stage.Window owner, javafx.scene.layout.Pane themeTarget) {
        Dialog<Void> d = new Dialog<>();
        Fx.icon(d);
        d.setTitle("设置");
        d.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        d.getDialogPane().setPrefSize(720, 560);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(gitConfigTab(), appTab(themeTarget));
        d.getDialogPane().setContent(tabs);
        d.showAndWait();
    }

    // ---------- Git 配置 ----------

    private static Tab gitConfigTab() {
        ComboBox<String> scope = new ComboBox<>();
        scope.getItems().addAll("当前仓库(local)", "全局(global)");
        boolean hasRepo = RepoManager.get().current() != null;
        scope.getSelectionModel().select(hasRepo ? 0 : 1);
        if (!hasRepo) scope.setDisable(true);

        TextField nameField = new TextField(NativeGit.configGet("user.name"));
        nameField.setPromptText("你的名字");
        TextField emailField = new TextField(NativeGit.configGet("user.email"));
        emailField.setPromptText("you@example.com");
        Button applyUser = new Button("保存用户信息");
        applyUser.getStyleClass().add("primary");

        ListView<String> entries = new ListView<>();
        entries.setPrefHeight(220);
        entries.setPlaceholder(new Label("该作用域还没有配置项"));
        TextField key = new TextField();
        key.setPromptText("键,如 core.longpaths");
        HBox.setHgrow(key, Priority.ALWAYS);
        TextField value = new TextField();
        value.setPromptText("值(留空=删除该项)");
        HBox.setHgrow(value, Priority.ALWAYS);

        Runnable reload = () -> {
            boolean global = scope.getSelectionModel().getSelectedIndex() == 1;
            Path repo = RepoManager.get().current();
            entries.getItems().setAll(NativeGit.configList(global, repo));
        };

        scope.setOnAction(e -> {
            reload.run();
            boolean global = scope.getSelectionModel().getSelectedIndex() == 1;
            // 用户信息快捷栏读取合并值,编辑后写入所选作用域
        });
        applyUser.setOnAction(e -> {
            boolean global = scope.getSelectionModel().getSelectedIndex() == 1;
            Path repo = RepoManager.get().current();
            if (!nameField.getText().isBlank()) {
                NativeGit.configSet(global, repo, "user.name", nameField.getText().strip());
            }
            if (!emailField.getText().isBlank()) {
                NativeGit.configSet(global, repo, "user.email", emailField.getText().strip());
            }
            UiLog.op("git config user.name/user.email", "已写入"
                    + (global ? " 全局" : " 当前仓库"), "");
            Fx.status("用户信息已保存");
            reload.run();
        });

        Button save = new Button("保存 / 覆盖");
        save.setOnAction(e -> {
            String k = key.getText().strip();
            String v = value.getText();
            if (k.isEmpty()) {
                Fx.error("无法保存", "键不能为空", null);
                return;
            }
            boolean global = scope.getSelectionModel().getSelectedIndex() == 1;
            Path repo = RepoManager.get().current();
            if (v.isEmpty()) {
                NativeGit.configUnset(global, repo, k);
                UiLog.op("git config --unset-all " + k, "已删除", "");
            } else {
                var r = NativeGit.configSet(global, repo, k, v);
                UiLog.op("git config " + k, r.out(), r.err());
                if (!r.ok()) {
                    Fx.error("保存失败", r.message(), null);
                    return;
                }
            }
            Fx.status("已保存 " + k);
            key.clear();
            value.clear();
            reload.run();
        });
        Button del = new Button("删除所选");
        del.setOnAction(e -> {
            String sel = entries.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String k = sel.contains("=") ? sel.substring(0, sel.indexOf('=')).strip() : sel;
            if (!Fx.confirm("删除配置", "确定删除 " + k + "?(该作用域内所有同名项)")) return;
            boolean global = scope.getSelectionModel().getSelectedIndex() == 1;
            Path repo = RepoManager.get().current();
            NativeGit.configUnset(global, repo, k);
            UiLog.op("git config --unset-all " + k, "已删除", "");
            reload.run();
        });
        entries.setOnMouseClicked(e -> {
            String sel = entries.getSelectionModel().getSelectedItem();
            if (sel != null && sel.contains("=")) {
                key.setText(sel.substring(0, sel.indexOf('=')).strip());
                value.setText(sel.substring(sel.indexOf('=') + 1));
            }
        });

        GridPane quick = new GridPane();
        quick.setHgap(8);
        quick.setVgap(6);
        quick.add(new Label("user.name:"), 0, 0);
        quick.add(nameField, 1, 0);
        quick.add(new Label("user.email:"), 0, 1);
        quick.add(emailField, 1, 1);
        quick.add(applyUser, 1, 2);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(emailField, Priority.ALWAYS);

        VBox box = new VBox(8,
                new HBox(8, new Label("作用域:"), scope),
                new javafx.scene.control.Separator(),
                quick,
                new javafx.scene.control.Separator(),
                new HBox(8, new Label("全部配置项(选中可编辑):")),
                entries,
                new HBox(8, key, value),
                new HBox(8, save, del));
        box.setPadding(new Insets(8));
        VBox.setVgrow(entries, Priority.ALWAYS);

        reload.run();
        Tab tab = new Tab("Git 配置", box);
        return tab;
    }

    // ---------- 应用设置 ----------

    private static Tab appTab(javafx.scene.layout.Pane themeTarget) {
        AppSettings s = AppSettings.get();

        ComboBox<String> theme = new ComboBox<>();
        theme.getItems().addAll("亮色", "暗色");
        theme.getSelectionModel().select("dark".equals(s.theme()) ? 1 : 0);

        TextField maxCommits = new TextField(String.valueOf(s.maxCommits()));
        maxCommits.setPromptText("如 2000");

        TextField gitPath = new TextField(s.gitPath());
        gitPath.setPromptText("留空=自动探测(Git Bash 优先),如 C:\\Program Files\\Git\\cmd\\git.exe");
        HBox.setHgrow(gitPath, Priority.ALWAYS);

        Label note = new Label("说明:git 路径修改后立即生效;主题立即生效;历史条数下次刷新生效。");
        note.getStyleClass().add("dim");
        note.setWrapText(true);

        Button save = new Button("保存应用设置");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> {
            s.setTheme(theme.getSelectionModel().getSelectedIndex() == 1 ? "dark" : "light");
            // 立即应用主题
            var classes = themeTarget.getStyleClass();
            boolean dark = "dark".equals(s.theme());
            if (dark && !classes.contains("dark")) classes.add("dark");
            if (!dark) classes.remove("dark");
            try {
                s.setMaxCommits(Integer.parseInt(maxCommits.getText().strip()));
            } catch (Exception ex) {
                Fx.error("无法保存", "历史条数必须是数字", maxCommits.getText());
                return;
            }
            s.setGitPath(gitPath.getText().strip());
            GitLocator.reset();
            Fx.status("应用设置已保存");
        });

        VBox box = new VBox(10,
                new HBox(8, new Label("主题:"), theme),
                new HBox(8, new Label("历史条数:"), maxCommits),
                new HBox(8, new Label("git 路径:"), gitPath),
                note, save);
        box.setPadding(new Insets(10));
        Tab tab = new Tab("应用", box);
        return tab;
    }
}
