package org.easygit.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刷新成本回归(真实临时仓库)。
 *
 * Windows 上 git 进程启动本身就要 100~200ms,所以「一次刷新起几个进程」几乎就等于
 * 刷新耗时。这里用 {@link GitProcess#execCount()} 把已经做过的合并/裁剪锁住:
 * 谁再把一个进程拆回两个,或者让指纹格式与轮询用的格式不一致(那样会每 5 秒白重载一次
 * 历史),这些用例就会红。
 *
 * 类级 {@link Timeout} 用 SEPARATE_THREAD:用例会起 git 子进程,退化时要变红而不是挂死。
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RefreshCostTest {

    private Path base, origin, work;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-cost-");
        origin = base.resolve("origin.git");
        work = base.resolve("work");
        git(base, "init", "--bare", "-q", origin.toString());
        git(null, "-c", "core.autocrlf=false", "clone", "-q", origin.toString(), work.toString());
        config(work);
        Files.writeString(work.resolve("f.txt"), "line1\nline2\n");
        commit(work, "c1");
        git(work, "push", "-q", "-u", "origin", "master");
        // 预热:首次调用会解析 git 路径等一次性开销,别算进被测调用的账上
        NativeGit.headSha(work);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (base != null && Files.exists(base)) {
            try (var s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("未推送集合:一个 git 进程(原来 rev-parse + rev-list 两个)")
    void unpushedShasCostsOneProcess() throws Exception {
        Files.writeString(work.resolve("g.txt"), "local\n");
        commit(work, "local-1");

        int before = GitProcess.execCount();
        var set = NativeGit.unpushedShas(work, 100);
        int used = GitProcess.execCount() - before;

        assertEquals(1, used, "未推送集合必须只起一个进程");
        assertEquals(1, set.size(), "本地多出一个提交,应量到 1 个未推送:" + set);
    }

    @Test
    @DisplayName("没有上游:仍是空集,且只起一个进程(靠 revspec 报错,不再先查上游)")
    void unpushedShasWithoutUpstream() throws Exception {
        git(work, "branch", "--unset-upstream");

        int before = GitProcess.execCount();
        var set = NativeGit.unpushedShas(work, 100);
        int used = GitProcess.execCount() - before;

        assertTrue(set.isEmpty(), "没有上游时未推送集合必须为空");
        assertEquals(1, used, "没有上游也只该起一个进程");
    }

    @Test
    @DisplayName("分支列表 + 引用指纹:共用一次 for-each-ref")
    void refSnapshotIsOneProcess() {
        int before = GitProcess.execCount();
        var snap = NativeGit.refSnapshot(work);
        int used = GitProcess.execCount() - before;

        assertEquals(1, used, "分支列表与引用指纹必须共用一次 for-each-ref");
        assertFalse(snap.branches().isEmpty(), "应读到 master 分支");
        assertEquals(NativeGit.branches(work).size(), snap.branches().size(),
                "refSnapshot 的分支列表要与 branches() 一致");
    }

    @Test
    @DisplayName("指纹格式与 refsFingerprint 完全一致(不一致会导致每 5 秒白重载一次历史)")
    void fingerprintMatchesPollingFormat() {
        String fromSnapshot = NativeGit.refSnapshot(work).fingerprint();
        String fromPoll = NativeGit.refsFingerprint(work);
        assertEquals(fromPoll, fromSnapshot,
                "phase1 算出的指纹被拿去和轮询的 refsFingerprint 比较,两者必须逐字节相同");
    }

    @Test
    @DisplayName("引用变化(新建分支)时指纹跟着变")
    void fingerprintTracksRefChange() throws Exception {
        String before = NativeGit.refSnapshot(work).fingerprint();
        git(work, "branch", "side");
        String after = NativeGit.refSnapshot(work).fingerprint();
        assertNotEquals(before, after, "新增引用后指纹必须变化,否则历史页不会自动刷新");
        assertTrue(after.contains("refs/heads/side"), "指纹应包含新引用: " + after);
    }

    // ---------- git 夹具 ----------

    private static void config(Path repo) throws Exception {
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "T");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.autocrlf", "false");
    }

    private static void commit(Path repo, String msg) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", msg);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().put("GIT_AUTHOR_NAME", "T");
        pb.environment().put("GIT_COMMITTER_NAME", "T");
        pb.environment().put("GIT_AUTHOR_EMAIL", "t@t");
        pb.environment().put("GIT_COMMITTER_EMAIL", "t@t");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " → " + code + "\n" + out);
        }
    }
}
