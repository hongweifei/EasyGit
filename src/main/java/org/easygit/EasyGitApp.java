package org.easygit;

import org.easygit.ui.Fx;
import org.easygit.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EasyGit 应用入口。
 */
public class EasyGitApp extends Application {

    /** 同类异常 5 分钟内只提示一次:周期性刷新里反复抛异常时,别变成"框关了又弹"。 */
    private static final Map<String, Long> REPORTED = new ConcurrentHashMap<>();
    /** 未捕获异常的落盘位置(用户报"界面报错卡住"时靠它定位)。 */
    private static final Path ERROR_LOG = Path.of(System.getProperty("user.home"), ".easygit", "error.log");

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String detail = sw.toString();
            appendErrorLog(t, detail);
            // 非模态提示:模态框会把主窗口锁住,一旦异常是周期性的,界面就彻底点不动了
            if (shouldReport(e)) {
                Fx.errorAsync("EasyGit - 未处理异常", String.valueOf(e),
                        "已追加到 " + ERROR_LOG + "\n\n" + detail);
            }
        });
        // 应用图标(窗口/任务栏)
        for (String s : new String[]{"icons/icon_16.png", "icons/icon_32.png",
                "icons/icon_48.png", "icons/icon_256.png"}) {
            var url = EasyGitApp.class.getResource("/" + s);
            if (url != null) stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
        }
        new MainWindow(stage).show();
    }

    /** 是否该弹提示:同一个异常(按 toString 归类)5 分钟内只弹一次。 */
    private static boolean shouldReport(Throwable e) {
        long now = System.currentTimeMillis();
        Long last = REPORTED.put(String.valueOf(e), now);
        return last == null || now - last > 5 * 60_000;
    }

    /** 追加到 ~/.easygit/error.log(UTF-8)。写日志本身出错不能再抛,否则递归。 */
    private static void appendErrorLog(Thread t, String detail) {
        try {
            Files.createDirectories(ERROR_LOG.getParent());
            String head = "\n===== " + java.time.LocalDateTime.now() + "  thread=" + t.getName() + " =====\n";
            Files.writeString(ERROR_LOG, head + detail, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
