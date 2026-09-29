package org.easygit.ui;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffHunk;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.LineType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * 高性能 diff 渲染:虚拟化 ListView,行式渲染(行号 + 内容),支持多文件与单文件。
 * 支持两种布局:统一(unified,一行一条)与并排(side-by-side,左右两栏对照)。
 */
public class DiffView extends VBox {

    private static final Font MONO = Font.font("Consolas", 13);
    private static final Font MONO_BOLD = Font.font("Consolas", 13);
    /** 行号列宽(统一视图两列、并排视图每栏一列)。 */
    private static final double NO_W = 46;

    private enum RowKind { FILE_HEADER, LINE, PAIR }

    /**
     * 一行。LINE = 统一视图/纯文件的一行,也是并排视图里的整行(文件头、@@ 头、提示)。
     * PAIR = 并排视图的一行:left 为旧文件侧,right 为新文件侧;
     * 某一侧为 null 表示该侧没有对应行(渲染为灰色填充块)。
     */
    private record Row(RowKind kind, DiffFile file, DiffLine left, DiffLine right) {
        static Row header(DiffFile f) { return new Row(RowKind.FILE_HEADER, f, null, null); }
        static Row line(DiffLine l) { return new Row(RowKind.LINE, null, l, null); }
        static Row pair(DiffLine left, DiffLine right) { return new Row(RowKind.PAIR, null, left, right); }
    }

    private final ListView<Row> list = new ListView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    /** 文件视图模式:只显示一列行号(差异视图为两列)。 */
    private final javafx.beans.property.BooleanProperty plainFile =
            new javafx.beans.property.SimpleBooleanProperty(false);
    /** 并排对照模式。纯文件模式下没有对比意义,始终按统一视图渲染。 */
    private final BooleanProperty sideBySide = new SimpleBooleanProperty(false);

    // 当前数据源:切换布局时据此重建,不必由调用方重新取数
    private List<DiffFile> currentFiles = List.of();
    private String plainTitle;              // 非 null 表示纯文件模式
    private List<String> plainLines = List.of();

    public DiffView() {
        setSpacing(0);
        list.getStyleClass().add("diff-list");
        list.setFixedCellSize(21);
        list.setCellFactory(v -> new Cell());
        list.setItems(rows);
        getChildren().add(list);
        VBox.setVgrow(list, Priority.ALWAYS);
        // 布局切换后立即重建(纯文件模式在 rebuild 内部仍按统一视图处理)
        sideBySide.addListener((o, ov, nv) -> rebuild());
    }

    public void clear() {
        currentFiles = List.of();
        plainTitle = null;
        plainLines = List.of();
        rows.clear();
    }

    /** 展示一组文件的完整 diff。 */
    public void showFiles(List<DiffFile> files) {
        plainTitle = null;
        plainLines = List.of();
        currentFiles = files == null ? List.of() : files;
        rebuild();
    }

    /** 展示纯文件内容(单列行号),用于提交的"文件视图"。 */
    public void showPlainFile(String title, List<String> lines) {
        plainTitle = title;
        plainLines = lines == null ? List.of() : lines;
        currentFiles = List.of();
        rebuild();
    }

    /** 展示单个文件。 */
    public void showFile(DiffFile file) {
        showFiles(file == null ? List.of() : List.of(file));
    }

    /** 是否并排对照。切换后立即用已持有的数据重建,不要求调用方重新取数。 */
    public void setSideBySide(boolean v) { sideBySide.set(v); }

    public boolean isSideBySide() { return sideBySide.get(); }

    public BooleanProperty sideBySideProperty() { return sideBySide; }

    /**
     * 生成"统一/并排"切换按钮(分段控件),由调用方放进自己的工具栏。
     * 纯文件模式下自动禁用。
     */
    public Node makeLayoutSwitch() {
        ToggleButton unified = new ToggleButton("统一");
        ToggleButton side = new ToggleButton("并排");
        unified.getStyleClass().add("diff-mode-btn");
        side.getStyleClass().add("diff-mode-btn");
        ToggleGroup group = new ToggleGroup();
        unified.setToggleGroup(group);
        side.setToggleGroup(group);
        (sideBySide.get() ? side : unified).setSelected(true);
        unified.setOnAction(e -> { if (unified.isSelected()) sideBySide.set(false); });
        side.setOnAction(e -> { if (side.isSelected()) sideBySide.set(true); });
        sideBySide.addListener((o, ov, nv) -> (nv ? side : unified).setSelected(true));
        unified.disableProperty().bind(plainFile);
        side.disableProperty().bind(plainFile);
        HBox bar = new HBox(0, unified, side);
        bar.getStyleClass().add("diff-mode-switch");
        return bar;
    }

    public void scrollToTop() {
        if (!rows.isEmpty()) list.scrollTo(0);
    }

