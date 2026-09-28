package org.easygit.ui;

import org.easygit.core.JGitService;
import org.easygit.core.model.ConflictModels.ConflictFile;
import org.easygit.core.model.ConflictModels.ConflictRegion;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 合并冲突解决对话框:
 * 每个冲突块可选择 采用当前(HEAD)/ 采用传入 / 两者都保留,实时预览,保存后自动 git add。
 */
public class ConflictDialog extends Dialog<Void> {

    private final ConflictFile cf;
    private final Runnable refreshAll;
    private final ListView<ConflictRegion> regionList = new ListView<>();
    private final TextArea preview = new TextArea();
    private final Map<ConflictRegion, String> strategy = new HashMap<>(); // ours/theirs/both/null=保留标记
    private Label resolvedLabel;

    public ConflictDialog(ConflictFile cf, Runnable refreshAll) {
        this.cf = cf;
        this.refreshAll = refreshAll;

        setTitle("解决冲突");
        setHeaderText("文件 " + cf.path + " 有 " + cf.regions.size() + " 处冲突");

        regionList.setItems(FXCollections.observableArrayList(cf.regions));
        regionList.setPrefHeight(160);
        regionList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ConflictRegion r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setText(null);
                    return;
                }
                String s = strategy.get(r);
                String tag = s == null ? "未处理" : switch (s) {
                    case "ours" -> "采用当前";
                    case "theirs" -> "采用传入";
                    case "both" -> "两者都保留";
                    case "base" -> "采用 base";
                    default -> "未处理";
                };
                setText(String.format("#%d  第 %d 行  (当前 %d 行 / 传入 %d 行)  →  %s",
                        cf.regions.indexOf(r) + 1, r.startLine, r.ours.size(), r.theirs.size(), tag));
            }
        });

        // 当前选中区域的快捷操作
        Button oursBtn = new Button("采用当前(HEAD)");
        oursBtn.setOnAction(e -> applyToSelected("ours"));
        Button theirsBtn = new Button("采用传入");
        theirsBtn.setOnAction(e -> applyToSelected("theirs"));
        Button bothBtn = new Button("两者都保留");
        bothBtn.setOnAction(e -> applyToSelected("both"));
        Button baseBtn = new Button("采用 base");
        baseBtn.setOnAction(e -> applyToSelected("base"));
        Button resetBtn = new Button("保留冲突标记");
        resetBtn.setOnAction(e -> applyToSelected(null));

        Button allOurs = new Button("全部采用当前");
        allOurs.setOnAction(e -> {
            cf.regions.forEach(r -> strategy.put(r, "ours"));
            refresh();
        });
        Button allTheirs = new Button("全部采用传入");
        allTheirs.setOnAction(e -> {
            cf.regions.forEach(r -> strategy.put(r, "theirs"));
            refresh();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        resolvedLabel = new Label();
        HBox allBar = new HBox(8, allOurs, allTheirs, spacer, resolvedLabel);
        HBox regionBar = new HBox(8, oursBtn, theirsBtn, baseBtn, bothBtn, resetBtn);
        regionBar.setPadding(new Insets(4, 0, 4, 0));

        preview.setPrefRowCount(18);
        preview.setFont(javafx.scene.text.Font.font("Consolas", 13));
        preview.textProperty().addListener((o, ov, nv) -> { /* 可手工编辑 */ });
        refresh();

        Button save = new Button("保存并标记已解决");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> save());
        Button cancel = new Button("取消");
        cancel.setOnAction(e -> close());

        VBox root = new VBox(6,
                new HBox(8, new Label("冲突块列表:"), spacer2(), allBar),
                regionList, regionBar,
                new Label("解决后的文件内容(可直接编辑):"),
                preview,
                new HBox(8, cancel, save));
        root.setPadding(new Insets(8));
        VBox.setVgrow(preview, Priority.ALWAYS);
        VBox.setVgrow(root, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(860, 640);
    }

    private static Region spacer2() { return new Region(); }

    private void applyToSelected(String s) {
        ConflictRegion r = regionList.getSelectionModel().getSelectedItem();
        if (r == null) return;
        if (s == null) strategy.remove(r);
        else strategy.put(r, s);
        refresh();
    }

    private void refresh() {
        regionList.refresh();
        List<String> lines = buildResolved();
        preview.setText(String.join("\n", lines));
        long done = strategy.size();
        resolvedLabel.setText("已解决 " + done + "/" + cf.regions.size());
    }

    private List<String> buildResolved() {
        List<Integer> resolvedIdx = new ArrayList<>();
        List<String> strategies = new ArrayList<>();
        for (int i = 0; i < cf.regions.size(); i++) {
            String s = strategy.get(cf.regions.get(i));
            if (s != null) {
                resolvedIdx.add(i);
                strategies.add(s);
            }
        }
        // ConflictParser.resolve 用单一策略;逐块应用需要自定义
        List<String> out = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < cf.lines.size(); i++) {
            String line = cf.lines.get(i);
            if (idx < cf.regions.size() && i == cf.regions.get(idx).startLine - 1) {
                ConflictRegion r = cf.regions.get(idx);
                String s = strategy.get(r);
                if (s != null) {
                    switch (s) {
                        case "ours" -> out.addAll(r.ours);
                        case "theirs" -> out.addAll(r.theirs);
                        case "base" -> out.addAll(r.base);
                        case "both" -> { out.addAll(r.ours); out.addAll(r.theirs); }
                    }
                } else {
                    out.add("<<<<<<<");
                    out.addAll(r.ours);
                    if (!r.base.isEmpty()) {
                        out.add("|||||||");
                        out.addAll(r.base);
                    }
                    out.add("=======");
                    out.addAll(r.theirs);
                    out.add(">>>>>>>");
                }
                int skip = 1 + r.ours.size() + (r.base.isEmpty() ? 0 : r.base.size() + 1)
                        + 1 + r.theirs.size() + 1;
                i += skip - 1;
                idx++;
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private void save() {
        int unresolved = 0;
        for (ConflictRegion r : cf.regions) {
            if (strategy.get(r) == null) unresolved++;
        }
        if (unresolved > 0 && !Fx.confirm("仍有未处理冲突",
                "还有 " + unresolved + " 处冲突保留标记,确定以当前内容保存并标记已解决?")) {
            return;
        }
        try {
            Path p = org.easygit.core.RepoManager.get().current().resolve(cf.path);
            String content = preview.getText();
            // 统一换行为 \n;git 会按配置处理
            if (!content.endsWith("\n")) content += "\n";
            Files.writeString(p, content, StandardCharsets.UTF_8);
            new JGitService(org.easygit.core.RepoManager.get().current()).stage(List.of(cf.path));
            Fx.status("已解决冲突并暂存 " + cf.path);
            refreshAll.run();
            close();
        } catch (Exception ex) {
            Fx.error("保存失败", ex.getMessage(), null);
        }
    }
}
