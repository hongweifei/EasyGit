package org.easygit.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推送管线(真实 git 仓库夹具,不依赖 JavaFX)。
 *
 * 覆盖:前置探测(同步/仅领先/分叉/无上游)/ 推送步进与计数 / 设置上游 /
 * 非快进被拒与强制推送 / 中文报错归类。
 */
class PushPipelineTest {

    private Path base, origin, work, other;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-push-");
        origin = base.resolve("origin.git");
        work = base.resolve("work");
        other = base.resolve("other");
        git(base, "init", "--bare", "-q", origin.toString());
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
    @DisplayName("前置探测:同步 / 仅领先 / 分叉 / 无上游 四种状态判定正确")
    void pushPlanStates() throws Exception {
        // 同步
        var plan = NativeGit.pushPlan(work, "master");
        assertEquals("origin/master", plan.upstream());
        assertTrue(plan.synced(), "刚推送完应判定为同步");
        assertFalse(plan.pushable());
        assertFalse(plan.diverged());

        // 本地领先 1
        Files.writeString(work.resolve("a.txt"), "a\n");
        commit(work, "work-1");
        plan = NativeGit.pushPlan(work, "master");
        assertTrue(plan.pushable(), "本地领先应判定为可推送");
        assertEquals(1, plan.ahead());
        assertEquals(0, plan.behind());

        // 远端也前进 → 分叉(本地引用要 fetch 过才能看到:前置探测基于上次 fetch 的本地引用)
        Files.writeString(other.resolve("b.txt"), "b\n");
        commit(other, "other-1");
        git(other, "push", "-q");
        git(work, "fetch", "-q", "origin");
        plan = NativeGit.pushPlan(work, "master");
        assertTrue(plan.diverged(), "两端各有提交应判定为分叉");
        assertEquals(1, plan.ahead());
        assertEquals(1, plan.behind());
    }

    @Test
    @DisplayName("无上游的分支:noUpstream;推送后自动建立上游")
    void noUpstreamThenSetUpstream() throws Exception {
        git(work, "branch", "feature");
        assertNull(NativeGit.upstreamOf(work, "feature"), "没推送过的分支没有上游");
        var plan = NativeGit.pushPlan(work, "feature");
        assertTrue(plan.noUpstream(), "PushPlan 应标注无上游");

        // 推送并设置上游
        var step = NativeGit.runPushStep(work, "feature", "origin", true, false);
        assertTrue(step.result().ok(), step.result().message());
        assertEquals("origin/feature", NativeGit.upstreamOf(work, "feature"),
                "推送 -u 后应建立上游");
        assertEquals(0, step.pushed(), "首次推送没有旧远程引用,计数为 0");
    }

    @Test
    @DisplayName("推送步进:pushed 计数 = 实际推上去的提交数")
    void pushCount() throws Exception {
        Files.writeString(work.resolve("a.txt"), "1\n");
        commit(work, "w1");
        Files.writeString(work.resolve("a.txt"), "2\n");
        commit(work, "w2");
        var step = NativeGit.runPushStep(work, "master", "origin", false, false);
        assertTrue(step.result().ok(), step.result().message());
        assertEquals(2, step.pushed(), "应推上去 2 个提交");
    }

    @Test
    @DisplayName("非快进被拒:识别 + 中文提示;强制推送(--force-with-lease)可覆盖")
    void nonFastForwardRejectedThenForce() throws Exception {
        Files.writeString(work.resolve("a.txt"), "local\n");
        commit(work, "work-side");
        Files.writeString(other.resolve("b.txt"), "other\n");
        commit(other, "other-side");
        git(other, "push", "-q");

        var rejected = NativeGit.runPushStep(work, "master", "origin", false, false);
        assertFalse(rejected.result().ok(), "非快进推送必须被远端拒绝");
        assertTrue(NativeGit.isPushRejected(rejected.result().message()),
                "应识别为推送被拒: " + rejected.result().message());
        String hint = NativeGit.friendlyError(rejected.result().message());
        assertTrue(hint.contains("拉取"), "中文提示应建议先拉取: " + hint);

        // 凭据类失败也会带 "failed to push some refs",但不得误判为推送被拒
        assertFalse(NativeGit.isPushRejected(
                "fatal: Authentication failed for 'https://x'\nerror: failed to push some refs"));

        // 强制推送:work 没 fetch 过,lease 校验发现本地对远端的认知过期 → 拒绝(stale info 是保护行为)
        var forced1 = NativeGit.runPushStep(work, "master", "origin", false, true);
        assertFalse(forced1.result().ok(), "本地对远端认知过期时 lease 应拒绝");
        String staleHint = NativeGit.friendlyError(forced1.result().message());
        assertTrue(staleHint.contains("抓取"), "stale info 应提示先抓取: " + staleHint);

        // 抓取刷新远端状态后,lease 校验通过,强制推送成功
        git(work, "fetch", "-q", "origin");
        var forced = NativeGit.runPushStep(work, "master", "origin", false, true);
        assertTrue(forced.result().ok(), forced.result().message());
        // 远端(以本地 origin/master 为准)应与被推的分支一致
        assertEquals(out(work, "rev-parse", "master").strip(),
                out(work, "rev-parse", "origin/master").strip(),
                "强制推送后远端应与本地一致");
    }

    @Test
    @DisplayName("中文报错归类:推送被拒 / 钩子拒绝 / 分支保护")
    void friendlyErrors() {
        String rejected = "To https://x\n ! [rejected]        master -> master (fetch first)\n"
                + "error: failed to push some refs";
        assertTrue(NativeGit.friendlyError(rejected).contains("推送被拒绝"), NativeGit.friendlyError(rejected));
        assertTrue(NativeGit.friendlyError(" ! [remote rejected] master -> master (pre-receive hook declined)")
                .contains("钩子"));
        assertTrue(NativeGit.friendlyError("remote: You are not allowed to push code to protected branch: master")
                .contains("权限"));
        // 认不出时原样返回
        assertEquals("fatal: something odd", NativeGit.friendlyError("fatal: something odd"));
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