    public void scrollToPath(String path) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind() == RowKind.FILE_HEADER && r.file().displayPath().equals(path)) {
                list.scrollTo(i);
                return;
            }
        }
    }

    // ---------- 行构建 ----------

    /** 用当前数据源与布局模式重建行。复用同一个 ObservableList,切换时视口不闪、滚动位置尽量保留。 */
    private void rebuild() {
        plainFile.set(plainTitle != null);
        List<Row> rs = new ArrayList<>();
        if (plainTitle != null) {
            buildPlain(rs);
        } else if (sideBySide.get()) {
            buildSideBySide(rs);
        } else {
            buildUnified(rs);
        }
        rows.setAll(rs);
    }

    private void buildUnified(List<Row> rs) {
        for (DiffFile f : currentFiles) {
            rs.add(Row.header(f));
            if (f.binary) {
                rs.add(Row.line(new DiffLine(LineType.CONTEXT, -1, -1, "(二进制文件,内容未显示)")));
                continue;
            }
            for (DiffHunk h : f.hunks) {
                rs.add(Row.line(new DiffLine(LineType.HUNK, -1, -1, h.header)));
                for (DiffLine l : h.lines) rs.add(Row.line(l));
            }
            if (f.hunks.isEmpty() && !f.newFile && !f.deletedFile) {
                rs.add(Row.line(new DiffLine(LineType.CONTEXT, -1, -1, "(仅文件模式变化,无内容差异)")));
            }
        }
    }

    private void buildPlain(List<Row> rs) {
        DiffFile f = new DiffFile();
        f.oldPath = plainTitle;
        f.newPath = plainTitle;
        rs.add(Row.header(f));
        int n = 1;
        for (String l : plainLines) {
            rs.add(Row.line(new DiffLine(LineType.CONTEXT, -1, n, l)));
            n++;
        }
    }

    /** 并排对照。文件头、@@ 头与提示行仍然整行横跨两栏,只有内容行左右配对。 */
    private void buildSideBySide(List<Row> rs) {
        for (DiffFile f : currentFiles) {
            rs.add(Row.header(f));
            if (f.binary) {
                rs.add(Row.line(new DiffLine(LineType.CONTEXT, -1, -1, "(二进制文件,内容未显示)")));
                continue;
            }
            for (DiffHunk h : f.hunks) {
                rs.add(Row.line(new DiffLine(LineType.HUNK, -1, -1, h.header)));
                pairHunkLines(rs, h.lines);
            }
            if (f.hunks.isEmpty() && !f.newFile && !f.deletedFile) {
                rs.add(Row.line(new DiffLine(LineType.CONTEXT, -1, -1, "(仅文件模式变化,无内容差异)")));
            }
        }
    }

    /**
     * 把 hunk 内的行配成左右对:连续的删除块与新增块逐行配对,
     * 数量不足的一侧用 null 补齐(渲染为灰色填充块),保证左右行数一致、上下对齐。
     * git 的 unified 格式在同一处修改里总是先列删除行再列新增行,这里按该约定扫描。
     */
    private void pairHunkLines(List<Row> rs, List<DiffLine> lines) {
        int i = 0;
        final int n = lines.size();
        while (i < n) {
            DiffLine l = lines.get(i);
            if (l.type() == LineType.CONTEXT) {
                rs.add(Row.pair(l, l));
                i++;
                continue;
            }
            List<DiffLine> dels = new ArrayList<>();
            List<DiffLine> adds = new ArrayList<>();
            while (i < n && lines.get(i).type() == LineType.DEL) dels.add(lines.get(i++));
            while (i < n && lines.get(i).type() == LineType.ADD) adds.add(lines.get(i++));
            if (dels.isEmpty() && adds.isEmpty()) {   // 异常数据兜底,保证 while 一定前进
                rs.add(Row.pair(l, l));
                i++;
                continue;
            }
            int count = Math.max(dels.size(), adds.size());
            for (int k = 0; k < count; k++) {
                rs.add(Row.pair(k < dels.size() ? dels.get(k) : null,
                                k < adds.size() ? adds.get(k) : null));
            }
        }
    }

    // ---------- 渲染 ----------

    private class Cell extends ListCell<Row> {
        // 统一视图的一行
        private final Label oldNo = new Label();
        private final Label newNo = new Label();
        private final Label code = new Label();
        private final HBox lineBox = new HBox(oldNo, newNo, code);
        // 并排视图的一行(左右两栏各占一半)
        private final Label lNo = new Label();
        private final Label lCode = new Label();
        private final HBox lHalf = makeHalf(lNo, lCode, false);
        private final Label rNo = new Label();
        private final Label rCode = new Label();
        private final HBox rHalf = makeHalf(rNo, rCode, true);
        private final HBox pairBox = new HBox(lHalf, rHalf);
        private final Label header = new Label();
        private final VBox box = new VBox();

        Cell() {
            oldNo.getStyleClass().addAll("mono", "diff-no");
            newNo.getStyleClass().addAll("mono", "diff-no");
            oldNo.setMinWidth(NO_W);
            oldNo.setPrefWidth(NO_W);
            newNo.setMinWidth(NO_W);
            newNo.setPrefWidth(NO_W);
            oldNo.setAlignment(Pos.CENTER_RIGHT);
            newNo.setAlignment(Pos.CENTER_RIGHT);
            code.getStyleClass().add("mono");
            code.setFont(MONO);
            code.setWrapText(false);
            lineBox.getStyleClass().add("diff-row");
            lineBox.setSpacing(0);
            lineBox.setAlignment(Pos.CENTER_LEFT);
            // 不设的话 HBox 只按内容宽度收缩,增删底纹只铺到文本长度为止,
            // 右侧会露出单元格自己的底色(短行时很明显)
            lineBox.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(code, Priority.ALWAYS);
            code.setMaxWidth(Double.MAX_VALUE);

            pairBox.getStyleClass().add("diff-row");
            pairBox.setSpacing(0);
            pairBox.setAlignment(Pos.CENTER_LEFT);
            pairBox.setMaxWidth(Double.MAX_VALUE);

            header.getStyleClass().add("diff-file-header");
            header.setFont(MONO_BOLD);
            header.setPadding(new Insets(3, 8, 3, 8));
            header.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().setAll(header);
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        /**
         * 并排视图的一个半栏。
         * prefWidth 归零 + Hgrow ALWAYS:两栏各分到一半宽度,与内容长短无关;
         * 不这样写的话 HBox 会按内容分配,左右栏宽度随文本变化而无法对齐。
         */
        private HBox makeHalf(Label no, Label code, boolean right) {
            no.getStyleClass().addAll("mono", "diff-no");
            no.setMinWidth(NO_W);
            no.setPrefWidth(NO_W);
            no.setAlignment(Pos.CENTER_RIGHT);
            code.getStyleClass().add("mono");
            code.setFont(MONO);
            code.setWrapText(false);
            code.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(code, Priority.ALWAYS);
            HBox h = new HBox(no, code);
            h.getStyleClass().addAll("diff-half", right ? "diff-right" : "diff-left");
            h.setSpacing(0);
            h.setAlignment(Pos.CENTER_LEFT);
            h.setMinWidth(0);
            h.setPrefWidth(0);
            h.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(h, Priority.ALWAYS);
            return h;
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
            if (row.kind() == RowKind.PAIR) {
                // 并排视图:左栏永远只可能是删除/上下文,右栏只可能是新增/上下文
                setupHalf(lHalf, lNo, lCode, row.left(), true);
                setupHalf(rHalf, rNo, rCode, row.right(), false);
                box.getChildren().setAll(pairBox);
                setGraphic(box);
                return;
            }
            DiffLine l = row.left();
            code.setText(l.text());
            // 文件视图模式只显示一列行号
            boolean plain = plainFile.get();
            oldNo.setVisible(!plain);
            oldNo.setMinWidth(plain ? 0 : NO_W);
            oldNo.setPrefWidth(plain ? 0 : NO_W);
            oldNo.setText(!plain && l.oldNo() > 0 ? String.valueOf(l.oldNo()) : "");
            newNo.setText(l.newNo() > 0 ? String.valueOf(l.newNo()) : "");
            // 只换类型类；"diff-row" 已在构造时加过一次。
            // 旧写法每次 updateItem 都 addAll("diff-row", ...)，而 removeAll 不含它，
            // 单元格被复用时 styleClass 会无限累积成 diff-row diff-row diff-row …
            lineBox.getStyleClass().removeAll("diff-add", "diff-del", "diff-ctx", "diff-hunk");
            switch (l.type()) {
                case ADD -> lineBox.getStyleClass().add("diff-add");
                case DEL -> lineBox.getStyleClass().add("diff-del");
                case HUNK -> {
                    lineBox.getStyleClass().add("diff-hunk");
                    oldNo.setText("");
                    newNo.setText("");
                }
                default -> lineBox.getStyleClass().add("diff-ctx");
            }
            box.getChildren().setAll(lineBox);
            setGraphic(box);
        }

        /** 并排视图的半栏上色。l 为 null 表示该侧无对应行,渲染为灰色填充块。 */
        private void setupHalf(HBox half, Label no, Label code, DiffLine l, boolean leftSide) {
            half.getStyleClass().removeAll("diff-add", "diff-del", "diff-ctx", "diff-filler");
            if (l == null) {
                half.getStyleClass().add("diff-filler");
                no.setText("");
                code.setText("");
                return;
            }
            switch (l.type()) {
                case ADD -> half.getStyleClass().add("diff-add");
                case DEL -> half.getStyleClass().add("diff-del");
                default -> half.getStyleClass().add("diff-ctx");
            }
            int num = leftSide ? l.oldNo() : l.newNo();
            no.setText(num > 0 ? String.valueOf(num) : "");
            code.setText(l.text());
        }
    }
}
