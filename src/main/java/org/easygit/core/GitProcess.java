package org.easygit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * git 子进程封装。所有 CLI 调用统一走这里:UTF-8、超时、并发读取输出避免管道死锁。
 */
public final class GitProcess {

    /** 一次 git 命令的执行结果。 */
    public record GitResult(int code, String out, String err) {
        public boolean ok() { return code == 0; }
        /** 失败时的用户可读消息。 */
        public String message() {
            String e = err == null ? "" : err.strip();
            if (!e.isEmpty()) return e;
            return (out == null ? "" : out).strip();
        }
    }

    private final Path dir;

    private GitProcess(Path dir) { this.dir = dir; }

    public static GitProcess in(Path repoDir) { return new GitProcess(repoDir); }

    /**
     * 网络操作超时。GUI 里没有终端,凭据/主机密钥一旦需要交互就会一直等——
     * 所以网络操作统一「关掉子进程 stdin + 禁终端提示 + SSH BatchMode」,
     * 出不来就快速失败并给出中文提示,而不是挂到超时。
     */
    private static final Duration NET_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 累计 spawn 的 git 子进程数(性能诊断)。
     *
     * Windows 上 git 进程启动本身就要 100~200ms,刷新耗时基本由"起了几个进程"决定,
     * 所以优化刷新必须先能数清进程数。与 {@code Fx.scheduledTasks()} 同类的诊断计数,
     * 只给探针/排查用,不参与任何业务判断。
     */
    private static final java.util.concurrent.atomic.AtomicInteger EXEC_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 累计已 spawn 的 git 子进程数(诊断)。 */
    public static int execCount() { return EXEC_COUNT.get(); }

    /**
     * 一次 spawn 的记录(环形缓冲,诊断用):**属于哪个仓库、什么命令、起止时刻**。
     *
     * "为什么这一轮起了 4 个进程、第 4 个是谁"这类问题,光有计数答不了;连续切换仓库时
     * 还要能回答"这些进程里有多少属于用户只是路过的仓库""两条读取到底并行了没有"——
     * 所以记录里带上仓库路径与起止时刻(毫秒),探针/用例直接查这段,不用去猜调用点。
     */
    public static final class Run {
        /** 命令所在仓库(null 表示与仓库无关的全局命令)。 */
        public final String repo;
        public final String cmd;
        public final long startedAt;
        private volatile long finishedAt;

        Run(String repo, String cmd, long startedAt) {
            this.repo = repo;
            this.cmd = cmd;
            this.startedAt = startedAt;
        }

        /** 结束时刻;0 表示还在跑。 */
        public long finishedAt() { return finishedAt; }

        void finish() { finishedAt = System.currentTimeMillis(); }

        /** 两条命令的运行区间是否真的重叠(用来证明"并行取数"不是嘴上说的)。 */
        public boolean overlaps(Run other) {
            long aEnd = finishedAt == 0 ? Long.MAX_VALUE : finishedAt;
            long bEnd = other.finishedAt == 0 ? Long.MAX_VALUE : other.finishedAt;
            return startedAt < bEnd && other.startedAt < aEnd;
        }

        @Override public String toString() {
            long ms = finishedAt == 0 ? -1 : finishedAt - startedAt;
            return cmd + "  [" + (repo == null ? "全局" : repo) + "]  " + ms + "ms";
        }
    }

    private static final int RECENT_MAX = 64;
    private static final Run[] RECENT = new Run[RECENT_MAX];
    private static final java.util.concurrent.atomic.AtomicInteger RECENT_N =
            new java.util.concurrent.atomic.AtomicInteger();

    private static Run record(Path repoDir, List<String> args) {
        Run r = new Run(repoDir == null ? null : repoDir.toString(),
                String.join(" ", args), System.currentTimeMillis());
        RECENT[Math.floorMod(RECENT_N.getAndIncrement(), RECENT_MAX)] = r;
        return r;
    }

