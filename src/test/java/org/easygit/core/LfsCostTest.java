package org.easygit.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LFS 检测的成本与超时行为(真实临时仓库)。
 *
 * 背景:`git lfs ls-files` 光 git-lfs 自身启动就要 ~1.6s(只有 1 个文件的仓库也一样),
 * 790MB 仓库冷启实测 14.9s;机器负载高时更久。它一度串在刷新阶段一里,启用 LFS 的仓库
 * 每次刷新都要等它。这里锁住三条:
 *  1) 不用 LFS 的仓库**一个进程都不起**(纯读 .gitattributes) —— 绝大多数仓库走这条;
 *  2) 超时必须按"数量未知"返回,且要快,不能把界面拖住;
 *  3) 一次超时**不能**把 jsonUnsupported 置真(否则一次抖动会把 JSON 路径永久关掉)。
 */
@Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LfsCostTest {

    private Path base, plain, lfsRepo;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-lfs-");
        plain = base.resolve("plain");
        lfsRepo = base.resolve("lfsrepo");
        initRepo(plain);
        initRepo(lfsRepo);
        // 声明使用 LFS(repoUsesLfs 只看根 .gitattributes,是纯文件读取)
        Files.writeString(lfsRepo.resolve(".gitattributes"), "*.bin filter=lfs diff=lfs merge=lfs -text\n");
        Files.writeString(lfsRepo.resolve("data.bin"), "not-really-lfs\n");
        commit(lfsRepo, "lfs-attrs");
        LfsService.resetCaches();
        LfsService.resetLsFilesBudget();
    }

    @AfterEach
    void tearDown() throws Exception {
        LfsService.resetLsFilesBudget();
        LfsService.resetCaches();
        if (base != null && Files.exists(base)) {
            try (var s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("repoUsesLfs 是纯文件读取:不起任何进程")
    void repoUsesLfsSpawnsNoProcess() {
        int before = GitProcess.execCount();
        assertTrue(LfsService.repoUsesLfs(lfsRepo), "根 .gitattributes 含 filter=lfs 应判定为使用 LFS");
        assertFalse(LfsService.repoUsesLfs(plain), "没有 .gitattributes 的仓库不该判定为使用 LFS");
        assertEquals(0, GitProcess.execCount() - before, "判定本身不能起 git 进程");
    }

    @Test
    @DisplayName("不用 LFS 的仓库:cachedRepoState 零进程、零成本")
    void plainRepoCostsNoProcess() {
        int before = GitProcess.execCount();
        int[] st = LfsService.cachedRepoState(plain);
        assertEquals(0, GitProcess.execCount() - before,
                "不用 LFS 的仓库刷新时不该起 lfs 进程(这正是绝大多数仓库的路径)");
        assertArrayEquals(new int[]{0, 0}, st);
    }

    @Test
    @DisplayName("ls-files 超时:按数量未知(-1)返回、很快返回、并且结果被缓存")
    void timeoutBecomesUnknownAndIsFast() {
        assumeTrue(LfsService.installed(), "本机没装 git-lfs,跳过");

        LfsService.setLsFilesBudget(Duration.ofMillis(1));
        long t0 = System.currentTimeMillis();
        int[] st = LfsService.cachedRepoState(lfsRepo);
        long ms = System.currentTimeMillis() - t0;

        assertEquals(1, st[0], "该仓库使用 LFS");
        assertEquals(-1, st[1], "超时时必须是 -1(数量未知),不能编造 0");
        assertTrue(ms < 5_000, "超时路径必须迅速返回,实测 " + ms + "ms");

        // 超时结果也要被缓存:否则 LFS 仓库每逢刷新都去撞一次 1.6s+ 的慢调用
        int before = GitProcess.execCount();
        long t1 = System.currentTimeMillis();
        int[] again = LfsService.cachedRepoState(lfsRepo);
        assertEquals(-1, again[1]);
        assertEquals(0, GitProcess.execCount() - before, "缓存命中时不该再起进程");
        assertTrue(System.currentTimeMillis() - t1 < 200, "缓存命中应该瞬时");
    }

    @Test
    @DisplayName("一次超时不能把 jsonUnsupported 置真(否则偶发抖动会永久关掉 JSON 路径)")
    void timeoutDoesNotPoisonJsonPath() {
        assumeTrue(LfsService.installed(), "本机没装 git-lfs,跳过");

        LfsService.setLsFilesBudget(Duration.ofMillis(1));
        assertFalse(LfsService.lsFilesChecked(lfsRepo).measured(), "1ms 预算下必然超时");

        // 换成充足预算重试:仍应走 --json 且量到结果(measured=true),而不是退化成"老版本不支持"
        LfsService.resetLsFilesBudget();
        LfsService.resetCaches();
        LfsService.LfsList l = LfsService.lsFilesChecked(lfsRepo);
        assertTrue(l.measured(), "超时不该污染 jsonUnsupported,重试必须还能量到结果");
        assertTrue(S_filesIsList(l), "结果应是列表");
    }

    private static boolean S_filesIsList(LfsService.LfsList l) {
        return l.files() != null;
    }

    @Test
    @DisplayName("充足预算 + 小仓库:能正常量到(夹具里那个 .bin 真的被 git-lfs 转成了指针)")
    void measuredOnSmallRepo() {
        assumeTrue(LfsService.installed(), "本机没装 git-lfs,跳过");
        LfsService.LfsList l = LfsService.lsFilesChecked(lfsRepo);
        assertTrue(l.measured(), "git-lfs 可用时小仓库应能正常读完");
        assertEquals(1, l.files().size(), "data.bin 命中 *.bin filter=lfs,add 时已被转成指针");
        assertTrue(l.files().get(0).path().endsWith("data.bin"),
                "解析出的路径应是 data.bin: " + l.files().get(0).path());
    }

    // ---------- 夹具 ----------

    private static void initRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "T");
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        commit(repo, "c1");
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
