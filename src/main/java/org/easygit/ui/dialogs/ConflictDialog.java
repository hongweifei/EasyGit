package org.easygit.ui.dialogs;

import org.easygit.core.ConflictIO;
import org.easygit.core.ConflictParser;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.model.ConflictModels.ConflictFile;
import org.easygit.core.model.ConflictModels.ConflictRegion;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.easygit.ui.base.Fx;

/**
 * 合并冲突解决对话框:
 * 每个冲突块可选择 采用当前(HEAD)/ 采用传入 / 两者都保留,实时预览,保存后自动 git add。
 *
 * 写回时按原文件的编码/BOM/换行风格({@link ConflictIO}),并且没让改的部分一个字节都不动 ——
 * 之前的实现统一按 UTF-8 + LF 重写整个文件,CRLF 文件解决一个冲突块就整篇变红,
 * GBK 文件更是直接被毁掉。
 */
public class ConflictDialog extends Dialog<Void> {

    private final ConflictFile cf;
    private final ConflictIO.Content content;
    private final Path file;
    private final Runnable refreshAll;
    private final ListView<ConflictRegion> regionList = new ListView<>();
    private final TextArea preview = new TextArea();
    private final Map<ConflictRegion, String> strategy = new HashMap<>(); // ours/theirs/both/base/null=保留标记
    private Label resolvedLabel;

    public ConflictDialog(ConflictFile cf, ConflictIO.Content content, Path file, Runnable refreshAll) {
        this.cf = cf;
        this.content = content;
        this.file = file;
        this.refreshAll = refreshAll;

        setTitle("解决冲突");
        Fx.icon(this);
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
        Button prevBtn = new Button("上一处");
        prevBtn.setOnAction(e -> stepRegion(-1));
        Button nextBtn = new Button("下一处");
        nextBtn.setOnAction(e -> stepRegion(1));

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
        HBox regionBar = new HBox(8, oursBtn, theirsBtn, baseBtn, bothBtn, resetBtn, prevBtn, nextBtn);
        regionBar.setPadding(new Insets(4, 0, 4, 0));

        preview.setPrefRowCount(18);
        preview.setFont(javafx.scene.text.Font.font("Consolas", 13));
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

    /** 在冲突块之间跳转(多块时不用在长文件里翻)。 */
    private void stepRegion(int delta) {
        int n = cf.regions.size();
        if (n == 0) return;
        int i = regionList.getSelectionModel().getSelectedIndex();
        int next = i < 0 ? (delta > 0 ? 0 : n - 1) : Math.floorMod(i + delta, n);
        regionList.getSelectionModel().select(next);
        regionList.scrollTo(next);
    }

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
        resolvedLabel.setText("已解决 " + done + "/" + cf.regions.size()
                + (ConflictParser.hasMarkers(lines) ? "   ⚠ 仍有冲突标记" : "   ✓ 无冲突标记"));
    }

    /** 逐块按已选策略生成文件内容(未选的块原样保留标记行,含引用名)。 */
    private List<String> buildResolved() {
        return ConflictParser.resolve(cf, (idx, r) -> strategy.get(r));
    }

    /** 预览框里的内容(用户可能手工改过)。 */
    private List<String> previewLines() {
        String t = preview.getText();
        List<String> out = new ArrayList<>(List.of(t.split("\n", -1)));
        if (t.endsWith("\n")) out.remove(out.size() - 1);
        return out;
    }

    private void save() {
        List<String> lines = previewLines();
        if (ConflictParser.hasMarkers(lines) && !Fx.confirm("仍有未处理的冲突",
                "预览内容里还留着冲突标记,确定以当前内容保存并标记已解决?")) {
            return;
        }
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        try {
            // 编码/BOM/换行按原文件写回;然后走 CLI git add(LFS 过滤器安全,索引冲突阶段一并清掉)
            ConflictIO.write(file, content, lines);
            var r = NativeGit.markResolved(repo, List.of(cf.path));
            if (!r.ok()) throw new RuntimeException(r.message());
            Fx.status("已解决冲突并暂存 " + cf.path);
            refreshAll.run();
            close();
        } catch (Exception ex) {
            Fx.error("保存失败", ex.getMessage(), null);
        }
    }
}
