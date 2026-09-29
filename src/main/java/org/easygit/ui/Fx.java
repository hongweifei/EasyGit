package org.easygit.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

    /** 在途任务(带仓库守卫的才可被取消)。 */
    private static final List<BgTask> IN_FLIGHT = new CopyOnWriteArrayList<>();

    /**
     * 一个后台任务。busy 计数必须"恰好释放一次":
     * 排队的任务被取消时 run() 根本不会执行,由取消方代为释放;
     * 已在执行的任务被取消时,自己那条 finally 释放。两边都调,靠 CAS 保证只减一次。
     */
    private static final class BgTask {
        final RepoGuard guard;
        final java.util.concurrent.atomic.AtomicBoolean busyReleased =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile Future<?> future;
        BgTask(RepoGuard guard) { this.guard = guard; }
        void releaseBusy() { if (busyReleased.compareAndSet(false, true)) busyEnd(); }
    }

    /** 忙碌指示的并发深度:连续切换仓库时多个刷新会重叠,谁先结束都不能提前熄灯。 */
    private static final AtomicInteger BUSY_DEPTH = new AtomicInteger();

    // 诊断计数:切换仓库的优化是否真的生效,靠这三个数看(探针/问题定位用)
    private static final AtomicInteger SCHEDULED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static final AtomicInteger CANCELLED = new AtomicInteger();

    private static volatile Consumer<String> busyListener;
    private static volatile Consumer<String> messageListener;

    private Fx() {}

    /** 统一的面板头:标题 + 可选右侧操作。所有面板共用,保证节奏一致。 */
    public static HBox panelHead(String title, Node... right) {
        Label t = new Label(title);
        t.getStyleClass().add("panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox h = new HBox(8, t, spacer);
        h.getChildren().addAll(right);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("panel-head");
        return h;
    }

    public static void bindStatus(Consumer<String> busy, Consumer<String> message) {
        busyListener = busy;
        messageListener = message;
    }

    public static void later(Runnable r) { Platform.runLater(r); }

    /** 在后台执行 git 工作,完成后回 UI 线程;异常弹错误框。 */
    public static <T> void bg(String busyLabel, Supplier<T> work, Consumer<T> onDone) {
        submit(busyLabel, null, work, onDone, () -> true);
    }

    public static void bg(String busyLabel, Runnable work) {
        bg(busyLabel, () -> { work.run(); return null; }, r -> {});
    }

    /**
     * 仓库相关的只读刷新任务:仓库一旦切换,任务不再启动、结果直接丢弃、错误也不再弹框。
     *
     * 只用于"读数据回填界面"的任务(状态/历史/差异/blame)。会改仓库状态的操作
     * (提交、检出、推送……)不要用这个重载:它们必须跑完并如实上报结果。
     */
    public static <T> void bg(String busyLabel, RepoGuard guard, Supplier<T> work, Consumer<T> onDone) {
        if (guard.stale()) { // 排队前先拦:过期任务连线程池都不进
            SKIPPED.incrementAndGet();
            return;
        }
        submit(busyLabel, guard,
                () -> guard.stale() ? null : work.get(), // 取数前再拦:别为旧仓库白起 git 子进程
                result -> { if (!guard.stale()) onDone.accept(result); },
                () -> !guard.stale()); // 回投前拦 + 过期时不弹无关错误框
    }

    /**
     * 提交一个后台任务。
     *
     * @param guard 非空表示这是可取消的仓库刷新任务(见 {@link #dropStaleTasks()})
     * @param valid 结果是否仍然有效;为假时结果与异常都不再打扰用户
     */
    private static <T> void submit(String busyLabel, RepoGuard guard, Supplier<T> work,
                                   Consumer<T> onDone, BooleanSupplier valid) {
        BgTask task = new BgTask(guard);
        busyStart(busyLabel);
        SCHEDULED.incrementAndGet();
        IN_FLIGHT.add(task);
        task.future = POOL.submit(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> {
                    task.releaseBusy(); // 先熄灯再渲染结果,和原来的视觉顺序一致
                    if (valid.getAsBoolean()) onDone.accept(result);
                });
            } catch (Throwable ex) {
                Platform.runLater(() -> {
                    task.releaseBusy();
                    UiLog.line("✖ " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    if (valid.getAsBoolean()) error("操作失败", ex.getMessage(), null);
                });
            } finally {
                // 取消任务靠中断线程实现(GitProcess 收到中断会 destroyForcibly 掉 git 子进程)。
                // 必须在这里清掉中断标记:线程池的线程会被复用,残留的中断标记会让
                // 该线程之后的每个 git 调用一进去就抛 InterruptedException,全部报"已被取消"。
                Thread.interrupted();
                // 兜底释放(幂等):被中断在半路时也要保证忙碌计数归零,否则转圈永远不停
                Platform.runLater(task::releaseBusy);
            }
        });
        pruneInFlight();
    }

    /**
     * 仓库已切换:中断旧仓库仍在排队/执行的只读刷新任务。
     * 一次切换会派生状态 + 历史两类刷新,连续切换时若不收回,线程池会被旧任务占满。
     * 由 UI 线程在刷新入口处调用(主窗口每次统一刷新时都会调一次)。
     *
     * @return 本次被取消的任务数
     */
    public static int dropStaleTasks() {
        int n = 0;
        for (BgTask t : IN_FLIGHT) {
            Future<?> f = t.future;
            if (t.guard == null || f == null || f.isDone() || !t.guard.stale()) continue;
            if (f.cancel(true)) {
                n++;
                // 排队中的任务被取消后 run() 不会执行,忙碌计数要由这里代还(释放是幂等的)
                Platform.runLater(t::releaseBusy);
            }
        }
        if (n > 0) CANCELLED.addAndGet(n);
        pruneInFlight();
        return n;
    }

    private static void pruneInFlight() {
        IN_FLIGHT.removeIf(t -> t.future != null && (t.future.isDone() || t.future.isCancelled()));
    }

    /** 已提交的后台任务数(诊断)。 */
    public static int scheduledTasks() { return SCHEDULED.get(); }

    /** 因仓库已切换而在排队/取数前被跳过的任务数(诊断)。 */
    public static int skippedTasks() { return SKIPPED.get(); }

    /** 因仓库已切换而被中断的旧仓库任务数(诊断)。 */
    public static int cancelledTasks() { return CANCELLED.get(); }

    private static void busyStart(String label) {
        BUSY_DEPTH.incrementAndGet();
        busy(label);
    }

    private static void busyEnd() {
        if (BUSY_DEPTH.decrementAndGet() <= 0) {
            BUSY_DEPTH.set(0);
            busy(null);
        }
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

    /** 给对话框的独立窗口挂上应用图标并应用主题(在 show 之前调用)。
    对话框是独立场景,不带主窗口的样式表;不挂的话暗色模式下弹窗是裸 Modena,
    令牌 lookup 也全部失效。 */
    public static void icon(javafx.scene.control.Dialog<?> d) {
        var pane = d.getDialogPane();
        String css = Fx.class.getResource("/css/theme.css").toExternalForm();
        if (!pane.getStylesheets().contains(css)) pane.getStylesheets().add(css);
        if ("dark".equals(org.easygit.core.AppSettings.get().theme())
                && !pane.getStyleClass().contains("dark")) {
            pane.getStyleClass().add("dark");
        }
        pane.sceneProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.getWindow() instanceof javafx.stage.Stage st && st.getIcons().isEmpty()) {
                for (String s : new String[]{"icons/icon_16.png", "icons/icon_32.png", "icons/icon_48.png"}) {
                    var url = Fx.class.getResource("/" + s);
                    if (url != null) st.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
                }
            }
        });
    }

    public static void error(String title, String message, String detail) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        icon(a);
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
        icon(a);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }

    public static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        icon(a);
        a.setTitle(title);
        a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }
}
