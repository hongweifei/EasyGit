package org.easygit.core;

import org.easygit.core.model.BlameLine;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.FileChange;
import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.StatusParser.StatusResult;
import org.easygit.core.LogParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生 git CLI 门面:status / log / diff / blame / 分支列表 / 网络操作。
 * 选择 CLI 的原因:大仓库性能好,且 fetch/push/pull 直接复用系统 git 的凭据管理器。
 */
public final class NativeGit {
    private NativeGit() {}

    // ---------- 状态 ----------

    public static StatusResult status(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo).exec(
                "status", "--porcelain=v2", "-z", "--branch", "--untracked-files=all");
        if (!r.ok()) throw new GitException("读取仓库状态失败:\n" + r.message());
        return StatusParser.parse(r.out());
    }

    // ---------- 历史 ----------

    public static List<CommitEntry> log(Path repo, int max, boolean all, String extraRef) {
        return log(repo, max, all, extraRef, null);
    }

    /** pathFilter 非空时只显示改动该文件的提交(git log -- <path>)。 */
    public static List<CommitEntry> log(Path repo, int max, boolean all, String extraRef, String pathFilter) {
        List<String> args = new ArrayList<>(List.of("log", "-z", "--date-order",
                "--max-count=" + max, LogParser.LOG_FORMAT));
        if (all) args.add("--all");
        if (extraRef != null && !extraRef.isBlank()) args.add(extraRef);
        if (pathFilter != null && !pathFilter.isBlank()) {
            args.add("--");
            args.add(pathFilter);
        }
        GitProcess.GitResult r = GitProcess.in(repo).exec(args.toArray(String[]::new));
        if (!r.ok()) throw new GitException("读取提交历史失败:\n" + r.message());
        return LogParser.parse(r.out());
    }

    // ---------- diff ----------

    public static List<DiffFile> diffUnstaged(Path repo) {
        return diffOf(repo, "diff", "--no-color", "--no-ext-diff", "-M", "-C");
    }

    public static List<DiffFile> diffUnstaged(Path repo, String path) {
        return diffOf(repo, "diff", "--no-color", "--no-ext-diff", "-M", "-C", "--", path);
    }

    public static List<DiffFile> diffStaged(Path repo) {
        return diffOf(repo, "diff", "--cached", "--no-color", "--no-ext-diff", "-M", "-C");
    }

    public static List<DiffFile> diffStaged(Path repo, String path) {
        return diffOf(repo, "diff", "--cached", "--no-color", "--no-ext-diff", "-M", "-C", "--", path);
    }

    public static List<DiffFile> diffCommit(Path repo, CommitEntry c) {
        if (c.parents.isEmpty()) {
            return diffOf(repo, "diff-tree", "-p", "--root", "--no-color", "-M", "-C", c.id);
        }
        return diffOf(repo, "diff", "--no-color", "--no-ext-diff", "-M", "-C",
                c.parents.get(0), c.id);
    }

    public static List<DiffFile> diffRange(Path repo, String from, String to) {
        return diffOf(repo, "diff", "--no-color", "--no-ext-diff", "-M", "-C", from, to);
    }

    private static List<DiffFile> diffOf(Path repo, String... args) {
        GitProcess.GitResult r = GitProcess.in(repo).exec(args);
        if (!r.ok()) throw new GitException("生成 diff 失败:\n" + r.message());
        return DiffParser.parse(r.out());
    }

    /** 工作区中未跟踪文件的原始内容(行列表)。 */
    public static List<String> localFileLines(Path repo, String relPath) {
        try {
            return Files.readAllLines(repo.resolve(relPath));
        } catch (Exception e) {
            return List.of("(无法读取文件: " + e.getMessage() + ")");
        }
    }

    /** 读取某次提交中的文件内容。 */
    public static String fileContentAt(Path repo, String rev, String relPath) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("show", rev + ":" + relPath);
        return r.ok() ? r.out() : "(读取失败)";
    }

    // ---------- blame ----------

    public static List<BlameLine> blame(Path repo, String relPath) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("blame", "--porcelain", "--", relPath);
        if (!r.ok()) throw new GitException("blame 失败:\n" + r.message());
        return BlameParser.parse(r.out());
    }

    // ---------- 分支 / 引用 ----------

    private static final String REF_FORMAT = "%(refname)%09%(objectname)%09%(upstream:short)%09"
            + "%(upstream:track,nobracket)%09%(HEAD)%09%(creatordate:unix)";

    public static List<BranchInfo> branches(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo).exec(
                "for-each-ref", "--format=" + REF_FORMAT,
                "refs/heads", "refs/remotes", "refs/tags");
        if (!r.ok()) throw new GitException("读取分支列表失败:\n" + r.message());
        List<BranchInfo> list = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            String[] t = line.split("\t", -1);
            if (t.length < 6) continue;
            String full = t[0];
            String tip = t[1];
            String upstream = t[2].isEmpty() ? null : t[2];
            String track = t[3];
            boolean current = t[4].contains("*");
            long time = parseLong(t[5]);
            if (full.startsWith("refs/heads/")) {
                list.add(new BranchInfo(BranchInfo.Kind.LOCAL, full.substring(11), full, tip, current, upstream, track, time));
            } else if (full.startsWith("refs/remotes/")) {
                String name = full.substring(13);
                if (name.endsWith("/HEAD")) continue; // 便利引用,不显示
                list.add(new BranchInfo(BranchInfo.Kind.REMOTE, name, full, tip, false, null, "", time));
            } else if (full.startsWith("refs/tags/")) {
                list.add(new BranchInfo(BranchInfo.Kind.TAG, full.substring(10), full, tip, false, null, "", time));
            }
        }
        return list;
    }

    public static Map<String, String> remotes(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("remote", "-v");
        Map<String, String> map = new LinkedHashMap<>();
        if (!r.ok()) return map;
        for (String line : r.out().split("\n")) {
            // name<TAB>url (fetch)
            String[] t = line.split("\t");
            if (t.length >= 2 && t[1].contains("(fetch)")) {
                map.put(t[0], t[1].replace("(fetch)", "").strip());
            }
        }
        return map;
    }

    // ---------- 远程管理 ----------

    public static GitProcess.GitResult remoteAdd(Path repo, String name, String url) {
        return GitProcess.in(repo).exec("remote", "add", name, url);
    }

    public static GitProcess.GitResult remoteRemove(Path repo, String name) {
        return GitProcess.in(repo).exec("remote", "remove", name);
    }

    public static GitProcess.GitResult remoteRename(Path repo, String oldName, String newName) {
        return GitProcess.in(repo).exec("remote", "rename", oldName, newName);
    }

    public static GitProcess.GitResult remoteSetUrl(Path repo, String name, String url) {
        return GitProcess.in(repo).exec("remote", "set-url", name, url);
    }

    // ---------- 网络操作(CLI,复用系统凭据管理器) ----------

    public static GitProcess.GitResult fetch(Path repo) {
        return GitProcess.in(repo).execNet("fetch", "--all", "--prune");
    }

    /** 只抓取某一个远程。 */
    public static GitProcess.GitResult fetchRemote(Path repo, String remote) {
        return GitProcess.in(repo).execNet("fetch", remote, "--prune");
    }

    public static GitProcess.GitResult pull(Path repo) {
        return GitProcess.in(repo).execNet("pull", "--no-edit");
    }

    public static GitProcess.GitResult push(Path repo, String branch, String upstream, boolean force) {
        if (upstream == null || upstream.isBlank()) {
            // 尚无上游:推送并设置 origin/<branch> 为上游
            if (branch == null || branch.isBlank()) return new GitProcess.GitResult(-1, "", "缺少分支名");
            List<String> args = new ArrayList<>(List.of("push", "-u", "origin", branch));
            if (force) args.add(1, "--force-with-lease");
            return GitProcess.in(repo).execNet(args.toArray(String[]::new));
        }
        List<String> args = new ArrayList<>(List.of("push", "origin", branch));
        if (force) args.add(1, "--force-with-lease");
        return GitProcess.in(repo).execNet(args.toArray(String[]::new));
    }

    /** 推送到指定远程,可选设置上游(--force-with-lease 更安全)。 */
    public static GitProcess.GitResult pushTo(Path repo, String branch, String remote, boolean setUpstream, boolean force) {
        if (branch == null || branch.isBlank() || remote == null || remote.isBlank()) {
            return new GitProcess.GitResult(-1, "", "缺少分支名或远程名");
        }
        List<String> args = new ArrayList<>(List.of("push"));
        if (force) args.add("--force-with-lease");
        if (setUpstream) args.add("-u");
        args.add(remote);
        args.add(branch);
        return GitProcess.in(repo).execNet(args.toArray(String[]::new));
    }

    /**
     * 更新一个未检出的本地分支:git fetch <remote> <remoteBranch>:<localBranch>,
     * 默认只允许快进(非快进会被 git 拒绝并返回错误)。
     */
    public static GitProcess.GitResult fetchBranch(Path repo, String remote, String remoteBranch, String localBranch) {
        return GitProcess.in(repo).execNet("fetch", remote, remoteBranch + ":" + localBranch);
    }

    public static GitProcess.GitResult clone(String url, Path targetDir) {
        return GitProcess.global("clone", "--progress", url, targetDir.toString());
    }

    public static GitProcess.GitResult init(Path targetDir) {
        return GitProcess.global("init", targetDir.toString());
    }

    public static GitProcess.GitResult mergeAbort(Path repo) {
        return GitProcess.in(repo).exec("merge", "--abort");
    }

    public static GitProcess.GitResult deleteTag(Path repo, String tagName) {
        return GitProcess.in(repo).exec("tag", "-d", tagName);
    }

    public static GitProcess.GitResult deleteRemoteBranch(Path repo, String remote, String branch) {
        return GitProcess.in(repo).execNet("push", remote, "--delete", branch);
    }

    // ---------- 标签推送 ----------

    public static GitProcess.GitResult pushTag(Path repo, String remote, String tag) {
        return GitProcess.in(repo).execNet("push", remote, "refs/tags/" + tag + ":refs/tags/" + tag);
    }

    public static GitProcess.GitResult pushAllTags(Path repo, String remote) {
        return GitProcess.in(repo).execNet("push", remote, "--tags");
    }

    public static GitProcess.GitResult deleteRemoteTag(Path repo, String remote, String tag) {
        return GitProcess.in(repo).execNet("push", remote, ":refs/tags/" + tag);
    }

    public static String headSha(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("rev-parse", "HEAD");
        return r.ok() ? r.out().strip() : "";
    }

    /**
     * 把当前分支重置到某个提交。
     * mode: mixed(默认,改动保留在工作区) / soft(改动保留在暂存区) / hard(彻底丢弃)。
     */
    public static GitProcess.GitResult reset(Path repo, String sha, String mode) {
        return GitProcess.in(repo).exec("reset", "--" + mode, sha);
    }

    /** 统计一个区间(如 "<sha>..HEAD")的提交数,失败返回 -1。 */
    public static int countRange(Path repo, String range) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("rev-list", "--count", range);
        if (!r.ok()) return -1;
        try {
            return Integer.parseInt(r.out().strip());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 列出一个区间(如 "origin/main..HEAD")内的提交 SHA,失败返回空列表。 */
    public static List<String> revListShas(Path repo, String range, int max) {
        GitProcess.GitResult r = GitProcess.in(repo).exec("rev-list", "--max-count=" + max, range);
        if (!r.ok()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            String s = line.strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ---------- git config ----------

    /** 读取作用域内的全部配置行("key=value")。global=false 时需要 repo。 */
    public static List<String> configList(boolean global, Path repo) {
        List<String> args = new ArrayList<>(List.of("config", "--list"));
        args.add(global ? "--global" : "--local");
        GitProcess.GitResult r = global ? GitProcess.global(args.toArray(String[]::new))
                : GitProcess.in(repo).exec(args.toArray(String[]::new));
        if (!r.ok()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (!line.isBlank()) out.add(line);
        }
        return out;
    }

    /** 读取合并后的生效值(含系统/全局/仓库)。 */
    public static String configGet(String key) {
        GitProcess.GitResult r = GitProcess.global("config", "--get", key);
        return r.ok() ? r.out().strip() : "";
    }

    public static GitProcess.GitResult configSet(boolean global, Path repo, String key, String value) {
        List<String> args = new ArrayList<>(List.of("config", global ? "--global" : "--local", key, value));
        return global ? GitProcess.global(args.toArray(String[]::new))
                : GitProcess.in(repo).exec(args.toArray(String[]::new));
    }

    public static GitProcess.GitResult configUnset(boolean global, Path repo, String key) {
        List<String> args = new ArrayList<>(List.of("config", global ? "--global" : "--local",
                "--unset-all", key));
        return global ? GitProcess.global(args.toArray(String[]::new))
                : GitProcess.in(repo).exec(args.toArray(String[]::new));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.strip()); } catch (Exception e) { return 0; }
    }

    public static final class GitException extends RuntimeException {
        public GitException(String msg) { super(msg); }
    }
}
