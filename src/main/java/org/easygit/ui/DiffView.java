package org.easygit.ui;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffHunk;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.LineType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

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
    /** 并排视图。纯文件模式下没有对比意义,始终按统一视图渲染。 */
    private final BooleanProperty sideBySide = new SimpleBooleanProperty(false);

    /** 底部横向滚动条:代码行比视口宽时用来左右看全(行号列不跟着动)。 */
    private final ScrollBar hbar = new ScrollBar();
    /** 当前横向偏移(像素,>=0);单元格里的代码/文件名按它左移。 */
    private final DoubleProperty hOffset = new SimpleDoubleProperty();
    /** 可横向滚动量 = 最宽一行需要的宽度 - 视口宽;<=0 表示放得下,滚动条隐藏。 */
    private double hScrollMax;
    /** 文本宽度测量用的临时节点(等宽字体,tab 也按实际渲染宽度算)。 */
    private static final Text METER = new Text();
    private static final double H_PAD = 20;

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
        hbar.setOrientation(Orientation.HORIZONTAL);
        // ListView(VirtualFlow) 自带一个隐藏的横向滚动条,给自绘的这条一个 id 以便区分/测试
        hbar.setId("diff-hbar");
        hbar.setMin(0);
        hbar.setVisible(false);
        hbar.setManaged(false);
        hbar.valueProperty().addListener((o, ov, nv) ->
                hOffset.set(Math.max(0, Math.min(nv.doubleValue(), hScrollMax))));
        // 视口宽度变化(拖分栏/改窗口)会让"放不放得下"跟着变
        list.widthProperty().addListener((o, ov, nv) -> updateHScroll());
        getChildren().addAll(list, hbar);
        VBox.setVgrow(list, Priority.ALWAYS);
        // 布局切换后立即重建(纯文件模式在 rebuild 内部仍按统一视图处理)
        sideBySide.addListener((o, ov, nv) -> rebuild());
    }

    public void clear() {
        currentFiles = List.of();
        plainTitle = null;
        plainLines = List.of();
        rows.clear();
        resetHScroll();
    }

    /** 展示一组文件的完整 diff。 */
    public void showFiles(List<DiffFile> files) {
        plainTitle = null;
        plainLines = List.of();
        currentFiles = files == null ? List.of() : files;
        rebuild();
        resetHScroll();   // 换了文件从最左边开始看
    }

    /** 展示纯文件内容(单列行号),用于提交的"文件视图"。 */
    public void showPlainFile(String title, List<String> lines) {
        plainTitle = title;
        plainLines = lines == null ? List.of() : lines;
        currentFiles = List.of();
        rebuild();
        resetHScroll();
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
        updateHScroll();
    }

    // ---------- 横向滚动 ----------

    /**
     * 重算横向可滚动量并刷新滚动条。
     *
     * 代码行常常比视口宽,原来只能被省略号/视口裁掉。这里按最宽一行算出需要多少像素,
     * 多出来的部分交给底部滚动条;单元格里的代码块按 {@link #hOffset} 左移(行号列不动,
     * 与编辑器一致),文件头行不参与(它有省略号 + 悬停提示)。
     */
    private void updateHScroll() {
        double viewport = list.getWidth() > 0 ? list.getWidth() : getWidth();
        if (viewport <= 0) {
            hScrollMax = 0;
            hbar.setVisible(false);
            hbar.setManaged(false);
            return;
        }
        double need = Math.max(viewport, neededWidth(viewport));
        hScrollMax = need - viewport;
        boolean show = hScrollMax > 0.5;
        hbar.setVisible(show);
        hbar.setManaged(show);
        // ScrollBar 惯例:max=内容总宽、visibleAmount=视口宽(拇指比例才对),value 上限 = max - visibleAmount
        hbar.setMax(need);
        hbar.setVisibleAmount(viewport);
        if (!show) {
            hbar.setValue(0);
        } else if (hbar.getValue() > hScrollMax) {
            hbar.setValue(hScrollMax);
        }
        hOffset.set(Math.max(0, Math.min(hbar.getValue(), hScrollMax)));
    }

    /** 换文件/清空时回到最左边。 */
    private void resetHScroll() {
        hbar.setValue(0);
        hOffset.set(0);
        updateHScroll();
    }

    /**
     * 布局后再算一次横向范围:重建时列表可能还没有宽度(首屏/切页签),
     * 只靠宽度监听会漏掉"宽度已定但内容后到"的顺序。
     */
    @Override protected void layoutChildren() {
        super.layoutChildren();
        updateHScroll();
    }

    /** 当前行集合里最宽一行需要的像素宽(并排视图按"每栏一半视口"折算)。 */
    private double neededWidth(double viewport) {
        boolean pair = sideBySide.get() && plainTitle == null;
        String widest = "";
        for (Row r : rows) {
            if (r.kind() == RowKind.FILE_HEADER) continue;
            if (r.kind() == RowKind.PAIR) {
                widest = longer(widest, r.left());
                widest = longer(widest, r.right());
            } else {
                widest = longer(widest, r.left());
            }
        }
        double codeW = textWidth(widest);
        if (pair) {
            // 两栏共用同一个偏移:按较宽一栏能看全来算总量
            double perColumn = NO_W + codeW + H_PAD;
            return Math.max(viewport, viewport / 2 + perColumn);
        }
        double numW = plainFile.get() ? NO_W : NO_W * 2;
        return numW + codeW + H_PAD;
    }

    /** 只按长度粗筛候选(等宽字体下够用),最终宽度只测一次。 */
    private static String longer(String best, DiffLine l) {
        String s = l == null ? "" : l.text();
        return s.length() > best.length() ? s : best;
    }

    private static double textWidth(String s) {
        METER.setFont(MONO);
        METER.setText(s == null ? "" : s);
        return METER.getLayoutBounds().getWidth();
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
        /** 代码块包在带裁剪的容器里:横向滚动时内容左移,不会画到行号列上,也不会溢出到隔壁栏。 */
        private final CodeArea codePane = codeArea(code);
        private final HBox lineBox = new HBox(oldNo, newNo, codePane);
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
         * 代码区的裁剪容器。
         *
         * 代码 Label 必须按**文本自身宽度**铺开(否则文本会被自己的宽度裁掉,横向滚动也看不到后半段),
         * 但它的宽度绝不能向上传递给 HBox/ListView —— 一旦参与宽度协商,整个 diff 列会被撑宽
         * (实测:ListView 被撑到 1466px)。所以这里自己算宽度:min/pref 恒为 0(不参与协商,
         * 靠 Hgrow 拿剩余空间),内部显式把 Label resize 成文本宽度,再用 clip 裁掉超出部分。
         */
        private class CodeArea extends Pane {
            private final Label content;

            CodeArea(Label content) {
                this.content = content;
                getChildren().add(content);
                setMinWidth(0);
                setPrefWidth(0);
                setMaxWidth(Double.MAX_VALUE);
                content.setWrapText(false);
                content.setMinWidth(0);
                content.setMaxWidth(Double.MAX_VALUE);
                content.translateXProperty().bind(hOffset.negate());
                Rectangle clip = new Rectangle();
                clip.widthProperty().bind(widthProperty());
                clip.heightProperty().bind(heightProperty());
                setClip(clip);
                HBox.setHgrow(this, Priority.ALWAYS);
            }

            @Override protected double computeMinWidth(double h) { return 0; }
            @Override protected double computePrefWidth(double h) { return 0; }

            @Override protected void layoutChildren() {
                double w = content.prefWidth(-1);                 // 文本宽度:保持不压缩
                double h = Math.min(getHeight(), content.prefHeight(w));
                content.resize(w, h);
                content.relocate(0, Math.max(0, (getHeight() - h) / 2)); // 纵向居中(原来是 HBox 干的)
            }
        }

        private CodeArea codeArea(Label content) {
            return new CodeArea(content);
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
            HBox h = new HBox(no, codeArea(code));
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
                setTooltip(null);
                return;
            }
            if (row.kind() == RowKind.FILE_HEADER) {
                DiffFile f = row.file();
                String stat = f.binary ? "(二进制)"
                        : (f.added == 0 && f.deleted == 0 ? "" : " +" + f.added + " -" + f.deleted);
                header.setText(f.displayPath() + (stat.isEmpty() ? "" : "   " + stat));
                box.getChildren().setAll(header);
                setGraphic(box);
                // 文件头行显示路径,窄栏会被省略号截断;悬停给全名(单元格复用,别的行必须清掉)
                setTooltip(Fx.tip(f.displayPath() + (stat.isEmpty() ? "" : "   " + stat)));
                return;
            }
            setTooltip(null);
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
