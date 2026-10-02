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

    /** 汇总信息(供状态对话框)。filesMeasured=false 表示文件列表没能在预算内读完。 */
    public record LfsInfo(boolean installed, String version, boolean hooked,
                          boolean repoUses, List<LfsFile> files, boolean filesMeasured, String envText) {}

    // ---------- 会话级缓存:避免每次刷新都 spawn lfs 进程 ----------

    private static volatile boolean resolvedInstalled;
    private static volatile boolean installedFlag;
    private static volatile String versionText = "";
    /** git-lfs --json 输出解析失败时置位(老版本不支持),本次会话不再尝试 */
    private static volatile boolean jsonUnsupported;
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> repoCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000;
    /**
     * 量不出文件数(超时)时的缓存时长。
     * 一次 ls-files 在启用 LFS 的仓库上要 1.6s 起(见 {@link #lsFilesBudget}),病态情况下更久,
     * 所以超时过的仓库别每分钟再撞一次 —— 界面显示"LFS"不带数字即可。
     */
    private static final long UNKNOWN_TTL_MS = 10 * 60_000;

    /**
     * {@code git lfs ls-files} 的时间预算。
     *
     * 实测:git-lfs 自身启动就要 ~1.6s(连只有 1 个文件的仓库也是),790MB 仓库冷启一遍 14.9s,
     * 机器负载高时更久。给它上限,超时按"数量未知"处理,绝不让界面一直等。
     */
    private static volatile java.time.Duration lsFilesBudget = java.time.Duration.ofSeconds(10);

    /** 仅供测试:调整 ls-files 的时间预算。 */
    static void setLsFilesBudget(java.time.Duration d) {
        if (d != null) lsFilesBudget = d;
    }

    /** 仅供测试:恢复默认时间预算。 */
    static void resetLsFilesBudget() {
        lsFilesBudget = java.time.Duration.ofSeconds(10);
    }

    private record CacheEntry(long stamp, boolean used, int count, long ttlMs) {}

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

    /** ls-files 的结果。measured=false 表示超时或失败 —— 这时"文件数"是**未知**,不是 0。 */
    public record LfsList(List<LfsFile> files, boolean measured) {}

    public static List<LfsFile> lsFiles(Path repo) {
        return lsFilesChecked(repo).files();
    }

    /**
     * 列 LFS 文件,带时间预算。
     *
     * 关键点:超时**不能**把 {@code jsonUnsupported} 置真。那个标记是"老版本 git-lfs 不支持 --json"
     * 用的,一次超时就永久关掉 JSON 路径,后续哪怕是好仓库也走文本回退,等于把偶发故障变成会话级退化。
     */
    public static LfsList lsFilesChecked(Path repo) {
        List<LfsFile> list = new ArrayList<>();
        // 优先 --json(结构化);输出可能是 {"files":[...]} 包装对象或裸数组,失败回退纯文本
        if (!jsonUnsupported) {
            GitProcess.GitResult r = GitProcess.in(repo).exec(lsFilesBudget, "lfs", "ls-files", "--json");
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
                        return new LfsList(list, true);
                    }
                } catch (Exception ex) {
                    jsonUnsupported = true; // 真的解析不了(老版本无 --json),本会话不再尝试
                }
            } else if (isTimeout(r)) {
                return new LfsList(List.of(), false); // 只是慢,别动 jsonUnsupported
            } else {
                jsonUnsupported = true;
            }
        }
        // 纯文本回退:格式类似 "<oid> <*/-> <path>"(path 可能含空格,取最后一段)
        GitProcess.GitResult r2 = GitProcess.in(repo).exec(lsFilesBudget, "lfs", "ls-files");
        if (!r2.ok()) return new LfsList(List.of(), false);
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
        return new LfsList(list, true);
    }

    /** GitProcess 超时(退出码 -1 且消息里带"超时")。 */
    private static boolean isTimeout(GitProcess.GitResult r) {
        return r.code() == -1 && r.message().contains("超时");
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
     * **count = -1 表示数量未知**(ls-files 超时):界面显示"LFS"而不是编造 0,
     * 并把这次结果缓存更久,避免每分钟都去撞一次慢调用。
     */
    public static int[] cachedRepoState(Path repo) {
        CacheEntry e = repoCache.get(repo.toString());
        long now = System.currentTimeMillis();
        if (e != null && now - e.stamp() < e.ttlMs()) {
            return new int[]{e.used() ? 1 : 0, e.count()};
        }
        // 这两步都是纯文件读取,不起进程 —— 不用 LFS 的仓库刷新成本为 0
        boolean used = repoUsesLfs(repo);
        int count = 0;
        long ttl = TTL_MS;
        if (used && installed()) {
            LfsList l = lsFilesChecked(repo);
            if (l.measured()) {
                count = l.files().size();
            } else {
                count = -1;
                ttl = UNKNOWN_TTL_MS;
            }
        }
        repoCache.put(repo.toString(), new CacheEntry(now, used, count, ttl));
        return new int[]{used ? 1 : 0, count};
    }

    public static LfsInfo gather(Path repo) {
        boolean inst = installed();
        List<LfsFile> files = List.of();
        boolean measured = true;
        String env = "";
        boolean uses = repo != null && repoUsesLfs(repo);
        if (inst) {
            if (uses) {
                LfsList l = lsFilesChecked(repo);
                files = l.files();
                measured = l.measured();
            }
            GitProcess.GitResult er = GitProcess.in(repo).exec("lfs", "env");
            env = er.ok() ? er.out() : er.message();
        }
        return new LfsInfo(inst, version(), hooked(), uses, files, measured, env);
    }
}
