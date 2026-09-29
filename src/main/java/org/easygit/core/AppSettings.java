package org.easygit.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用设置持久化(JSON),存于 ~/.easygit/settings.json。
 * 管理多个仓库(名称/分组)、当前仓库、最近打开、主题等。
 */
public final class AppSettings {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".easygit", "settings.json");

    /** 一个受管理的仓库条目。group 为空串表示未分组。 */
    public record RepoEntry(String path, String name, String group) {}

    private List<RepoEntry> repos = new ArrayList<>();
    private String currentRepo = "";
    private List<String> recentRepos = new ArrayList<>();
    private String theme = "light";
    private int maxCommits = 2000;
    /** 首次启动的默认窗口尺寸;取值以能在 1366x768 的笔记本屏上放得下为准。 */
    public static final double DEFAULT_WINDOW_W = 1120, DEFAULT_WINDOW_H = 700;
    private double windowW = DEFAULT_WINDOW_W, windowH = DEFAULT_WINDOW_H;
    private String gitPath = "";

    private static final AppSettings INSTANCE = new AppSettings();

    public static AppSettings get() { return INSTANCE; }

    private AppSettings() { load(); }

    public void load() {
        try {
            if (Files.exists(FILE)) {
                JSONObject o = new JSONObject(Files.readString(FILE, StandardCharsets.UTF_8));
                repos = new ArrayList<>();
                JSONArray ra = o.optJSONArray("repos");
                if (ra != null) {
                    for (int i = 0; i < ra.length(); i++) {
                        JSONObject e = ra.getJSONObject(i);
                        repos.add(new RepoEntry(e.optString("path"), e.optString("name"), e.optString("group")));
                    }
                }
                currentRepo = o.optString("currentRepo", "");
                recentRepos = new ArrayList<>();
                JSONArray arr = o.optJSONArray("recentRepos");
                if (arr != null) arr.forEach(s -> recentRepos.add((String) s));
                // 迁移:旧版本只有 recentRepos,自动升级为受管理仓库列表
                if (repos.isEmpty() && !recentRepos.isEmpty()) {
                    for (String p : recentRepos) {
                        Path path = Path.of(p);
                        String name = path.getFileName() == null ? p : path.getFileName().toString();
                        repos.add(new RepoEntry(p, name, ""));
                    }
                }
                theme = o.optString("theme", "light");
                maxCommits = o.optInt("maxCommits", 2000);
                windowW = o.optDouble("windowW", DEFAULT_WINDOW_W);
                windowH = o.optDouble("windowH", DEFAULT_WINDOW_H);
                gitPath = o.optString("gitPath", "");
            }
        } catch (Throwable t) {
            System.err.println("读取设置失败: " + t);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JSONObject o = new JSONObject();
            JSONArray ra = new JSONArray();
            for (RepoEntry e : repos) {
                ra.put(new JSONObject().put("path", e.path()).put("name", e.name()).put("group", e.group()));
            }
            o.put("repos", ra);
            o.put("currentRepo", currentRepo);
            o.put("recentRepos", recentRepos);
            o.put("theme", theme);
            o.put("maxCommits", maxCommits);
            o.put("windowW", windowW);
            o.put("windowH", windowH);
            o.put("gitPath", gitPath);
            Files.writeString(FILE, o.toString(2), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 含 NoClassDefFoundError 等 Error:设置保存失败不应影响退出流程
            System.err.println("保存设置失败: " + t);
        }
    }

    // ---------- 仓库列表 ----------

    public synchronized List<RepoEntry> repos() { return new ArrayList<>(repos); }

    public synchronized void setRepos(List<RepoEntry> list) {
        repos = new ArrayList<>(list);
        save();
    }

    public synchronized String currentRepo() { return currentRepo; }

    public synchronized void setCurrentRepo(String path) {
        this.currentRepo = path == null ? "" : path;
        save();
    }

    // ---------- 最近打开 ----------

    public synchronized void addRecentRepo(Path repo) {
        String s = repo.toAbsolutePath().normalize().toString();
        recentRepos.remove(s);
        recentRepos.add(0, s);
        while (recentRepos.size() > 15) recentRepos.remove(recentRepos.size() - 1);
        save();
    }

    public synchronized void removeRecentRepo(String repo) {
        recentRepos.remove(repo);
        save();
    }

    // ---------- git 可执行文件覆盖 ----------

    /** 手动指定的 git 路径(如 Git Bash 的 git.exe);空 = 自动探测。 */
    public String gitPath() { return gitPath; }

    public synchronized void setGitPath(String p) {
        this.gitPath = p == null ? "" : p.strip();
        save();
    }

    public List<String> recentRepos() { return new ArrayList<>(recentRepos); }
    public String theme() { return theme; }
    public void setTheme(String t) { this.theme = t; save(); }
    public int maxCommits() { return maxCommits; }
    public void setMaxCommits(int n) { this.maxCommits = Math.max(100, n); save(); }
    public double windowW() { return windowW; }
    public double windowH() { return windowH; }
    public void setWindowSize(double w, double h) { this.windowW = w; this.windowH = h; save(); }
}
