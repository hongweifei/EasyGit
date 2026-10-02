package org.easygit.ui.panels;

import org.easygit.core.JGitService;
import org.easygit.core.model.BranchInfo;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;
import org.easygit.ui.dialogs.Dialogs;

/**
 * 左侧面板:当前分支状态 + 本地分支 / 远程分支 / 标签 树。
 */
public class BranchPanel extends VBox {

    /** 树节点数据。 */
    record TNode(String text, String kind, BranchInfo info) {
        static final String SECTION = "section";
        static final String LOCAL = "local";
        static final String REMOTE = "remote";
        static final String TAG = "tag";
        static final String HINT = "hint";
    }

    private final Label currentLabel = new Label();
    private final Label trackLabel = new Label();
    private final TreeView<TNode> tree = new TreeView<>();
    private final Runnable refreshAll;
    private Runnable newBranchAction = () -> {};
    private Runnable mergeAction = () -> {};

    /** 注入分支操作(由 MainWindow 提供弹窗与刷新)。 */
    public void setActions(Runnable newBranch, Runnable merge) {
        this.newBranchAction = newBranch;
        this.mergeAction = merge;
    }

    public BranchPanel(Runnable refreshAll) {
        this.refreshAll = refreshAll;
        setSpacing(6);
        setPadding(new javafx.geometry.Insets(6, 10, 4, 10));

        // 区头:标题 + 右侧操作(新建/合并归位于分支语境,不再占全局工具栏)
        Label sec = new Label("分支");
        sec.getStyleClass().add("section-title");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        Button newBranchBtn = new Button("新建分支");
        newBranchBtn.getStyleClass().add("ghost");
        newBranchBtn.setOnAction(e -> newBranchAction.run());
        Button mergeBtn = new Button("合并");
        mergeBtn.getStyleClass().add("ghost");
        mergeBtn.setOnAction(e -> mergeAction.run());
        HBox head = new HBox(4, sec, headSpacer, newBranchBtn, mergeBtn);
        head.setAlignment(Pos.CENTER_LEFT);

        // 当前分支:高亮 chip + 上游跟踪(次行小字)
        currentLabel.getStyleClass().addAll("chip", "chip-branch");
        trackLabel.getStyleClass().add("row-sub");
        HBox branchRow = new HBox(8, currentLabel, trackLabel);
        branchRow.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.setCellFactory(v -> new Cell());
        VBox.setVgrow(tree, Priority.ALWAYS);

        getChildren().addAll(head, branchRow, tree);
    }

    private static Path repo() { return org.easygit.core.RepoManager.get().current(); }

