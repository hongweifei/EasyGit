package org.easygit.ui;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffHunk;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.LineType;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * 高性能 diff 渲染:虚拟化 ListView,行式渲染(行号 + 内容),支持多文件与单文件。
 */
public class DiffView extends VBox {

    private static final Font MONO = Font.font("Consolas", 13);
    private static final Font MONO_BOLD = Font.font("Consolas", 13);

    private enum RowKind { FILE_HEADER, LINE }

    private record Row(RowKind kind, DiffFile file, DiffLine line) {}

    private final ListView<Row> list = new ListView<>();
    private final Label emptyHint = new Label("选择文件或提交以查看差异");
    /** 文件视图模式:只显示一列行号(差异视图为两列)。 */
    private final javafx.beans.property.BooleanProperty plainFile =
            new javafx.beans.property.SimpleBooleanProperty(false);

    public DiffView() {
        setSpacing(0);
        emptyHint.getStyleClass().add("dim");
        list.setFixedCellSize(21);
        list.setCellFactory(v -> new Cell());
        list.getItems().addListener((javafx.collections.ListChangeListener<Row>) c -> {
            emptyHint.setVisible(list.getItems().isEmpty());
        });
        getChildren().addAll(emptyHint, list);
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    public void clear() {
        list.getItems().clear();
    }

    /** 展示一组文件的完整 diff。 */
    public void showFiles(List<DiffFile> files) {
        plainFile.set(false);
        List<Row> rows = new ArrayList<>();
        if (files != null) {
            for (DiffFile f : files) {
                rows.add(new Row(RowKind.FILE_HEADER, f, null));
                if (f.binary) {
                    rows.add(new Row(RowKind.LINE, f, new DiffLine(LineType.CONTEXT, -1, -1, "(二进制文件,内容未显示)")));
                } else {
                    for (DiffHunk h : f.hunks) {
                        rows.add(new Row(RowKind.LINE, f, new DiffLine(LineType.HUNK, -1, -1, h.header)));
                        rows.addAll(h.lines.stream().map(l -> new Row(RowKind.LINE, f, l)).toList());
                    }
                    if (f.hunks.isEmpty() && !f.newFile && !f.deletedFile) {
                        rows.add(new Row(RowKind.LINE, f, new DiffLine(LineType.CONTEXT, -1, -1, "(仅文件模式变化,无内容差异)")));
                    }
                }
            }
        }
        list.setItems(FXCollections.observableArrayList(rows));
    }

    /** 展示纯文件内容(单列行号),用于提交的"文件视图"。 */
    public void showPlainFile(String title, List<String> lines) {
        plainFile.set(true);
        DiffFile f = new DiffFile();
        f.oldPath = title;
        f.newPath = title;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(RowKind.FILE_HEADER, f, null));
        int n = 1;
        for (String l : lines) {
            rows.add(new Row(RowKind.LINE, f, new DiffLine(LineType.CONTEXT, -1, n, l)));
            n++;
        }
        list.setItems(FXCollections.observableArrayList(rows));
    }

    /** 展示单个文件。 */
    public void showFile(DiffFile file) {
        showFiles(file == null ? List.of() : List.of(file));
    }

    public void scrollToTop() {
        if (!list.getItems().isEmpty()) list.scrollTo(0);
    }

    public void scrollToPath(String path) {
        for (int i = 0; i < list.getItems().size(); i++) {
            Row r = list.getItems().get(i);
            if (r.kind == RowKind.FILE_HEADER && r.file.displayPath().equals(path)) {
                list.scrollTo(i);
                return;
            }
        }
    }

    // ---------- 渲染 ----------

    private class Cell extends ListCell<Row> {
        private final Label oldNo = new Label();
        private final Label newNo = new Label();
        private final Label code = new Label();
        private final HBox lineBox = new HBox(oldNo, newNo, code);
        private final Label header = new Label();
        private final VBox box = new VBox();

        Cell() {
            oldNo.getStyleClass().addAll("mono", "diff-no");
            newNo.getStyleClass().addAll("mono", "diff-no");
            oldNo.setMinWidth(46);
            oldNo.setPrefWidth(46);
            newNo.setMinWidth(46);
            newNo.setPrefWidth(46);
            oldNo.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            newNo.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            code.getStyleClass().add("mono");
            code.setFont(MONO);
            code.setWrapText(false);
            lineBox.setSpacing(0);
            lineBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            HBox.setHgrow(code, Priority.ALWAYS);
            code.setMaxWidth(Double.MAX_VALUE);
            header.getStyleClass().add("diff-file-header");
            header.setFont(MONO_BOLD);
            header.setPadding(new Insets(3, 8, 3, 8));
            header.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().setAll(header);
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            if (row.kind() == RowKind.FILE_HEADER) {
                DiffFile f = row.file();
                String stat = f.binary ? "(二进制)"
                        : (f.added == 0 && f.deleted == 0 ? "" : " +" + f.added + " -" + f.deleted);
                header.setText(f.displayPath() + (stat.isEmpty() ? "" : "   " + stat));
                box.getChildren().setAll(header);
                setGraphic(box);
                return;
            }
            DiffLine l = row.line();
            code.setText(l.text());
            // 文件视图模式只显示一列行号
            boolean plain = plainFile.get();
            oldNo.setVisible(!plain);
            oldNo.setMinWidth(plain ? 0 : 46);
            oldNo.setPrefWidth(plain ? 0 : 46);
            oldNo.setText(!plain && l.oldNo() > 0 ? String.valueOf(l.oldNo()) : "");
            newNo.setText(l.newNo() > 0 ? String.valueOf(l.newNo()) : "");
            lineBox.getStyleClass().removeAll("diff-add", "diff-del", "diff-ctx", "diff-hunk");
            switch (l.type()) {
                case ADD -> lineBox.getStyleClass().addAll("diff-row", "diff-add");
                case DEL -> lineBox.getStyleClass().addAll("diff-row", "diff-del");
                case HUNK -> {
                    lineBox.getStyleClass().addAll("diff-row", "diff-hunk");
                    oldNo.setText("");
                    newNo.setText("");
                    code.setStyle("-fx-text-fill: -accent;");
                }
                default -> lineBox.getStyleClass().addAll("diff-row", "diff-ctx");
            }
            if (l.type() != LineType.HUNK) code.setStyle("");
            box.getChildren().setAll(lineBox);
            setGraphic(box);
        }
    }
}
