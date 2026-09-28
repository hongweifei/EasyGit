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

    /** 不依赖仓库的全局命令(clone 等)。 */
    public static GitResult global(String... args) {
        return new GitProcess(null).exec(Duration.ofMinutes(30), args);
    }

    public GitResult exec(String... args) { return exec(Duration.ofMinutes(10), args); }

    /** 长时间网络操作。 */
    public GitResult execNet(String... args) { return exec(Duration.ofMinutes(30), args); }

    public GitResult exec(Duration timeout, String... args) {
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
        // 注意:不要往 PATH 注入 Git 的 usr\bin 等目录 —— 实测会让 git-remote-https
        // 加载错误版本的 DLL,报 "remote helper 'https' aborted session"(退出码 128)。
        // git 自己会通过 exec-path 定位远程助手/钩子/凭据助手,无需我们干预。

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Process p = null;
        try {
            p = pb.start();
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
        }
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
