package org.easygit;

import org.easygit.ui.Fx;
import org.easygit.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * EasyGit 应用入口。
 */
public class EasyGitApp extends Application {

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            Platform.runLater(() -> {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Fx.error("EasyGit - 未处理异常", String.valueOf(e), sw.toString());
            });
        });
        // 应用图标(窗口/任务栏)
        for (String s : new String[]{"icons/icon_16.png", "icons/icon_32.png",
                "icons/icon_48.png", "icons/icon_256.png"}) {
            var url = EasyGitApp.class.getResource("/" + s);
            if (url != null) stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
        }
        new MainWindow(stage).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
