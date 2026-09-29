package org.easygit.ui;

import org.easygit.core.model.BlameLine;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Blame(行级追溯)视图。
 */
public class BlameView extends VBox {

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    /** Blame 段落色:与提交图泳道同套青碧领衔配色(见 DESIGN.md)。 */
    private static final Color[] PALETTE = {
            Color.web("#14b8a6"), Color.web("#58a6ff"), Color.web("#d29922"), Color.web("#f85149"),
            Color.web("#a371f7"), Color.web("#39c5cf"), Color.web("#ff7b72"), Color.web("#7ee787")
    };

    private final ListView<BlameLine> list = new ListView<>();
    private final String path;

    public BlameView(String path) {
        this.path = path;
        setSpacing(4);
        setPadding(new Insets(4));
        Label title = new Label("Blame: " + path);
        title.getStyleClass().add("h2");

        list.setFixedCellSize(22);
        list.setCellFactory(v -> new Cell());
        list.setPlaceholder(new Label("正在读取…"));
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(title, list);

        load();
    }

    private void load() {
        // Blame 页签会随仓库切换被关掉;守卫让旧仓库的结果不再回填这个已脱离场景的视图
        RepoGuard guard = RepoGuard.capture();
        Path repo = guard.repo();
        if (repo == null) return;
        Fx.bg("读取 blame…", guard, () -> org.easygit.core.NativeGit.blame(repo, path),
                lines -> list.getItems().setAll(lines));
    }

    public String path() { return path; }

    private class Cell extends ListCell<BlameLine> {
        @Override
        protected void updateItem(BlameLine l, boolean empty) {
            super.updateItem(l, empty);
            if (empty || l == null) {
                setGraphic(null);
                return;
            }
            Label no = new Label(String.valueOf(l.lineNo));
            no.getStyleClass().add("diff-no");
            no.setMinWidth(44);
            no.setAlignment(Pos.CENTER_RIGHT);

            Label sha = new Label(l.abbr);
            sha.getStyleClass().add("chip");
            Color c = PALETTE[Math.floorMod(l.sha.hashCode(), PALETTE.length)];
            sha.setStyle("-fx-background-color: " + toHex(c.deriveColor(0, 1, 1, 0.25)) + "; -fx-text-fill: -fg;");

            Label author = new Label(l.author);
            author.getStyleClass().add("blame-meta");
            author.setMinWidth(90);
            Label date = new Label(DTF.format(Instant.ofEpochSecond(l.time)));
            date.getStyleClass().add("blame-meta");
            date.setMinWidth(80);

            Label code = new Label(l.content);
            code.getStyleClass().add("mono");
            code.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(code, Priority.ALWAYS);

            HBox box = new HBox(8, no, sha, author, date, code);
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
        }

        private String toHex(Color c) {
            return String.format("#%02x%02x%02x",
                    (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255),
                    (int) Math.round(c.getBlue() * 255));
        }
    }
}
