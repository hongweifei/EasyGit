package org.easygit.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git LFS 支持:安装检测、fetch/pull/prune、跟踪规则(.gitattributes)、文件列表。
 */
public final class LfsService {
    private LfsService() {}

    /** 一个 LFS 跟踪的文件。downloaded=false 表示本地只有指针文件。 */
    public record LfsFile(String path, String oid, long size, boolean downloaded) {}

    /** 汇总信息(供状态对话框)。 */
    public record LfsInfo(boolean installed, String version, boolean hooked,
                          boolean repoUses, List<LfsFile> files, String envText) {}

    // ---------- 会话级缓存:避免每次刷新都 spawn lfs 进程 ----------

    private static volatile boolean resolvedInstalled;
    private static volatile boolean installedFlag;
    private static volatile String versionText = "";
    /** git-lfs --json 输出解析失败时置位(老版本不支持),本次会话不再尝试 */
    private static volatile boolean jsonUnsupported;
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> repoCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000;

    private record CacheEntry(long stamp, boolean used, int count) {}

    /** LFS 菜单操作(安装/拉取/抓取/规则变更/prune)后调用,强制下次刷新重算。 */
    public static synchronized void resetCaches() {
        resolvedInstalled = false;
        versionText = "";
        jsonUnsupported = false;
        repoCache.clear();
    }

    // ---------- 安装状态 ----------

    public static boolean installed() {
        if (!resolvedInstalled) {
            GitProcess.GitResult r = GitProcess.global("lfs", "version");
            installedFlag = r.ok() && r.out().toLowerCase().contains("git-lfs");
            versionText = r.ok() ? r.out().strip() : "";
            resolvedInstalled = true;
        }
        return installedFlag;
    }

    public static String version() {
        installed();
        return versionText;
    }

    /** LFS 过滤器是否已接入 git 配置(global/system/local)。 */
    public static boolean hooked() {
        GitProcess.GitResult r = GitProcess.global("config", "--get", "filter.lfs.smudge");
        return r.ok() && !r.out().isBlank();
    }

    public static GitProcess.GitResult install() {
        return GitProcess.global("lfs", "install");
    }

    // ---------- 对象操作 ----------

    /** LFS 要搬二进制对象,给比普通网络操作更长的超时(15 分钟)。 */
    private static final java.time.Duration LFS_TIMEOUT = java.time.Duration.ofMinutes(15);

    public static GitProcess.GitResult fetch(Path repo) {
        return GitProcess.in(repo).execNet(LFS_TIMEOUT, "lfs", "fetch");
    }

    public static GitProcess.GitResult pull(Path repo) {
        return GitProcess.in(repo).execNet(LFS_TIMEOUT, "lfs", "pull");
    }

    public static GitProcess.GitResult prune(Path repo) {
        return GitProcess.in(repo).execNet(LFS_TIMEOUT, "lfs", "prune");
    }

    // ---------- 仓库判断与文件列表 ----------

