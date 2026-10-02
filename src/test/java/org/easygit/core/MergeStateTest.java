package org.easygit.core;

import org.easygit.core.model.MergeState;
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
 * 进行中的合并/变基/拣选:状态识别 + 继续/跳过/中止(真实 git 仓库夹具)。
 *
 * 类级 {@link Timeout} 用 SEPARATE_THREAD:继续变基要压制编辑器,
 * 万一回归成"git 拉起编辑器等人输入",测试必须在 90 秒内变红,而不是把构建永久挂住。
 */
@Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MergeStateTest {

    private Path base, origin, work, other;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-merge-");
        origin = base.resolve("origin.git");
        work = base.resolve("work");
        other = base.resolve("other");
        git(base, "init", "--bare", "-q", origin.toString());
        git(null, "-c", "core.autocrlf=false", "clone", "-q", origin.toString(), work.toString());
        config(work);
        Files.writeString(work.resolve("f.txt"), "line1\nline2\n");
        commit(work, "c1");
        git(work, "push", "-q", "-u", "origin", "master");
        // other 必须在 c1 推上去之后再克隆,否则它是一条"无历史"的根提交,推回去必然被拒
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
    @DisplayName("干净仓库:没有进行中的操作,也不许瞎继续/跳过")
    void cleanRepoHasNoOperation() {
        MergeState st = NativeGit.mergeState(work);
        assertEquals(MergeState.Kind.NONE, st.kind());
        assertFalse(st.inProgress());
        assertFalse(st.canSkip());
        assertTrue(st.progressText().isEmpty());
        assertFalse(NativeGit.continueInProgress(work).ok(), "没有操作时「继续」必须被拒绝");
        assertFalse(NativeGit.skipInProgress(work).ok(), "没有操作时「跳过」必须被拒绝");
    }

    @Test
    @DisplayName("合并冲突:识别为合并,继续后产生合并提交")
    void mergeConflictThenContinue() throws Exception {
        divergeConflicting();
        String before = NativeGit.headSha(work);
        assertFalse(NativeGit.mergeUpstream(work).ok(), "两边改同一行应合并冲突");
        assertEquals(1, NativeGit.unmergedCount(work));
        assertEquals(List.of("f.txt"), NativeGit.unmergedPaths(work));

        MergeState st = NativeGit.mergeState(work);
        assertEquals(MergeState.Kind.MERGE, st.kind());
        assertTrue(st.detail().startsWith("Merge"), "详情应是 MERGE_MSG 首行: " + st.detail());
        assertTrue(st.message().contains("Merge"), "默认说明要能直接填进提交框: " + st.message());
        assertFalse(st.message().contains("#"), "注释行(# Conflicts:)不能带进提交框");
        assertFalse(st.canSkip(), "合并没有「跳过单个提交」这回事");

        // 解决:写入解决后的内容 + 标记已解决(CLI add,索引里的冲突阶段要清掉)
        Files.writeString(work.resolve("f.txt"), "resolved\nline2\n");
        assertTrue(NativeGit.markResolved(work, List.of("f.txt")).ok());
        assertEquals(0, NativeGit.unmergedCount(work));

        var done = NativeGit.continueInProgress(work);
        assertTrue(done.ok(), "继续合并必须成功: " + done.message());
        assertEquals(2, parentCount(work), "继续后应产生合并提交");
        assertEquals(MergeState.Kind.NONE, NativeGit.mergeState(work).kind());
        assertNotEquals(before, NativeGit.headSha(work));
    }

    @Test
    @DisplayName("变基冲突:能报出第几个提交,继续后历史保持线性(且不挂死)")
    void rebaseConflictStateAndContinue() throws Exception {
        divergeConflicting();
        assertFalse(NativeGit.rebaseOntoUpstream(work).ok(), "两边改同一行应变基冲突");

        MergeState st = NativeGit.mergeState(work);
        assertEquals(MergeState.Kind.REBASE, st.kind());
        assertEquals(1, st.total(), "待重放的提交数应为 1");
        assertEquals(1, st.step(), "应停在第 1 个提交");
        assertTrue(st.canSkip());
        assertEquals("第 1/1 个提交", st.progressText());

        Files.writeString(work.resolve("f.txt"), "resolved\nline2\n");
        assertTrue(NativeGit.markResolved(work, List.of("f.txt")).ok());

        var done = NativeGit.continueInProgress(work);
        assertTrue(done.ok(), "继续变基必须成功,且不能因拉起编辑器而卡住: " + done.message());
        assertEquals(MergeState.Kind.NONE, NativeGit.mergeState(work).kind());
        assertEquals(1, parentCount(work), "变基后仍是线性历史");
        assertTrue(subject(work, "HEAD").contains("local-1"), "本地提交应被重放: " + subject(work, "HEAD"));
    }

    @Test
    @DisplayName("跳过:变基中当前提交被丢弃,HEAD 落在远端提交上")
    void skipRebaseDropsCurrentCommit() throws Exception {
        divergeConflicting();
        assertFalse(NativeGit.rebaseOntoUpstream(work).ok());

        var skipped = NativeGit.skipInProgress(work);
        assertTrue(skipped.ok(), "跳过当前提交应成功: " + skipped.message());
        assertEquals(MergeState.Kind.NONE, NativeGit.mergeState(work).kind());
        assertEquals(0, NativeGit.unmergedCount(work));
        assertTrue(subject(work, "HEAD").contains("remote-1"),
                "跳过之后 HEAD 应是远端提交: " + subject(work, "HEAD"));
    }

    @Test
    @DisplayName("中止:HEAD、工作区、冲突全部回到操作前")
    void abortRestoresPreMergeState() throws Exception {
        divergeConflicting();
        String before = NativeGit.headSha(work);
        assertFalse(NativeGit.mergeUpstream(work).ok());
        assertTrue(NativeGit.mergeState(work).inProgress());

        assertTrue(NativeGit.abortInProgress(work).ok(), "中止合并应成功");
        assertEquals(MergeState.Kind.NONE, NativeGit.mergeState(work).kind());
        assertEquals(0, NativeGit.unmergedCount(work));
        assertEquals(before, NativeGit.headSha(work), "中止后 HEAD 必须回到操作前");
        assertFalse(NativeGit.dirty(work));
    }

    @Test
    @DisplayName("拣选冲突:识别为拣选(带提交号),继续后提交落地")
    void cherryPickConflictThenContinue() throws Exception {
        git(work, "checkout", "-q", "-b", "side");
        Files.writeString(work.resolve("f.txt"), "side-line\nline2\n");
        commit(work, "side-1");
        String sideSha = NativeGit.headSha(work);
        git(work, "checkout", "-q", "master");
        Files.writeString(work.resolve("f.txt"), "master-line\nline2\n");
        commit(work, "master-1");

        assertFalse(GitProcess.in(work).exec("cherry-pick", sideSha).ok(), "两边改同一行应拣选冲突");
        MergeState st = NativeGit.mergeState(work);
        assertEquals(MergeState.Kind.CHERRY_PICK, st.kind());
        assertEquals(sideSha.substring(0, 8), st.detail(), "详情应是短提交号");

        Files.writeString(work.resolve("f.txt"), "resolved\nline2\n");
        assertTrue(NativeGit.markResolved(work, List.of("f.txt")).ok());
        var done = NativeGit.continueInProgress(work);
        assertTrue(done.ok(), "继续拣选必须成功: " + done.message());
        assertEquals("side-1", subject(work, "HEAD"));
        assertEquals(MergeState.Kind.NONE, NativeGit.mergeState(work).kind());
    }

    // ---------- 夹具 ----------

    /** 制造"双方都改同一行"的分叉:work 一条本地提交,origin 一条远端提交。 */
    private void divergeConflicting() throws Exception {
        Files.writeString(work.resolve("f.txt"), "work-side\nline2\n");
        commit(work, "local-1");
        Files.writeString(other.resolve("f.txt"), "other-side\nline2\n");
        commit(other, "remote-1");
        git(other, "push", "-q");
        git(work, "fetch", "-q", "origin");
    }

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