    /** 最近 spawn 的 git 命令(旧 → 新,最多 {@value #RECENT_MAX} 条)。 */
    public static List<Run> recent() {
        int n = Math.min(RECENT_N.get(), RECENT_MAX);
        List<Run> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Run r = RECENT[Math.floorMod(RECENT_N.get() - n + i, RECENT_MAX)];
            if (r != null) out.add(r);
        }
        return out;
    }

    /** 是否配置了 core.sshCommand(配置了就不覆盖用户的 ssh 命令)。 */
    private static final Map<String, Boolean> SSH_CMD_CONFIGURED = new java.util.concurrent.ConcurrentHashMap<>();

    /** 不依赖仓库的全局命令(clone 等)。 */
    public static GitResult global(String... args) {
        return new GitProcess(null).exec(Duration.ofMinutes(30), false, args);
    }

    /** 不依赖仓库的全局网络命令(clone 等)。 */
    public static GitResult globalNet(String... args) {
        return new GitProcess(null).exec(Duration.ofMinutes(30), true, args);
    }

    public GitResult exec(String... args) { return exec(Duration.ofMinutes(10), false, args); }

    /** 长时间网络操作(fetch/pull/push/lfs)。 */
    public GitResult execNet(String... args) { return exec(NET_TIMEOUT, true, args); }

    public GitResult execNet(Duration timeout, String... args) { return exec(timeout, true, args); }

    public GitResult exec(Duration timeout, String... args) { return exec(timeout, false, args); }

    public GitResult exec(Duration timeout, boolean net, String... args) {
        List<String> cmd = new ArrayList<>();
        // 优先使用 Git for Windows(Git Bash)的 git
        cmd.add(GitLocator.executable());
        // 统一解析行为,避免用户全局配置干扰
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.add("-c");
        cmd.add("color.ui=false");
        cmd.add("-c");
        cmd.add("i18n.logoutputencoding=utf-8");
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        if (net) applyNonInteractiveEnv(pb);
        // 注意:不要往 PATH 注入 Git 的 usr\bin 等目录 —— 实测会让 git-remote-https
        // 加载错误版本的 DLL,报 "remote helper 'https' aborted session"(退出码 128)。
        // git 自己会通过 exec-path 定位远程助手/钩子/凭据助手,无需我们干预。

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Process p = null;
        Run run = null;
        try {
            EXEC_COUNT.incrementAndGet();
            run = record(dir, List.of(args));
            p = pb.start();
            // 子进程 stdin 立刻收到 EOF:任何"读一行输入"的提示都会立即失败而不是永久阻塞。
            // (GUI 里没有终端,这是"拉取后界面卡住"那类问题的根因之一)
            try { p.getOutputStream().close(); } catch (Exception ignored) {}
            Process proc = p;
            Thread tOut = pump(proc, false, out);
            Thread tErr = pump(proc, true, err);
            if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return new GitResult(-1, out.toString(), "执行超时: git " + String.join(" ", args));
            }
            tOut.join(5000);
            tErr.join(5000);
            return new GitResult(proc.exitValue(), out.toString(), err.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new GitResult(-1, out.toString(), "已被取消");
        } catch (Exception e) {
            return new GitResult(-1, out.toString(), e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (run != null) run.finish();   // 诊断用:记下这一刻,连续切换的归因全靠它
        }
    }

    /**
     * 网络操作的非交互环境:
     * - GIT_TERMINAL_PROMPT=0:凭据管理器(GCM 等)照常弹窗,但禁止退化成"读终端"而卡死;
     * - SSH 加 BatchMode=yes:有口令的密钥、未确认的主机密钥立刻报错而不是等输入。
     * 用户自己设置过 GIT_SSH_COMMAND / GIT_SSH,或仓库配置了 core.sshCommand 时一律不动,
     * 避免覆盖他们的自定义 ssh。
     */
    private void applyNonInteractiveEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (env.containsKey("GIT_SSH_COMMAND") || env.containsKey("GIT_SSH")) return;
        if (dir != null && sshCommandConfigured(dir)) return;
        env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
    }

    private static boolean sshCommandConfigured(Path repoDir) {
        return SSH_CMD_CONFIGURED.computeIfAbsent(repoDir.toAbsolutePath().toString(), k -> {
            GitResult r = new GitProcess(repoDir).exec(Duration.ofSeconds(5), false,
                    "config", "--get", "core.sshCommand");
            return r.ok() && !r.out().isBlank();
        });
    }

    private Thread pump(Process proc, boolean isErr, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(isErr ? proc.getErrorStream() : proc.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) >= 0) sink.append(buf, 0, n);
            } catch (Exception ignored) {
            }
        }, isErr ? "git-stderr" : "git-stdout");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static boolean available() {
        return new GitProcess(null).exec(Duration.ofSeconds(5), "--version").ok();
    }

    public static String version() {
        GitResult r = new GitProcess(null).exec(Duration.ofSeconds(5), "--version");
        return r.ok() ? r.out().strip() : "git 不可用";
    }
}
