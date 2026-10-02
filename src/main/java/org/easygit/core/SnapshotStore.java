package org.easygit.core;

import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.FileChange;
import org.easygit.core.model.RepoSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 仓库快照的落盘存储:每个仓库一个 JSON,放在 {@code ~/.easygit/snapshots/}。
 *
 * 三条硬性要求:
 * <ol>
 *   <li><b>绝不因为缓存而出问题</b>:任何异常(目录不可写、JSON 损坏、版本不符、路径对不上、
 *       文件太大、太久没用)都只是"没有缓存",不抛给调用方。</li>
 *   <li><b>写入要原子</b>:先写 {@code .tmp} 再 move,避免进程被杀时留下半份 JSON ——
 *       下次启动读到半份就是"解析失败"(虽然也只当没有缓存,但白丢一次)。</li>
 *   <li><b>磁盘占用有上限</b>:最多 {@link #MAX_FILES} 个仓库,超出按修改时间淘汰最旧的。</li>
 * </ol>
 */
public final class SnapshotStore {

    private SnapshotStore() {}

    /** 超过这个年龄的快照不再使用:它只是"先把界面填上"的缓存,太旧反而误导。 */
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    /** 最多保留几个仓库的快照(按修改时间淘汰)。 */
    public static final int MAX_FILES = 8;
    /** 单个快照文件上限,超了就当没有(防病态大的历史把磁盘/解析拖垮)。 */
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    /** 测试/探针用:覆盖快照根目录(null 表示按 user.home 解析)。 */
    private static volatile Path baseDir;

    static void setBaseDir(Path dir) {
        baseDir = dir;
    }

    /** 快照目录 {@code ~/.easygit/snapshots}(与 settings.json 同处,探针用 -Duser.home 隔离)。 */
    public static Path dir() {
        Path b = baseDir;
        return b != null ? b : Path.of(System.getProperty("user.home"), ".easygit", "snapshots");
    }

    /** 落盘一份快照(失败只当没缓存)。 */
    public static void save(Path repo, RepoSnapshot snap) {
        if (repo == null || snap == null) return;
        Path tmp = null;
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            String json = toJson(snap).toString();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return;
            Path file = dir.resolve(fileName(repo));
            tmp = dir.resolve(fileName(repo) + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);   // 跨卷等情况退一步
            }
            tmp = null;
            prune();
        } catch (Exception ignored) {
            // 缓存写不进去不值得打扰用户
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            }
        }
    }

    /** 读取快照;任何问题都返回 empty(调用方只需按"没有缓存"处理)。 */
    public static Optional<RepoSnapshot> load(Path repo) {
        if (repo == null) return Optional.empty();
        try {
            Path file = dir().resolve(fileName(repo));
            if (!Files.isRegularFile(file)) return Optional.empty();
            if (Files.size(file) > MAX_BYTES) return Optional.empty();
            RepoSnapshot snap = fromJson(new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
            if (snap == null) return Optional.empty();
            if (snap.version() != RepoSnapshot.CURRENT_VERSION) return Optional.empty();
            if (!repo.toString().equals(snap.repoPath())) return Optional.empty();   // 摘要撞车/文件被换过
            long age = System.currentTimeMillis() - snap.savedAt();
            if (age < 0 || age > MAX_AGE_MS) return Optional.empty();
            return Optional.of(snap);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 删除某个仓库的快照(仓库被移除时用)。 */
    public static void delete(Path repo) {
        if (repo == null) return;
        try {
            Files.deleteIfExists(dir().resolve(fileName(repo)));
        } catch (Exception ignored) {
        }
    }

    /** 文件名 = 仓库绝对路径的 SHA-256 前 32 个十六进制字符(路径里什么字符都有,摘要最省心)。 */
    private static String fileName(Path repo) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(repo.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb + ".json";
        } catch (Exception e) {
            return Integer.toHexString(repo.toString().hashCode()) + ".json";
        }
    }

    /** 只保留最近的 {@link #MAX_FILES} 个快照文件。 */
    private static void prune() {
        try (Stream<Path> s = Files.list(dir())) {
            List<Path> files = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".json")).toList());
            if (files.size() <= MAX_FILES) return;
            files.sort(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0L; }
            }));
            for (int i = 0; i < files.size() - MAX_FILES; i++) {
                try { Files.deleteIfExists(files.get(i)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- JSON ----------
    // 数组形式(字段顺序固定)比对象紧凑得多:2000 条提交时体积差近一半。
    // 字段顺序在 toJson/fromJson 两侧必须一一对应。

    static JSONObject toJson(RepoSnapshot s) {
        JSONObject root = new JSONObject();
        root.put("version", s.version());
        root.put("savedAt", s.savedAt());
        root.put("repoPath", s.repoPath());
        root.put("refs", s.refs() == null ? JSONObject.NULL : s.refs());
        RepoSnapshot.StatusSnap st = s.status();
        if (st != null) {
            JSONObject o = new JSONObject();
            o.put("oid", nz(st.oid()));
            o.put("branch", nz(st.branch()));
            o.put("upstream", nz(st.upstream()));
            o.put("detached", st.detached());
            o.put("ahead", st.ahead());
            o.put("behind", st.behind());
            JSONArray ch = new JSONArray();
            if (st.changes() != null) {
                for (FileChange f : st.changes()) {
                    // [path, origPath, indexState, wtState, unmerged, untracked]
                    ch.put(new JSONArray().put(f.path).put(f.origPath == null ? JSONObject.NULL : f.origPath)
                            .put(String.valueOf(f.indexState)).put(String.valueOf(f.wtState))
                            .put(f.unmerged).put(f.untracked));
                }
            }
            o.put("changes", ch);
            JSONArray br = new JSONArray();
            if (st.branches() != null) {
                for (BranchInfo b : st.branches()) {
                    // [kind, name, fullName, tip, current, upstream, track, time]
                    br.put(new JSONArray().put(b.kind.name()).put(b.name).put(b.fullName).put(b.tip)
                            .put(b.current).put(b.upstream == null ? JSONObject.NULL : b.upstream)
                            .put(b.track).put(b.time));
                }
            }
            o.put("branches", br);
            root.put("status", o);
        }
        RepoSnapshot.HistorySnap h = s.history();
        if (h != null) {
            JSONObject o = new JSONObject();
            o.put("refs", h.refs() == null ? JSONObject.NULL : h.refs());
            o.put("maxCommits", h.maxCommits());
            o.put("allBranches", h.allBranches());
            o.put("pathFilter", h.pathFilter() == null ? JSONObject.NULL : h.pathFilter());
            JSONArray log = new JSONArray();
            if (h.log() != null) {
                for (CommitEntry c : h.log()) {
                    // [id, abbr, parents(空格连接), author, email, time, refs(逗号连接), subject, body]
                    log.put(new JSONArray().put(c.id).put(c.abbr).put(String.join(" ", c.parents))
                            .put(c.author).put(c.email).put(c.time).put(String.join(",", c.refs))
                            .put(c.subject).put(c.body));
                }
            }
            o.put("log", log);
            JSONArray un = new JSONArray();
            if (h.unpushed() != null) for (String sha : h.unpushed()) un.put(sha);
            o.put("unpushed", un);
            root.put("history", o);
        }
        return root;
    }

    static RepoSnapshot fromJson(JSONObject root) {
        RepoSnapshot.StatusSnap status = null;
        JSONObject so = root.optJSONObject("status");
        if (so != null) {
            List<FileChange> changes = new ArrayList<>();
            JSONArray ch = so.optJSONArray("changes");
            if (ch != null) {
                for (int i = 0; i < ch.length(); i++) {
                    JSONArray a = ch.optJSONArray(i);
                    if (a == null || a.length() < 6) continue;
                    changes.add(new FileChange(a.optString(0),
                            a.isNull(1) ? null : a.optString(1),
                            ch(a.optString(2, " ")), ch(a.optString(3, " ")),
                            a.optBoolean(4), a.optBoolean(5)));
                }
            }
            List<BranchInfo> branches = new ArrayList<>();
            JSONArray br = so.optJSONArray("branches");
            if (br != null) {
                for (int i = 0; i < br.length(); i++) {
                    JSONArray a = br.optJSONArray(i);
                    if (a == null || a.length() < 8) continue;
                    BranchInfo.Kind kind;
                    try {
                        kind = BranchInfo.Kind.valueOf(a.optString(0));
                    } catch (Exception e) {
                        continue;
                    }
                    branches.add(new BranchInfo(kind, a.optString(1), a.optString(2), a.optString(3),
                            a.optBoolean(4), a.isNull(5) ? null : a.optString(5), a.optString(6), a.optLong(7)));
                }
            }
            status = new RepoSnapshot.StatusSnap(nz(so.optString("oid", "")), nz(so.optString("branch", "")),
                    so.isNull("upstream") ? "" : nz(so.optString("upstream", "")),
                    so.optBoolean("detached"), so.optInt("ahead"), so.optInt("behind"), changes, branches);
        }
        RepoSnapshot.HistorySnap history = null;
        JSONObject ho = root.optJSONObject("history");
        if (ho != null) {
            List<CommitEntry> log = new ArrayList<>();
            JSONArray la = ho.optJSONArray("log");
            if (la != null) {
                for (int i = 0; i < la.length(); i++) {
                    JSONArray a = la.optJSONArray(i);
                    if (a == null || a.length() < 9) continue;
                    log.add(new CommitEntry(a.optString(0), a.optString(1), a.optString(2), a.optString(3),
                            a.optString(4), a.optLong(5), a.optString(6), a.optString(7), a.optString(8)));
                }
            }
            Set<String> unpushed = new LinkedHashSet<>();
            JSONArray ua = ho.optJSONArray("unpushed");
            if (ua != null) for (int i = 0; i < ua.length(); i++) unpushed.add(ua.optString(i));
            history = new RepoSnapshot.HistorySnap(ho.isNull("refs") ? null : nz(ho.optString("refs", "")),
                    ho.optInt("maxCommits"), ho.optBoolean("allBranches"),
                    ho.isNull("pathFilter") ? null : nz(ho.optString("pathFilter", "")), log, unpushed);
        }
        if (status == null && history == null) return null;
        return new RepoSnapshot(root.optInt("version", -1), root.optLong("savedAt"),
                root.optString("repoPath", ""), root.isNull("refs") ? null : nz(root.optString("refs", "")),
                status, history);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static char ch(String s) { return s == null || s.isEmpty() ? ' ' : s.charAt(0); }
}
