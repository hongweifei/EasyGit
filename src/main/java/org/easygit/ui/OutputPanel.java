package org.easygit.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 底部输出面板:展示 git 操作(commit/push/pull/reset…)的命令输出。
 * 默认隐藏;出现错误或用户点工具栏「输出」时展开。
 */
public class OutputPanel extends VBox {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea console = new TextArea();

    public OutputPanel() {
        setSpacing(2);
        setPadding(new Insets(2, 6, 2, 6));
        setStyle("-fx-background-color: -panel2; -fx-border-color: -border transparent transparent transparent;");
        setMaxHeight(220);

        console.setEditable(false);
        console.getStyleClass().addAll("mono", "output-console");
        console.setPrefHeight(170);
        console.setWrapText(false);

        Label title = new Label("输出");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button clear = new Button("清空");
        clear.setOnAction(e -> console.clear());
        Button hide = new Button("收起 ▼");
        hide.setOnAction(e -> hide());
        HBox header = new HBox(8, title, spacer, clear, hide);
        header.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(header, console);
        VBox.setVgrow(console, Priority.ALWAYS);

        setVisible(false);
        setManaged(false);

        UiLog.bind(this::dispatch);
    }

    private void dispatch(String raw) {
        Platform.runLater(() -> {
            if (raw.startsWith("\u0001")) {
                String[] parts = raw.split("\u0001", -1);
                appendOp(parts.length > 1 ? parts[1] : "git",
                        parts.length > 2 ? parts[2] : "",
                        parts.length > 3 ? parts[3] : "");
            } else {
                appendLine(raw);
            }
        });
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
    }

    public void toggle() {
        boolean show = !isVisible();
        setVisible(show);
        setManaged(show);
    }

    private void appendLine(String line) {
        boolean isError = line.startsWith("✖");
        if (isError) {
            setVisible(true);
            setManaged(true);
        }
        console.appendText("[" + TF.format(LocalTime.now()) + "] " + line + "\n");
        scrollToEnd();
    }

    private void appendOp(String op, String out, String err) {
        // git 操作的命令输出总是弹出面板
        setVisible(true);
        setManaged(true);
        StringBuilder sb = new StringBuilder();
        sb.append("── [").append(TF.format(LocalTime.now())).append("] ").append(op).append('\n');
        String o = clean(out);
        String e = clean(err);
        // 两个流都有内容时才区分标注;git 的正常状态输出大多走 stderr,单流时不加前缀
        if (!o.isEmpty() && !e.isEmpty()) {
            sb.append("[stdout] ").append(o).append('\n');
            sb.append("[stderr] ").append(e).append('\n');
        } else if (!o.isEmpty()) {
            sb.append(o).append('\n');
        } else if (!e.isEmpty()) {
            sb.append(e).append('\n');
        }
        console.appendText(sb.toString());
        scrollToEnd();
    }

    /** 剥离 ANSI 颜色/控制序列,规范化 \r 进度输出,去除行尾空白。 */
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile(
            "\u001B\\[[0-9;]*[A-Za-z]"
                    + "|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"
                    + "|\u001B[=>]");

    private static String clean(String s) {
        if (s == null || s.isBlank()) return "";
        String x = ANSI.matcher(s).replaceAll("");
        x = x.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : x.split("\n", -1)) {
            String t = line.stripTrailing();
            if (!t.isBlank()) sb.append(t).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private void scrollToEnd() {
        console.selectEnd();
        console.deselect();
        console.setScrollTop(Double.MAX_VALUE);
    }
}