    /** 仓库是否使用 LFS(根 .gitattributes 含 filter=lfs)。 */
    public static boolean repoUsesLfs(Path repo) {
        try {
            Path ga = repo.resolve(".gitattributes");
            if (!Files.isRegularFile(ga)) return false;
            for (String line : Files.readAllLines(ga)) {
                if (line.contains("filter=lfs")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static List<LfsFile> lsFiles(Path repo) {
        List<LfsFile> list = new ArrayList<>();
        // 优先 --json(结构化);输出可能是 {"files":[...]} 包装对象或裸数组,失败回退纯文本
        if (!jsonUnsupported) {
            GitProcess.GitResult r = GitProcess.in(repo).exec("lfs", "ls-files", "--json");
            if (r.ok()) {
                String out = r.out().strip();
                try {
                    JSONArray arr;
                    if (out.startsWith("{")) {
                        arr = new JSONObject(out).optJSONArray("files");
                    } else if (out.startsWith("[")) {
                        arr = new JSONArray(out);
                    } else {
                        arr = null;
                    }
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            list.add(new LfsFile(o.optString("name"), o.optString("oid"),
                                    o.optLong("size", -1), o.optBoolean("downloaded", true)));
                        }
                        return list;
                    }
                } catch (Exception ex) {
                    jsonUnsupported = true; // 解析失败(老版本无 --json),本会话不再尝试
                }
            } else {
                jsonUnsupported = true;
            }
        }
        // 纯文本回退:格式类似 "<oid> <*/-> <path>"(path 可能含空格,取最后一段)
        GitProcess.GitResult r2 = GitProcess.in(repo).exec("lfs", "ls-files");
        if (!r2.ok()) return list;
        for (String line : r2.out().split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            String[] t = s.split("\\s+");
            if (t.length < 2) continue;
            boolean downloaded = false;
            String oid = "";
            for (String tok : t) {
                if (tok.equals("*")) downloaded = true;
                if (tok.matches("[0-9a-f]{8,64}")) oid = tok;
            }
            list.add(new LfsFile(t[t.length - 1], oid, -1, downloaded));
        }
        return list;
    }

    // ---------- 跟踪规则 ----------

    /** 当前 LFS 跟踪规则(来自 .gitattributes 中含 filter=lfs 的行,取首段模式)。 */
    public static List<String> trackPatterns(Path repo) {
        List<String> out = new ArrayList<>();
        try {
            Path ga = repo.resolve(".gitattributes");
            if (!Files.isRegularFile(ga)) return out;
            for (String line : Files.readAllLines(ga)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.contains("filter=lfs")) {
                    out.add(s.split("\\s+")[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static GitProcess.GitResult track(Path repo, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return new GitProcess.GitResult(-1, "", "模式不能为空");
        }
        return GitProcess.in(repo).exec("lfs", "track", pattern.strip());
    }

    /** 从 .gitattributes 移除一条跟踪规则(直接编辑文件并暂存)。 */
    public static GitProcess.GitResult untrack(Path repo, String pattern) {
        try {
            Path ga = repo.resolve(".gitattributes");
            if (!Files.isRegularFile(ga)) return new GitProcess.GitResult(-1, "", "没有 .gitattributes");
            List<String> lines = Files.readAllLines(ga);
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String line : lines) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#") && s.contains("filter=lfs")
                        && s.split("\\s+")[0].equals(pattern)) {
                    changed = true;
                    continue;
                }
                kept.add(line);
            }
            if (!changed) return new GitProcess.GitResult(-1, "", "未找到该规则: " + pattern);
            Files.write(ga, kept);
            return GitProcess.in(repo).exec("add", ".gitattributes");
        } catch (Exception e) {
            return new GitProcess.GitResult(-1, "", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ---------- 汇总 ----------

    /**
     * 供周期刷新使用的缓存版仓库状态,返回 [used(0/1), count]。
     * 60 秒内重复调用直接命中缓存,不再 spawn lfs 进程;
     * LFS 菜单操作后由 resetCaches() 强制重算。
     */
    public static int[] cachedRepoState(Path repo) {
        CacheEntry e = repoCache.get(repo.toString());
        long now = System.currentTimeMillis();
        if (e != null && now - e.stamp() < TTL_MS) {
            return new int[]{e.used() ? 1 : 0, e.count()};
        }
        boolean used = repoUsesLfs(repo);
        int count = 0;
        if (used && installed()) {
            try {
                count = lsFiles(repo).size();
            } catch (Exception ignored) {
            }
        }
        repoCache.put(repo.toString(), new CacheEntry(now, used, count));
        return new int[]{used ? 1 : 0, count};
    }

    public static LfsInfo gather(Path repo) {
        boolean inst = installed();
        List<LfsFile> files = List.of();
        String env = "";
        boolean uses = repo != null && repoUsesLfs(repo);
        if (inst) {
            if (uses) {
                try {
                    files = lsFiles(repo);
                } catch (Exception ignored) {
                }
            }
            GitProcess.GitResult er = GitProcess.in(repo).exec("lfs", "env");
            env = er.ok() ? er.out() : er.message();
        }
        return new LfsInfo(inst, version(), hooked(), uses, files, env);
    }
}
