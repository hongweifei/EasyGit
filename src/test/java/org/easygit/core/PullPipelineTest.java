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
 * 拉取管线(真实 git 仓库夹具,不依赖 JavaFX)。
 *
 * 覆盖:上游探测 / 快进不产生合并提交 / 分叉合并 / 分叉变基 / 冲突识别与中止 /
 * 本地改动挡住 → 暂存重试恢复 / 网络操作不挂死 / 中文报错归类。
 *
 * 类级 {@link Timeout} 用 SEPARATE_THREAD:这些用例会真的起 git 子进程,
 * 万一哪个命令退化成"等 stdin/等编辑器",必须在 120 秒内变红而不是挂死构建。
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PullPipelineTest {

    private Path base, origin, work, other;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-pull-");
        origin = base.resolve("origin.git");
        work = base.resolve("work");
        other = base.resolve("other");
        git(base, "init", "--bare", "-q", origin.toString());
        // 克隆时就关掉 autocrlf:克隆后才发现换行设置的话,工作区已被检出 CRLF,
        // 之后任何 git add -A 都会把"换行差异"当成真实改动提交,制造假冲突。
        git(null, "-c", "core.autocrlf=false", "clone", "-q", origin.toString(), work.toString());
        config(work);
        Files.writeString(work.resolve("f.txt"), "line1\nline2\n");
        commit(work, "c1");
        git(work, "push", "-q", "-u", "origin", "master");
        git(null, "-c", "core.autocrlf=false", "clone", "-q", origin.toString(), other.toString());
        config(other);
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
    @DisplayName("上游探测:有上游返回 remote/branch,无上游返回 null")
    void upstreamDetection() throws Exception {
        assertEquals("origin/master", NativeGit.upstream(work));
        Path lonely = base.resolve("lonely");
        git(null, "init", "-q", lonely.toString());
        config(lonely);
        Files.writeString(lonely.resolve("x.txt"), "x\n");
        commit(lonely, "x1");
        assertNull(NativeGit.upstream(lonely), "没有上游时必须是 null,而不是空串或原始输出");
    }

    @Test
    @DisplayName("可快进:ff-only 不产生合并提交")
    void fastForwardKeepsHistoryLinear() throws Exception {
        Files.writeString(other.resolve("g.txt"), "remote\n");
        commit(other, "r1");
        git(other, "push", "-q");

        assertTrue(NativeGit.fetchUpstream(work).ok());
        int[] ab = NativeGit.aheadBehind(work);
        assertEquals(0, ab[0], "本地没有提交");
        assertEquals(1, ab[1], "落后 1 个提交");

        var plan = NativeGit.pullPlan(work);
        assertTrue(plan.fastForwardable(), "应判定为可快进");
        assertFalse(plan.diverged());
        assertFalse(plan.upToDate());

        var step = NativeGit.runPullStep(work, () -> NativeGit.mergeUpstreamFfOnly(work));
        assertTrue(step.result().ok(), step.result().message());
        assertEquals(1, parentCount(work), "快进不能产生合并提交");
        assertEquals(1, step.incoming(), "快进带来 1 个提交,状态栏应报'新增 1 个提交'");
        assertEquals(0, step.conflicts());
    }

    @Test
    @DisplayName("已分叉:合并拉取产生 2 父提交,变基拉取保持线性")
    void divergedMergeAndRebase() throws Exception {
        Files.writeString(work.resolve("f.txt"), "line1\nline2\nlocal\n");
        commit(work, "local1");
        Files.writeString(other.resolve("g.txt"), "remote\n");
        commit(other, "r2");
        git(other, "push", "-q");
        assertTrue(NativeGit.fetchUpstream(work).ok());

        var plan = NativeGit.pullPlan(work);
        assertTrue(plan.diverged(), "两端各有提交应判定为分叉");
        assertEquals(1, plan.ahead());
        assertEquals(1, plan.behind());

        // 合并
        var merge = NativeGit.runPullStep(work, () -> NativeGit.mergeUpstream(work));
        assertTrue(merge.result().ok(), merge.result().message());
        assertEquals(2, parentCount(work), "合并拉取应产生合并提交");

        // 退回去用变基跑同一条分叉
        git(work, "reset", "-q", "--hard", "HEAD~1");
        assertTrue(NativeGit.pullPlan(work).diverged());
        var rebase = NativeGit.runPullStep(work, () -> NativeGit.rebaseOntoUpstream(work));
        assertTrue(rebase.result().ok(), rebase.result().message());
        assertEquals(1, parentCount(work), "变基后历史必须保持线性");
        assertTrue(subject(work, "HEAD").contains("local1"), "本地提交应在远端之后重放");
    }

    @Test
    @DisplayName("冲突:识别 unmerged,可中止恢复")
    void conflictThenAbort() throws Exception {
        Files.writeString(work.resolve("f.txt"), "local-side\n");
        commit(work, "conflict-local");
        Files.writeString(other.resolve("f.txt"), "remote-side\n");
        commit(other, "conflict-remote");
        git(other, "push", "-q");
        assertTrue(NativeGit.fetchUpstream(work).ok());

        var step = NativeGit.runPullStep(work, () -> NativeGit.mergeUpstream(work));
        assertFalse(step.result().ok(), "冲突时 git 必须返回非 0");
        assertTrue(step.conflicts() > 0, "应量出冲突文件数");
        assertEquals(step.conflicts(), NativeGit.unmergedCount(work));
        assertTrue(NativeGit.dirty(work));

        assertTrue(NativeGit.abortInProgress(work).ok());
        assertEquals(0, NativeGit.unmergedCount(work), "中止后不应再有冲突");
    }

    @Test
    @DisplayName("本地改动挡住拉取:stashRetryPull 暂存重试并恢复改动")
    void localChangesStashRetry() throws Exception {
        // 关键:本地与远端必须都改同一个文件,否则快进会直接成功(git 只拦"会被覆盖的"改动)
        Files.writeString(work.resolve("f.txt"), "dirty-first-line\nline2\n");
        Files.writeString(other.resolve("f.txt"), "line1\nline2\nremote-third\n");
        commit(other, "r3");
        git(other, "push", "-q");
        assertTrue(NativeGit.fetchUpstream(work).ok());

        var blocked = NativeGit.mergeUpstreamFfOnly(work);
        assertFalse(blocked.ok(), "本地改动应挡住快进");
        assertTrue(NativeGit.isLocalObstruction(blocked.message()),
                "应识别为本地阻挡: " + blocked.message());

        var retry = NativeGit.stashRetryPull(work, "拉取", () -> NativeGit.mergeUpstreamFfOnly(work));
        assertNull(retry.stashError(), "暂存应成功");
        assertNull(retry.pullError(), "暂存后应能拉取成功");
        assertNotNull(retry.pop());
        // 本地改第 1 行、远端在第 3 行后追加:恢复应当干净成功(不冲突)
        assertTrue(retry.pop().ok(), "本地改动应被恢复: " + retry.pop().message());
        String merged = Files.readString(work.resolve("f.txt")).replace("\r\n", "\n");
        assertTrue(merged.contains("dirty-first-line"), "本地改动要回到工作区: " + merged);
        assertTrue(merged.contains("remote-third"), "远端改动也要在: " + merged);
    }

    @Test
    @DisplayName("网络类命令不会因等待 stdin 挂死")
    void stdinIsClosed() {
        long t0 = System.currentTimeMillis();
        var r = GitProcess.in(work).exec("hash-object", "--stdin");
        long ms = System.currentTimeMillis() - t0;
        assertTrue(r.ok(), "stdin 已关闭,hash-object 应立即以空输入成功: " + r.message());
        assertTrue(ms < 10_000, "读 stdin 的命令不能挂住,实测 " + ms + "ms");
    }

    @Test
    @DisplayName("网络操作带 GIT_TERMINAL_PROMPT=0,普通操作不带")
    void nonInteractiveEnvOnlyForNetwork() {
        var net = GitProcess.in(work)
                .execNet("-c", "alias.ep=!echo TERM=[$GIT_TERMINAL_PROMPT]", "ep");
        assertTrue(net.out().contains("TERM=[0]"), "网络操作必须禁交互: " + net.out());
        var plain = GitProcess.in(work)
                .exec("-c", "alias.ep=!echo TERM=[$GIT_TERMINAL_PROMPT]", "ep");
        assertFalse(plain.out().contains("TERM=[0]"), "普通操作不该被注入该变量: " + plain.out());
    }

    @Test
    @DisplayName("英文报错归类成中文提示")
    void friendlyErrors() {
        assertTrue(NativeGit.friendlyError("fatal: could not read Username for 'https://x': terminal prompts disabled")
                .contains("凭据"));
        assertTrue(NativeGit.friendlyError("fatal: unable to access 'https://x': Could not resolve host: x")
                .contains("连不上"));
        assertTrue(NativeGit.friendlyError("fatal: Not possible to fast-forward, aborting.").contains("分叉"));
        assertTrue(NativeGit.friendlyError("fatal: no tracking information for the current branch").contains("上游"));
        assertTrue(NativeGit.friendlyError("error: Your local changes to the following files would be overwritten")
                .contains("本地未提交"));
        assertEquals("fatal: something odd", NativeGit.friendlyError("fatal: something odd"),
                "认不出的报错必须原样返回,不能吞掉");
    }

    @Test
    @DisplayName("引用指纹:侧支/远程分支新提交(HEAD 不动)也能被发现")
    void refsFingerprintCatchesNonHeadRefChanges() throws Exception {
        String base0 = NativeGit.refsFingerprint(work);
        assertFalse(base0.isBlank(), "指纹不应为空");

        // 1) 侧支分支新提交:HEAD 完全不动
        git(work, "branch", "-f", "side", "HEAD");
        git(work, "checkout", "-q", "side");
        Files.writeString(work.resolve("side.txt"), "side\n");
        commit(work, "side-1");
        git(work, "checkout", "-q", "master");
        String afterSide = NativeGit.refsFingerprint(work);
        assertNotEquals(base0, afterSide,
                "侧支分支前进后指纹必须变化,否则历史页不会自动刷新(用户得手动点刷新)");
        assertTrue(afterSide.contains("refs/heads/side"), "指纹应包含侧支引用");

        // 2) 远程跟踪分支前进(他人推送 + fetch)
        git(work, "checkout", "-q", "-b", "tmp-push", "origin/master");
        Files.writeString(work.resolve("remote.txt"), "remote\n");
        commit(work, "remote-1");
        git(work, "push", "-q", "origin", "tmp-push:master");
        git(work, "checkout", "-q", "master");
        git(work, "branch", "-q", "-D", "tmp-push");
        git(work, "fetch", "-q", "origin");
        String afterFetch = NativeGit.refsFingerprint(work);
        assertNotEquals(afterSide, afterFetch, "fetch 更新远程跟踪分支后指纹必须变化");

        // 3) 删掉引用也要能发现
        git(work, "branch", "-D", "side");
        assertNotEquals(afterFetch, NativeGit.refsFingerprint(work), "删除分支后指纹必须变化");

        // 4) 没有任何变化时指纹必须稳定(否则每 5 秒白刷一次历史)
        assertEquals(NativeGit.refsFingerprint(work), NativeGit.refsFingerprint(work));
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

    private static int parentCount(Path repo) throws Exception {
        String s = out(repo, "rev-list", "--parents", "-n", "1", "HEAD").strip();
        return s.split("\\s+").length - 1;
    }

    private static String subject(Path repo, String rev) throws Exception {
        return out(repo, "log", "-1", "--format=%s", rev).strip();
    }

    private static String out(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return s;
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
