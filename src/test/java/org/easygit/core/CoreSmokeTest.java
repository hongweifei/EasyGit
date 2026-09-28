package org.easygit.core;

import org.easygit.core.model.BlameLine;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.FileChange;
import org.easygit.core.StatusParser.StatusResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心层集成冒烟:使用真实 git CLI 在临时目录里跑通 全流程。
 */
class CoreSmokeTest {

    @TempDir
    Path tmp;

    private static void git(Path repo, String... args) {
        GitProcess.GitResult r = GitProcess.in(repo).exec(args);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " 失败: " + r.message());
    }

    private Path initRepo() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        assertTrue(NativeGit.init(repo).ok());
        // 本测试专用的提交者身份,不污染全局配置
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "tester@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        return repo;
    }

    @Test
    void gitLfsTrackCommitAndList() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(LfsService.installed(), "Git LFS 未安装,跳过");
        Path repo = initRepo();
        // 本仓库局部初始化(不动全局配置)
        GitProcess.GitResult li = GitProcess.in(repo).exec("lfs", "install", "--local");
        assertTrue(li.ok(), li.message());

        assertTrue(LfsService.track(repo, "*.big").ok());
        assertTrue(LfsService.trackPatterns(repo).contains("*.big"));
        assertTrue(LfsService.repoUsesLfs(repo));

        // 提交一个会被 LFS 接管的文件(此仓库 usesLfs → 自动走 CLI add/commit)
        byte[] data = new byte[2048];
        java.util.Arrays.fill(data, (byte) 'A');
        Files.write(repo.resolve("model.big"), data);
        JGitService svc = new JGitService(repo);
        svc.stageAll();
        svc.commit("add lfs file", false);

        List<LfsService.LfsFile> files = LfsService.lsFiles(repo);
        assertEquals(1, files.size(), "应有一个 LFS 文件");
        assertEquals("model.big", files.get(0).path());
        assertTrue(files.get(0).downloaded(), "提交后本地应有完整对象");

        // 移除规则(.gitattributes)
        assertTrue(LfsService.untrack(repo, "*.big").ok());
        assertFalse(LfsService.trackPatterns(repo).contains("*.big"));
    }

    @Test
    void gitLocatorPrefersGitForWindows() {
        String exe = GitLocator.executable();
        assertNotNull(exe);
        assertFalse(exe.isBlank());
        // 解析出的 git 必须可执行
        GitProcess.GitResult r = GitProcess.global("--version");
        assertTrue(r.ok(), "解析到的 git 不可用: " + exe);
        System.out.println("EasyGit 使用 git = " + exe);
        System.out.println("PATH 前缀 = " + GitLocator.pathPrefix());
    }

    @Test
    void statusParseUntrackedThenStagedThenCommit() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);

        StatusResult st = NativeGit.status(repo);
        assertEquals(1, st.changes().size());
        assertTrue(st.changes().get(0).untracked);

        new JGitService(repo).stage(List.of("a.txt"));
        st = NativeGit.status(repo);
        FileChange staged = st.changes().get(0);
        assertEquals('A', staged.indexState);
        assertFalse(staged.untracked);

        String sha = new JGitService(repo).commit("first commit", false);
        assertTrue(sha.matches("[0-9a-f]{40}"));

        st = NativeGit.status(repo);
        assertTrue(st.changes().isEmpty());
        assertEquals("master", st.branch());
    }

    @Test
    void logAndGraphAndDiff() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("a.txt"), "line1\nline2\n", StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c1", false);
        Files.writeString(repo.resolve("a.txt"), "line1\nline2 changed\nline3\n", StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c2", false);

        List<CommitEntry> log = NativeGit.log(repo, 100, false, null);
        assertEquals(2, log.size());
        assertEquals("c2", log.get(0).subject);
        assertEquals(1, log.get(0).parents.size());
        GraphBuilder.build(log);
        assertEquals(0, log.get(0).lane);
        // 新契约:首父沿本道继续由渲染器画竖线,不产生显式边
        assertFalse(log.get(0).parents.isEmpty());

        List<DiffFile> unstaged = NativeGit.diffUnstaged(repo);
        assertTrue(unstaged.isEmpty());

        List<DiffFile> commitDiff = NativeGit.diffCommit(repo, log.get(0));
        assertEquals(1, commitDiff.size());
        assertEquals("a.txt", commitDiff.get(0).newPath);
        assertEquals(2, commitDiff.get(0).added);   // line2 changed + line3
        assertEquals(1, commitDiff.get(0).deleted); // line2
    }

    @Test
    void branchLifecycleAndStash() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("a.txt"), "v1\n", StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c1", false);

        JGitService svc = new JGitService(repo);
        svc.createBranch("feature", null);
        svc.checkout("feature", false, null);

        List<BranchInfo> branches = NativeGit.branches(repo);
        assertTrue(branches.stream().anyMatch(b -> b.kind == BranchInfo.Kind.LOCAL
                && b.name.equals("feature") && b.current));

        // stash
        Files.writeString(repo.resolve("a.txt"), "v1\nwip\n", StandardCharsets.UTF_8);
        svc.stash("wip work", true);
        assertTrue(NativeGit.status(repo).changes().isEmpty());
        List<org.easygit.core.model.StashEntry> stashes = svc.stashList();
        assertEquals(1, stashes.size());
        assertEquals("stash@{0}", stashes.get(0).ref());
        svc.stashApply(0);
        assertEquals(List.of("v1", "wip"), Files.readAllLines(repo.resolve("a.txt")));
        svc.stashDrop(0);
        assertTrue(svc.stashList().isEmpty());
    }

    @Test
    void mergeConflictDetectionAndResolution() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("f.txt"), "base\n", StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c0", false);

        JGitService svc = new JGitService(repo);
        svc.createBranch("side", null);
        // main 上修改
        Files.writeString(repo.resolve("f.txt"), "main change\n", StandardCharsets.UTF_8);
        svc.stageAll();
        svc.commit("main edit", false);
        // side 上修改同一行
        svc.checkout("side", false, null);
        Files.writeString(repo.resolve("f.txt"), "side change\n", StandardCharsets.UTF_8);
        svc.stageAll();
        svc.commit("side edit", false);
        // 回到 master 合并
        svc.checkout("master", false, null);
        JGitService.MergeOutcome outcome = svc.merge("side");
        assertEquals("CONFLICTING", outcome.status());
        assertTrue(outcome.conflicts().contains("f.txt"));

        StatusResult st = NativeGit.status(repo);
        assertTrue(st.changes().stream().anyMatch(f -> f.unmerged));

        // 解析冲突标记
        List<String> lines = Files.readAllLines(repo.resolve("f.txt"));
        var cf = ConflictParser.parse("f.txt", lines);
        assertTrue(cf.hasConflicts());
        assertEquals(1, cf.regions.size());
        assertEquals(List.of("main change"), cf.regions.get(0).ours);
        assertEquals(List.of("side change"), cf.regions.get(0).theirs);

        // ours 策略解决并标记
        List<String> resolved = ConflictParser.resolve(cf, List.of(0), "ours");
        Files.write(repo.resolve("f.txt"), resolved, StandardCharsets.UTF_8);
        svc.stage(List.of("f.txt"));
        svc.commit("merge side", false);
        st = NativeGit.status(repo);
        assertTrue(st.changes().isEmpty());
    }

    @Test
    void rewriteCommitMessageRewritesDescendants() throws Exception {
        Path repo = initRepo();
        JGitService svc = new JGitService(repo);
        Files.writeString(repo.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        svc.stageAll();
        String c1 = svc.commit("c1", false);
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        svc.stageAll();
        String c2 = svc.commit("c2", false);
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        svc.stageAll();
        String c3 = svc.commit("c3", false);

        String newHead = svc.rewriteCommitMessage(c2, "c2 rewritten\n\nbody line");
        assertTrue(newHead.matches("[0-9a-f]{40}"));
        assertNotEquals(c3, newHead, "后代提交 SHA 必须改变");

        List<CommitEntry> log = NativeGit.log(repo, 10, false, null);
        assertEquals(3, log.size());
        assertEquals("c3", log.get(0).subject, "最新提交仍是 c3,但 SHA 已变");
        assertEquals("c2 rewritten", log.get(1).subject);
        assertEquals("body line", log.get(1).body.strip());
        assertEquals("c1", log.get(2).subject);
        assertEquals(c1, log.get(2).id, "根提交不受影响");
        assertEquals(log.get(2).id, log.get(1).parents.get(0), "父链重新挂接正确");
        assertEquals(log.get(1).id, log.get(0).parents.get(0));
        assertEquals("Tester", log.get(1).author, "作者信息保留");

        assertEquals(List.of("one", "two", "three"), Files.readAllLines(repo.resolve("a.txt")));
        assertTrue(NativeGit.status(repo).changes().isEmpty(), "文件内容与索引不变");

        // 最新提交也可改(等价 amend)
        String head2 = svc.rewriteCommitMessage(log.get(0).id, "c3 renamed");
        assertEquals("c3 renamed", NativeGit.log(repo, 1, false, null).get(0).subject);
        assertTrue(NativeGit.status(repo).changes().isEmpty());
    }

    @Test
    void blameReturnsPerLineInfo() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("b.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c1", false);

        List<BlameLine> blame = NativeGit.blame(repo, "b.txt");
        assertEquals(2, blame.size());
        assertEquals("one", blame.get(0).content);
        assertEquals("Tester", blame.get(0).author);
        assertEquals("c1", blame.get(1).summary);
    }

    @Test
    void renameDetection() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("old.txt"), "data\n".repeat(10), StandardCharsets.UTF_8);
        new JGitService(repo).stageAll();
        new JGitService(repo).commit("c1", false);
        Files.move(repo.resolve("old.txt"), repo.resolve("new.txt"));
        new JGitService(repo).stageAll();

        List<DiffFile> stagedDiff = NativeGit.diffStaged(repo);
        assertEquals(1, stagedDiff.size());
        assertTrue(stagedDiff.get(0).renamed, "应检测到重命名");
        assertEquals("old.txt", stagedDiff.get(0).oldPath);
        assertEquals("new.txt", stagedDiff.get(0).newPath);
    }
}