    /**
     * 「本仓库是否配置了远程」的缓存:**每个仓库各记一份**(上限 16,按最近使用淘汰)。
     *
     * 原来只有一个槽(键=当前仓库),切来切去必然每次都落空 —— 连续切换仓库时每个仓库都要
     * 起一个 {@code git remote -v}(约 100ms),连"只是路过、马上又切走"的仓库也要起。
     * 远程增删改后由 {@link #invalidateRemotesCache()} 作废。
     */
    private final java.util.LinkedHashMap<String, Boolean> remotesKnown =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) {
                    return size() > 16;
                }
            };

    /** 远程增删改后作废缓存(下一次刷新会重新问 git)。 */
    public void invalidateRemotesCache() {
        remotesKnown.clear();
    }

    public void refresh(List<BranchInfo> branches) {
        TreeItem<TNode> localRoot = node(new TNode("本地分支", TNode.SECTION, null));
        TreeItem<TNode> remoteRoot = node(new TNode("远程分支", TNode.SECTION, null));
        TreeItem<TNode> tagRoot = node(new TNode("标签", TNode.SECTION, null));
        TreeItem<TNode> root = node(new TNode("root", TNode.SECTION, null));

        String current = null;
        for (BranchInfo b : branches) {
            switch (b.kind) {
                case LOCAL -> {
                    if (b.current) {
                        current = b.name;
                        currentLabel.setText(b.name);
                        trackLabel.setText(b.upstream == null ? "(无上游)" :
                                b.upstream + (b.track.isEmpty() ? "" : "  " + b.track));
                    }
                    localRoot.getChildren().add(node(new TNode(b.name, TNode.LOCAL, b)));
                }
                case REMOTE -> remoteRoot.getChildren().add(node(new TNode(b.name, TNode.REMOTE, b)));
                case TAG -> tagRoot.getChildren().add(node(new TNode(b.name, TNode.TAG, b)));
            }
        }
        if (current == null) {
            currentLabel.setText("(空仓库 / 分离 HEAD)");
            trackLabel.setText("");
        }
        // 三个分组常驻显示;空分组给出提示(远程分组右键可管理)
        root.getChildren().add(localRoot);
        if (localRoot.getChildren().isEmpty()) {
            localRoot.getChildren().add(node(new TNode("暂无本地分支", TNode.HINT, null)));
        }
        root.getChildren().add(remoteRoot);
        if (remoteRoot.getChildren().isEmpty()) {
            // 「有没有配置远程」这个答案几乎不会变,却每次刷新都要起一个 git remote -v(约 100ms)。
            // 按仓库缓存:同一个仓库只问一次(连续切换时每个仓库也不会重复问)。
            Path repoNow = repo();
            String key = repoNow == null ? "" : repoNow.toString();
            Boolean known = remotesKnown.get(key);
            if (known == null) {
                known = repoNow != null && !org.easygit.core.NativeGit.remotes(repoNow).isEmpty();
                remotesKnown.put(key, known);
            }
            remoteRoot.getChildren().add(node(new TNode(known
                    ? "尚未抓取(可执行抓取或右键管理远程)"
                    : "未配置远程(右键管理远程)", TNode.HINT, null)));
        }
        root.getChildren().add(tagRoot);
        if (tagRoot.getChildren().isEmpty()) {
            tagRoot.getChildren().add(node(new TNode("暂无标签", TNode.HINT, null)));
        }
        tree.setRoot(root);
    }

    private static TreeItem<TNode> node(TNode n) {
        return new TreeItem<>(n);
    }

    // ---------- 单元格与右键菜单 ----------

    private class Cell extends TreeCell<TNode> {
        @Override
        protected void updateItem(TNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            HBox box = new HBox(new Label(n.text()));
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
            setText(null);

            switch (n.kind) {
                case TNode.HINT -> {
                    Label hint = new Label(n.text);
                    hint.getStyleClass().add("dim");
                    box.getChildren().setAll(hint);
                    setGraphic(box);
                    setContextMenu(null);
                    setOnMouseClicked(null);
                    setTooltip(null);
                }
                case TNode.SECTION -> {
                    getStyleClass().add("section-title");
                    if (n.text.equals("远程分支")) {
                        ContextMenu rm = new ContextMenu();
                        MenuItem mi = new MenuItem("管理远程…");
                        mi.setOnAction(e -> Dialogs.remoteManageDialog(
                                getScene() != null ? getScene().getWindow() : null,
                                org.easygit.core.RepoManager.get().current(), refreshAll));
                        rm.getItems().add(mi);
                        setContextMenu(rm);
                    } else {
                        setContextMenu(null);
                    }
                }
                case TNode.LOCAL -> {
                    if (n.info.current) {
                        getStyleClass().add("chip-branch");
                        box.getChildren().add(new Label("  ●"));
                    }
                    setTooltip(new Tooltip(n.info.upstream == null ? n.text() : n.text() + " → " + n.info.upstream));
                    setContextMenu(localMenu(n));
                    setOnMouseClicked(e -> {
                        if (e.getClickCount() == 2 && !n.info.current) checkout(n);
                    });
                }
                case TNode.REMOTE -> {
                    getStyleClass().add("chip-remote");
                    setContextMenu(remoteMenu(n));
                }
                case TNode.TAG -> {
                    getStyleClass().add("chip-tag");
                    setContextMenu(tagMenu(n));
                }
            }
        }
    }

    // ---------- 动作 ----------

    /** 更新分支:当前分支走 pull;其他分支从上游快进(fetch refspec),不切换工作区。 */
    private void updateBranch(BranchInfo b) {
        Path repo = repo();
        if (repo == null) return;
        Fx.bg("更新分支…", () -> {
            if (b.upstream == null || b.upstream.isBlank()) {
                throw new IllegalStateException("分支 " + b.name + " 没有设置上游,请先推送到远程并设置上游");
            }
            if (b.current) {
                var r = org.easygit.core.NativeGit.pull(repo);
                if (!r.ok()) throw new IllegalStateException(org.easygit.core.NativeGit.friendlyError(r.message()));
                return "已更新当前分支 " + b.name + " ← " + b.upstream;
            }
            int i = b.upstream.indexOf('/');
            // 上游不一定带远程前缀(比如把本地分支设成上游),直接 substring 会 StringIndexOutOfBounds
            if (i <= 0 || i == b.upstream.length() - 1) {
                throw new IllegalStateException("上游 " + b.upstream
                        + " 不是「远程/分支」形式,无法自动快进。请检出该分支后用「拉取」。");
            }
            String remote = b.upstream.substring(0, i);
            String remoteBranch = b.upstream.substring(i + 1);
            var r = org.easygit.core.NativeGit.fetchBranch(repo, remote, remoteBranch, b.name);
            if (!r.ok()) {
                throw new IllegalStateException("更新失败(通常是非快进更新,git 不允许直接覆盖):\n"
                        + org.easygit.core.NativeGit.friendlyError(r.message()));
            }
            return "已快进更新 " + b.name + " ← " + b.upstream;
        }, msg -> {
            Fx.status(msg);
            refreshAll.run();
        });
    }

    private void checkout(TNode n) {
        String name = n.info.name;
        Fx.bg("检出分支…", () -> {
            new JGitService(repo()).checkout(name, false, null);
            return true;
        }, r -> {
            Fx.status("已切换到 " + name);
            refreshAll.run();
        });
    }

    private ContextMenu localMenu(TNode n) {
        BranchInfo b = n.info;
        ContextMenu menu = new ContextMenu();
        menu.getItems().add(mi("更新此分支", () -> updateBranch(b)));
        if (!b.current) {
            menu.getItems().add(mi("检出", () -> checkout(n)));
        }
        menu.getItems().addAll(
                mi("合并到当前分支", () -> {
                    if (b.current) { Fx.info("无法合并", "不能把当前分支合并到自己。"); return; }
                    Fx.bg("合并…", () -> new JGitService(repo()).merge(b.name), outcome -> {
                        UiLog.op("git merge " + b.name, "", outcome.message()
                                + (outcome.conflicts().isEmpty() ? "" :
                                "  冲突文件: " + String.join(", ", outcome.conflicts())));
                        Fx.status(outcome.message());
                        refreshAll.run();
                    });
                }),
                mi("基于此分支创建新分支…", () -> {
                    String name = Dialogs.newBranch("");
                    if (name != null) {
                        Fx.bg("创建分支…", () -> {
                            new JGitService(repo()).createBranch(name, b.fullName);
                            return true;
                        }, r -> {
                            Fx.status("已创建分支 " + name);
                            refreshAll.run();
                        });
                    }
                }),
                mi("推送到指定远程…", () -> Dialogs.pushDialog(b.name, refreshAll, null, null)),
                mi("重命名…", () -> {
                    String newName = Dialogs.askText("重命名分支", "新分支名:", b.name);
                    if (newName != null && !newName.isBlank() && !newName.equals(b.name)) {
                        Fx.bg("重命名分支…", () -> {
                            new JGitService(repo()).renameBranch(b.name, newName.strip());
                            return true;
                        }, r -> {
                            Fx.status("已重命名 " + b.name + " → " + newName.strip());
                            refreshAll.run();
                        });
                    }
                }),
                mi("删除分支", () -> {
                    if (!Fx.confirm("删除分支", "确定删除分支 " + b.name + "?")) return;
                    Fx.bg("删除分支…", () -> {
                        try {
                            new JGitService(repo()).deleteBranch(b.name, false);
                            return "已删除 " + b.name;
                        } catch (Exception ex) {
                            if (Fx.confirm("强制删除", "分支未合并,是否强制删除?\n" + ex.getMessage())) {
                                new JGitService(repo()).deleteBranch(b.name, true);
                                return "已强制删除 " + b.name;
                            }
                            return null;
                        }
                    }, msg -> {
                        if (msg != null) {
                            Fx.status(msg);
                            refreshAll.run();
                        }
                    });
                })
        );
        return menu;
    }

    private ContextMenu remoteMenu(TNode n) {
        BranchInfo b = n.info;
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                mi("检出到新本地分支…", () -> {
                    String localName = b.name.contains("/")
                            ? b.name.substring(b.name.indexOf('/') + 1) : b.name;
                    String name = Dialogs.askText("检出到新本地分支", "本地分支名:", localName);
                    if (name != null && !name.isBlank()) {
                        Fx.bg("检出…", () -> {
                            new JGitService(repo()).checkout(name.strip(), true, b.fullName);
                            return true;
                        }, r -> {
                            Fx.status("已检出 " + name.strip());
                            refreshAll.run();
                        });
                    }
                }),
                mi("合并到当前分支", () -> {
                    Fx.bg("合并…", () -> new JGitService(repo()).merge(b.fullName), outcome -> {
                        UiLog.op("git merge " + b.fullName, "", outcome.message()
                                + (outcome.conflicts().isEmpty() ? "" :
                                "  冲突文件: " + String.join(", ", outcome.conflicts())));
                        Fx.status(outcome.message());
                        refreshAll.run();
                    });
                }),
                mi("删除远程分支", () -> {
                    String remote = b.name.contains("/") ? b.name.substring(0, b.name.indexOf('/')) : "origin";
                    String branch = b.name.contains("/") ? b.name.substring(b.name.indexOf('/') + 1) : b.name;
                    if (!Fx.confirm("删除远程分支", "确定删除远程分支 " + b.name + "?")) return;
                    Fx.bg("删除远程分支…", () -> org.easygit.core.NativeGit.deleteRemoteBranch(repo(), remote, branch),
                            r -> {
                                if (r.ok()) Fx.status("已删除远程分支 " + b.name);
                                else Fx.error("删除失败", r.message(), null);
                                refreshAll.run();
                            });
                })
        );
        return menu;
    }

    private ContextMenu tagMenu(TNode n) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                mi("推送此标签到远程…", () -> {
                    String remote = Dialogs.chooseRemote(repo());
                    if (remote == null) return;
                    Fx.bg("推送标签…", () -> org.easygit.core.NativeGit.pushTag(repo(), remote, n.info.name),
                            r -> {
                                UiLog.op("git push " + remote + " " + n.info.name + (r.ok() ? " ✓" : " ✖"),
                                        r.out(), r.err());
                                if (r.ok()) Fx.status("已推送标签 " + n.info.name + " → " + remote);
                                else Fx.error("推送失败", r.message(), null);
                            });
                }),
                mi("推送所有标签", () -> {
                    String remote = Dialogs.chooseRemote(repo());
                    if (remote == null) return;
                    Fx.bg("推送所有标签…", () -> org.easygit.core.NativeGit.pushAllTags(repo(), remote),
                            r -> {
                                UiLog.op("git push " + remote + " --tags" + (r.ok() ? " ✓" : " ✖"),
                                        r.out(), r.err());
                                if (r.ok()) Fx.status("已推送所有标签 → " + remote);
                                else Fx.error("推送失败", r.message(), null);
                            });
                }),
                mi("复制名称", () -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(n.info.name);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                }),
                mi("删除标签", () -> {
                    if (!Fx.confirm("删除标签", "确定删除标签 " + n.info.name + "?")) return;
                    Fx.bg("删除标签…", () -> org.easygit.core.NativeGit.deleteTag(repo(), n.info.name),
                            r -> {
                                if (r.ok()) Fx.status("已删除标签 " + n.info.name);
                                else Fx.error("删除失败", r.message(), null);
                                refreshAll.run();
                            });
                }),
                mi("删除远程标签…", () -> {
                    String remote = Dialogs.chooseRemote(repo());
                    if (remote == null) return;
                    if (!Fx.confirm("删除远程标签", "确定删除远程 " + remote + " 上的标签 " + n.info.name + "?")) return;
                    Fx.bg("删除远程标签…", () -> org.easygit.core.NativeGit.deleteRemoteTag(repo(), remote, n.info.name),
                            r -> {
                                UiLog.op("git push " + remote + " :refs/tags/" + n.info.name
                                                + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
                                if (r.ok()) Fx.status("已删除远程标签 " + n.info.name);
                                else Fx.error("删除失败", r.message(), null);
                            });
                })
        );
        return menu;
    }

    private static MenuItem mi(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }
}
