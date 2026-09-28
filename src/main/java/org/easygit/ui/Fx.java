package org.easygit.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * FX 工具:后台任务执行、对话框。
 */
public final class Fx {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "easygit-bg");
        t.setDaemon(true);
        return t;
    });

    private static volatile Consumer<String> busyListener;
    private static volatile Consumer<String> messageListener;

    private Fx() {}

    public static void bindStatus(Consumer<String> busy, Consumer<String> message) {
        busyListener = busy;
        messageListener = message;
    }

    public static void later(Runnable r) { Platform.runLater(r); }

    /** 在后台执行 git 工作,完成后回 UI 线程;异常弹错误框。 */
    public static <T> void bg(String busyLabel, Supplier<T> work, Consumer<T> onDone) {
        busy(busyLabel);
        POOL.submit(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> {
                    busy(null);
                    onDone.accept(result);
                });
            } catch (Throwable ex) {
                Platform.runLater(() -> {
                    busy(null);
                    UiLog.line("✖ " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    error("操作失败", ex.getMessage(), null);
                });
            }
        });
    }

    public static void bg(String busyLabel, Runnable work) {
        bg(busyLabel, () -> { work.run(); return null; }, r -> {});
    }

    public static void busy(String label) {
        Consumer<String> l = busyListener;
        if (l != null) l.accept(label);
    }

    public static void status(String msg) {
        Consumer<String> l = messageListener;
        if (l != null) l.accept(msg);
    }

    // ---------- 对话框 ----------

    public static void error(String title, String message, String detail) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(message == null ? "" : message);
        if (detail != null && !detail.isBlank()) {
            TextArea ta = new TextArea(detail);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setMaxWidth(Double.MAX_VALUE);
            ta.setMaxHeight(Double.MAX_VALUE);
            GridPane g = new GridPane();
            g.setMaxWidth(Double.MAX_VALUE);
            g.add(new Label("详细信息:"), 0, 0);
            g.add(ta, 0, 1);
            GridPane.setVgrow(ta, Priority.ALWAYS);
            GridPane.setHgrow(ta, Priority.ALWAYS);
            a.getDialogPane().setContent(g);
        }
        a.showAndWait();
    }

    public static void info(String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }

    public static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        a.setTitle(title);
        a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }
}
