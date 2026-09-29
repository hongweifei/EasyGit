package org.easygit.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 当前仓库状态管理:多仓库管理(添加/移除/改名/分组)、切换当前仓库、发现 .git 根目录、变更通知。
 */
public final class RepoManager {
    private static final RepoManager INSTANCE = new RepoManager();

    private Path current;
    private final List<Consumer<Path>> listeners = new ArrayList<>();

    private RepoManager() {
        AppSettings s = AppSettings.get();
        // 优先恢复上次使用的仓库
        String cur = s.currentRepo();
        if (cur != null && !cur.isEmpty()) {
            Path p = findRepoRoot(Path.of(cur));
            if (p != null) current = p;
        }
        if (current == null) {
            for (AppSettings.RepoEntry e : s.repos()) {
                Path p = findRepoRoot(Path.of(e.path()));
                if (p != null) {
                    current = p;
                    break;
                }
            }
        }
        // 兼容更旧版本:recentRepos 兜底
        if (current == null) {
            for (String r : s.recentRepos()) {
                Path p = findRepoRoot(Path.of(r));
                if (p != null) {
                    current = p;
                    break;
                }
            }
        }
    }

    public static RepoManager get() { return INSTANCE; }

    public Path current() { return current; }

    public boolean hasRepo() { return current != null; }

    /** 打开/切换到目录所在仓库(自动加入管理列表,使用默认名)。 */
    public boolean open(Path dir) {
        Path root = findRepoRoot(dir);
        if (root == null) return false;
        if (isManaged(root)) {
            select(root);
        } else {
            addRepo(root, root.getFileName() == null ? root.toString() : root.getFileName().toString(), "");
        }
        return true;
    }

    /** 添加仓库到管理列表(或更新其名称/分组)并切换过去。 */
    public void addRepo(Path root, String name, String group) {
        List<AppSettings.RepoEntry> list = new ArrayList<>(AppSettings.get().repos());
        String key = root.toString();
        list.removeIf(e -> e.path().equals(key));
        list.add(0, new AppSettings.RepoEntry(key, name, group == null ? "" : group));
        AppSettings.get().setRepos(list);
        select(root);
    }

    /** 从管理列表移除;若是当前仓库则清空当前。总是通知监听者刷新列表。 */
    public void removeRepo(String path) {
        List<AppSettings.RepoEntry> list = new ArrayList<>(AppSettings.get().repos());
        list.removeIf(e -> e.path().equals(path));
        AppSettings.get().setRepos(list);
        if (current != null && current.toString().equals(path)) {
            current = null;
            AppSettings.get().setCurrentRepo("");
        }
        notifyListeners();
    }

    public void renameRepo(String path, String newName) {
        updateRepo(path, newName, null);
        notifyListeners();
    }

    public void setRepoGroup(String path, String group) {
        updateRepo(path, null, group);
        notifyListeners();
    }

    /** 重命名分组:组内所有仓库一起迁移到新组名;新组名与已有分组相同则相当于合并。 */
    public void renameGroup(String from, String to) {
        String target = to == null ? "" : to.strip();
        if (from == null || from.isBlank() || target.isBlank() || from.equals(target)) return;
        moveGroup(from, target);
    }

    /** 解散分组:组内所有仓库移到未分组(不删除仓库本身)。 */
    public void dissolveGroup(String name) {
        if (name == null || name.isBlank()) return;
        moveGroup(name, "");
    }

    /** 把 from 组的所有仓库整体移到 to 组(to 为空串表示未分组)。 */
    private void moveGroup(String from, String to) {
        List<AppSettings.RepoEntry> list = new ArrayList<>();
        boolean changed = false;
        for (AppSettings.RepoEntry e : AppSettings.get().repos()) {
            if (e.group().equals(from)) {
                list.add(new AppSettings.RepoEntry(e.path(), e.name(), to));
                changed = true;
            } else {
                list.add(e);
            }
        }
        if (changed) {
            AppSettings.get().setRepos(list);
            notifyListeners();
        }
    }

    private void updateRepo(String path, String newName, String newGroup) {
        List<AppSettings.RepoEntry> list = new ArrayList<>();
        for (AppSettings.RepoEntry e : AppSettings.get().repos()) {
            if (e.path().equals(path)) {
                list.add(new AppSettings.RepoEntry(e.path(),
                        newName != null ? newName : e.name(),
                        newGroup != null ? newGroup : e.group()));
            } else {
                list.add(e);
            }
        }
        AppSettings.get().setRepos(list);
    }

    public boolean isManaged(Path root) {
        String key = root.toString();
        return AppSettings.get().repos().stream().anyMatch(e -> e.path().equals(key));
    }

    /** 受管理的仓库列表。 */
    public List<AppSettings.RepoEntry> managedRepos() {
        return AppSettings.get().repos();
    }

    /** 所有已使用的分组名(排除未分组,按字母序)。 */
    public List<String> groups() {
        Set<String> gs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (AppSettings.RepoEntry e : AppSettings.get().repos()) {
            if (!e.group().isBlank()) gs.add(e.group());
        }
        return new ArrayList<>(gs);
    }

    private void select(Path root) {
        current = root;
        AppSettings.get().addRecentRepo(root);
        AppSettings.get().setCurrentRepo(root.toString());
        notifyListeners();
    }

    public void notifyListeners() {
        for (Consumer<Path> l : listeners) l.accept(current);
    }

    public void addListener(Consumer<Path> l) { listeners.add(l); }

    /** 从 dir 向上查找 .git(目录或 worktree 文件),找不到返回 null。 */
    public static Path findRepoRoot(Path dir) {
        if (dir == null) return null;
        Path p = dir.toAbsolutePath().normalize();
        while (p != null) {
            Path dotGit = p.resolve(".git");
            if (Files.isDirectory(dotGit) || Files.isRegularFile(dotGit)) return p;
            p = p.getParent();
        }
        return null;
    }

    public static boolean isGitRepo(Path dir) { return findRepoRoot(dir) != null; }
}
