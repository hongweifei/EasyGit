package org.easygit.core;

import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.FileChange;
import org.easygit.core.model.RepoSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照落盘存储:重开程序时靠它把上次看到的样子先画出来。
 *
 * 这里锁住的核心是**"缓存坏了也只当没有缓存"**:损坏、版本不符、路径对不上、过期、太大,
 * 一律返回空,绝不能因为一个缓存文件把应用搞崩或读出错数据。
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SnapshotStoreTest {

    private Path base, snapDir, repoA, repoB;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-snap-");
        snapDir = base.resolve("snapshots");
        repoA = Files.createDirectories(base.resolve("repoA"));
        repoB = Files.createDirectories(base.resolve("repoB"));
        SnapshotStore.setBaseDir(snapDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        SnapshotStore.setBaseDir(null);
        if (base != null && Files.exists(base)) {
            try (var s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** 一份内容齐全的快照(状态 + 分支 + 历史),字段故意含 null/空/特殊字符。 */
    private RepoSnapshot full(Path repo) {
        List<FileChange> changes = List.of(
                new FileChange("src/主文件.java", null, ' ', 'M', false, false),
                new FileChange("新 文件.txt", "旧 文件.txt", 'R', ' ', false, false),
                new FileChange("冲突.txt", null, 'U', 'U', true, false));
        List<BranchInfo> branches = List.of(
                new BranchInfo(BranchInfo.Kind.LOCAL, "main", "refs/heads/main", "abc123", true, "origin/main", "ahead 2", 1700000000L),
                new BranchInfo(BranchInfo.Kind.REMOTE, "origin/main", "refs/remotes/origin/main", "abc123", false, null, "", 1700000000L));
        List<CommitEntry> log = List.of(
                new CommitEntry("abc123def456", "abc123d", "p1 p2", "张三", "z@x", 1700000000L,
                        "HEAD -> main,origin/main", "修一个 bug", "正文第一行\n正文第二行"),
                new CommitEntry("p1p1p1p1p1p1", "p1p1p1", "", "李四", "l@x", 1699999000L, "", "初始提交", ""));
        return new RepoSnapshot(RepoSnapshot.CURRENT_VERSION, System.currentTimeMillis(), repo.toString(),
                "refs-fingerprint-1",
                new RepoSnapshot.StatusSnap("oid1", "main", "origin/main", false, 2, 1, changes, branches),
                new RepoSnapshot.HistorySnap("refs-history-1", 2000, true, "src/主文件.java", log,
                        Set.of("abc123def456")));
    }

    @Test
    @DisplayName("往返:状态/分支/历史连同中文与空字段都原样回来")
    void roundTrip() {
        SnapshotStore.save(repoA, full(repoA));
        RepoSnapshot got = SnapshotStore.load(repoA).orElseThrow(() -> new AssertionError("应该读得到"));

        assertEquals(RepoSnapshot.CURRENT_VERSION, got.version());
        assertEquals(repoA.toString(), got.repoPath());
        assertEquals("refs-fingerprint-1", got.refs());

        RepoSnapshot.StatusSnap st = got.status();
        assertEquals("main", st.branch());
        assertEquals("origin/main", st.upstream());
        assertEquals(2, st.ahead());
        assertEquals(1, st.behind());
        assertEquals(3, st.changes().size());
        assertEquals("src/主文件.java", st.changes().get(0).path);
        assertEquals("旧 文件.txt", st.changes().get(1).origPath);
        assertEquals('R', st.changes().get(1).indexState);
        assertTrue(st.changes().get(2).unmerged);
        assertEquals(2, st.branches().size());
        assertTrue(st.branches().get(0).current);
        assertEquals("ahead 2", st.branches().get(0).track);
        assertNull(st.branches().get(1).upstream, "空上游要回来还是 null");

        RepoSnapshot.HistorySnap h = got.history();
        assertEquals("refs-history-1", h.refs(), "历史自带指纹,不能与状态那份混用");
        assertEquals(2000, h.maxCommits());
        assertTrue(h.allBranches());
        assertEquals("src/主文件.java", h.pathFilter());
        assertEquals(2, h.log().size());
        assertEquals("abc123def456", h.log().get(0).id);
        assertEquals(List.of("p1", "p2"), h.log().get(0).parents);
        assertEquals(List.of("HEAD -> main", "origin/main"), h.log().get(0).refs);
        assertEquals("修一个 bug", h.log().get(0).subject);
        assertEquals("正文第一行\n正文第二行", h.log().get(0).body);
        assertEquals(Set.of("abc123def456"), h.unpushed());
    }

    @Test
    @DisplayName("没有缓存时返回空(不抛异常)")
    void missingFileIsEmpty() {
        assertTrue(SnapshotStore.load(repoA).isEmpty());
        assertTrue(SnapshotStore.load(null).isEmpty());
    }

    @Test
    @DisplayName("版本不符 → 整份丢弃")
    void versionMismatch() {
        RepoSnapshot s = full(repoA);
        SnapshotStore.save(repoA, new RepoSnapshot(99, s.savedAt(), s.repoPath(), s.refs(), s.status(), s.history()));
        assertTrue(SnapshotStore.load(repoA).isEmpty(), "格式版本不符时必须当没有缓存");
    }

    @Test
    @DisplayName("文件里的仓库路径对不上 → 丢弃(摘要撞车/文件被换过)")
    void repoPathMismatch() {
        SnapshotStore.save(repoA, full(repoB));   // 用 A 的文件名写了 B 的内容
        assertTrue(SnapshotStore.load(repoA).isEmpty(), "自校验失败必须丢弃");
    }

    @Test
    @DisplayName("过期(超过 7 天)→ 丢弃")
    void expired() {
        RepoSnapshot s = full(repoA);
        SnapshotStore.save(repoA, new RepoSnapshot(RepoSnapshot.CURRENT_VERSION,
                System.currentTimeMillis() - SnapshotStore.MAX_AGE_MS - 1000,
                s.repoPath(), s.refs(), s.status(), s.history()));
        assertTrue(SnapshotStore.load(repoA).isEmpty(), "太旧的快照会误导,必须丢弃");
    }

    @Test
    @DisplayName("文件损坏 → 丢弃,不抛异常")
    void corrupt() throws Exception {
        SnapshotStore.save(repoA, full(repoA));
        Path f = onlyJson();
        Files.writeString(f, "{这不是合法 JSON", StandardCharsets.UTF_8);
        assertTrue(SnapshotStore.load(repoA).isEmpty());
    }

    @Test
    @DisplayName("写入是原子的:不留 .tmp,且落盘后立刻可读")
    void atomicWriteLeavesNoTmp() throws Exception {
        SnapshotStore.save(repoA, full(repoA));
        try (Stream<Path> s = Files.list(snapDir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().endsWith(".tmp")).count(),
                    "不能留下半份临时文件");
        }
        assertTrue(SnapshotStore.load(repoA).isPresent());
    }

    @Test
    @DisplayName("最多保留 8 个仓库,超出淘汰最旧的")
    void prunesOldest() throws Exception {
        for (int i = 0; i < SnapshotStore.MAX_FILES + 4; i++) {
            Path r = Files.createDirectories(base.resolve("r" + i));
            java.util.Set<Path> before = listJson();
            SnapshotStore.save(r, full(r));
            // 找出这次新建的文件,把它的修改时间设成递增的旧时间 → 淘汰顺序可预期。
            // (不能按文件内容找:JSON 里 Windows 路径的反斜杠是转义过的)
            Path created = listJson().stream().filter(p -> !before.contains(p)).findFirst().orElse(null);
            if (created != null) {
                Files.setLastModifiedTime(created, java.nio.file.attribute.FileTime.fromMillis(1000L + i));
            }
        }
        long n;
        try (Stream<Path> s = Files.list(snapDir)) {
            n = s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
        assertTrue(n <= SnapshotStore.MAX_FILES, "快照文件数应被限制在 " + SnapshotStore.MAX_FILES + ",实际 " + n);
    }

    private java.util.Set<Path> listJson() throws Exception {
        if (!Files.isDirectory(snapDir)) return java.util.Set.of();   // 第一次还没建目录
        try (Stream<Path> s = Files.list(snapDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    @DisplayName("超大快照不落盘(宁可没有缓存,也别把磁盘/解析拖垮)")
    void tooLargeIsSkipped() {
        // 造一个足够大的历史:每条 body 约 1KB × 9000 条 > 8MB
        List<CommitEntry> big = new java.util.ArrayList<>();
        String body = "x".repeat(1000);
        for (int i = 0; i < 9000; i++) {
            big.add(new CommitEntry("id" + i, "id" + i, "", "a", "a@a", 1L, "", "s", body));
        }
        RepoSnapshot s = new RepoSnapshot(RepoSnapshot.CURRENT_VERSION, System.currentTimeMillis(),
                repoA.toString(), "r", null,
                new RepoSnapshot.HistorySnap("r", 2000, false, null, big, Set.of()));
        SnapshotStore.save(repoA, s);
        assertTrue(SnapshotStore.load(repoA).isEmpty(), "超过上限的快照不该落盘/读回");
    }

    @Test
    @DisplayName("读盘播种是同步的(首帧就得有内容)—— 由调用线程直接读写")
    void loadStaysSynchronous() {
        SnapshotStore.save(repoA, full(repoA));
        assertTrue(SnapshotStore.load(repoA).isPresent());
        assertEquals(Thread.currentThread().getName(), SnapshotStore.lastLoadThread(),
                "播种必须同步:异步读盘会让首帧空着,持久化就没意义了(写盘才走后台线程)");
    }

    @Test
    @DisplayName("异步落盘:不占用调用线程(切仓时不能卡 FX 线程),flush 后一定读得到")
    void asyncSaveRunsOffCallerThread() {
        SnapshotStore.saveAsync(repoA, full(repoA));
        assertTrue(SnapshotStore.flush(10_000), "flush 应该等到排队的落盘写完");

        assertEquals("easygit-snapshot", SnapshotStore.lastIoThread(),
                "落盘必须发生在后台线程 —— 2000 条提交的快照序列化要 ~60ms,占着 FX 线程就是掉帧");
        assertNotEquals(Thread.currentThread().getName(), SnapshotStore.lastIoThread());
        assertTrue(SnapshotStore.load(repoA).isPresent(), "flush 之后一定能读回来");
    }

    @Test
    @DisplayName("异步落盘按提交顺序写:后提交的覆盖先提交的(单线程队列)")
    void asyncSaveKeepsOrder() {
        RepoSnapshot first = full(repoA);
        SnapshotStore.saveAsync(repoA, new RepoSnapshot(first.version(), first.savedAt(), first.repoPath(),
                "refs-1", first.status(), first.history()));
        for (int i = 0; i < 20; i++) {
            SnapshotStore.saveAsync(repoA, new RepoSnapshot(first.version(), first.savedAt(), first.repoPath(),
                    "refs-2", first.status(), first.history()));
        }
        assertTrue(SnapshotStore.flush(10_000));
        assertEquals("refs-2", SnapshotStore.load(repoA).orElseThrow().refs(),
                "最后一次提交的内容必须在磁盘上(顺序被打乱就会读到旧的那份)");
    }

    @Test
    @DisplayName("异步落盘:空参数直接忽略,不抛异常")
    void asyncSaveIgnoresNulls() {
        SnapshotStore.saveAsync(null, full(repoA));
        SnapshotStore.saveAsync(repoA, null);
        assertTrue(SnapshotStore.flush(5_000));
        assertTrue(SnapshotStore.load(repoA).isEmpty(), "什么都没写,读不到是正常的");
    }

    // ---------- 辅助 ----------

    private Path onlyJson() throws Exception {
        try (Stream<Path> s = Files.list(snapDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
        }
    }
}
