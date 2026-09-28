package org.easygit.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git 可执行文件定位:优先使用 Git for Windows(Git Bash)安装,
 * 顺序:settings.gitPath 手动覆盖 → 注册表 InstallPath → 常见安装位置 → PATH。
 * 找到后把 Git 的 bin/usr\bin/cmd 前置到子进程 PATH,
 * 使钩子脚本、凭据管理器等行为与 Git Bash 环境一致。
 */
public final class GitLocator {
    private static String exe;                 // 已解析的 git 可执行文件
    private static List<Path> envDirs = List.of(); // 注入子进程 PATH 的目录

    private GitLocator() {}

    /** 当前是否为 Windows。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    public static synchronized void reset() {
        exe = null;
        envDirs = List.of();
    }

    public static synchronized String executable() {
        init();
        return exe;
    }

    /** 子进程 PATH 需要前置的目录(含分隔符);空串表示无需修改。 */
    public static synchronized String pathPrefix() {
        init();
        if (envDirs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path d : envDirs) {
            if (sb.length() > 0) sb.append(';');
            sb.append(d);
        }
        return sb.append(';').toString();
    }

    public static String describe() {
        return executable();
    }

    private static void init() {
        if (exe != null) return;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");

        // 1) 手动覆盖
        String configured = AppSettings.get().gitPath();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p) && works(p)) {
                accept(p, guessInstall(p));
                return;
            }
        }

        // 2) 注册表(Git for Windows 安装器写入)
        if (windows) {
            for (String key : List.of(
                    "HKLM\\SOFTWARE\\GitForWindows",
                    "HKCU\\SOFTWARE\\GitForWindows",
                    "HKLM\\SOFTWARE\\WOW6432Node\\GitForWindows")) {
                Path install = queryRegistryInstallPath(key);
                if (install != null && tryInstall(install)) return;
            }
        }

        // 3) 常见安装位置
        if (windows) {
            List<Path> candidates = new ArrayList<>();
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            String lad = System.getenv("LocalAppData");
            if (pf != null) candidates.add(Path.of(pf, "Git"));
            if (pf86 != null) candidates.add(Path.of(pf86, "Git"));
            if (lad != null) candidates.add(Path.of(lad, "Programs", "Git"));
            for (Path c : candidates) {
                if (tryInstall(c)) return;
            }
        } else {
            // macOS(Homebrew Intel/ARM、MacPorts)、Linux 常见位置
            for (String p : List.of(
                    "/opt/homebrew/bin/git",
                    "/usr/local/bin/git",
                    "/opt/local/bin/git",
                    "/usr/bin/git",
                    "/bin/git")) {
                Path g = Path.of(p);
                if (Files.isRegularFile(g) && works(g)) {
                    accept(g, guessInstall(g));
                    return;
                }
            }
        }

        // 4) PATH 上的 git
        String fromPath = findOnPath(windows);
        if (fromPath != null) {
            Path p = Path.of(fromPath);
            if (works(p)) {
                accept(p, guessInstall(p));
                return;
            }
        }

        // 5) 兜底:交给系统解析
        exe = "git";
    }

    private static boolean tryInstall(Path install) {
        if (install == null || !Files.isDirectory(install)) return false;
        for (Path rel : List.of(Path.of("cmd", "git.exe"), Path.of("bin", "git.exe"))) {
            Path g = install.resolve(rel);
            if (Files.isRegularFile(g) && works(g)) {
                accept(g, install);
                return true;
            }
        }
        return false;
    }

    private static void accept(Path exePath, Path install) {
        exe = exePath.toString();
        List<Path> dirs = new ArrayList<>();
        if (install != null) {
            for (Path rel : List.of(Path.of("usr", "bin"), Path.of("bin"), Path.of("cmd"), Path.of("mingw64", "bin"))) {
                Path d = install.resolve(rel);
                if (Files.isDirectory(d)) dirs.add(d);
            }
        } else {
            Path parent = exePath.getParent();
            if (parent != null) dirs.add(parent);
        }
        envDirs = dirs;
    }

    /** 从 exe 路径猜安装根(cmd\git.exe → 上级;bin\git.exe → 上级;裸路径 → 父目录)。 */
    private static Path guessInstall(Path exePath) {
        Path parent = exePath.getParent();
        if (parent == null) return null;
        String name = parent.getFileName() == null ? "" : parent.getFileName().toString().toLowerCase();
        if (name.equals("cmd") || name.equals("bin")) return parent.getParent();
        return parent;
    }

    private static Path queryRegistryInstallPath(String key) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", "InstallPath")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) >= 0) out.append(buf, 0, n);
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            for (String line : out.toString().split("\n")) {
                if (line.contains("InstallPath") && line.contains("REG_SZ")) {
                    String val = line.substring(line.indexOf("REG_SZ") + "REG_SZ".length()).strip();
                    if (!val.isEmpty() && Files.isDirectory(Path.of(val))) {
                        return Path.of(val);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String findOnPath(boolean windows) {
        try {
            Process p = new ProcessBuilder(windows ? "where" : "which", "git")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) >= 0) out.append(buf, 0, n);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            for (String line : out.toString().split("\n")) {
                String s = line.strip();
                if (!s.isEmpty() && Files.isRegularFile(Path.of(s))) return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean works(Path exePath) {
        try {
            Process p = new ProcessBuilder(exePath.toString(), "--version").start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
