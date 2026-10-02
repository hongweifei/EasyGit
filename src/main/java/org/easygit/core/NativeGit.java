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

    // ---------- 拉取管线:先抓取上游,再按"已最新 / 可快进 / 已分叉"分流 ----------

    /** 当前分支的上游(如 origin/main);游离 HEAD 或未设置上游返回 null。 */
    public static String upstream(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo)
                .exec("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        if (!r.ok()) return null;
        String s = r.out().strip();
        return s.isEmpty() || s.contains("@{u}") ? null : s;
    }

    /**
     * 相对上游的提交数,返回 {领先, 落后}。
     * rev-list --left-right @{u}...HEAD 输出「落后&lt;TAB&gt;领先」,取不到时返回 {0,0}。
     */
    public static int[] aheadBehind(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo)
                .exec("rev-list", "--count", "--left-right", "@{u}...HEAD");
        if (!r.ok()) return new int[]{0, 0};
        String[] t = r.out().strip().split("\\s+");
        if (t.length < 2) return new int[]{0, 0};
        try {
            return new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[0])};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    /** 只抓当前分支上游所在的远程(--prune),比 fetch --all 快且不动其他远程。 */
    public static GitProcess.GitResult fetchUpstream(Path repo) {
        String up = upstream(repo);
        if (up == null) return new GitProcess.GitResult(-1, "", "当前分支没有设置上游分支");
        int i = up.indexOf('/');
        if (i <= 0 || i == up.length() - 1) {
            return new GitProcess.GitResult(-1, "", "上游 " + up + " 不是「远程/分支」形式,无法抓取");
        }
        return fetchRemote(repo, up.substring(0, i));
    }

    /** 快进到上游(本地没有额外提交时使用,不产生合并提交)。 */
    public static GitProcess.GitResult mergeUpstreamFfOnly(Path repo) {
        return GitProcess.in(repo).exec("merge", "--ff-only", "@{u}");
    }

    /** 合并上游(分叉时选择"合并拉取",产生合并提交)。 */
    public static GitProcess.GitResult mergeUpstream(Path repo) {
        return GitProcess.in(repo).exec("merge", "--no-edit", "@{u}");
    }

    /** 变基到上游(分叉时选择"变基拉取",本地提交重放、历史保持线性)。 */
    public static GitProcess.GitResult rebaseOntoUpstream(Path repo) {
        return GitProcess.in(repo).exec("rebase", "@{u}");
    }

    /** 冲突文件数(未解决)。取不到状态时返回 0。 */
    public static int unmergedCount(Path repo) {
        try {
            return (int) status(repo).changes().stream().filter(f -> f.unmerged).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 工作区是否有未提交改动(含未跟踪)。 */
    public static boolean dirty(Path repo) {
        try {
            return !status(repo).changes().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 暂存工作区改动(含未跟踪),拉取被本地改动挡住时用。 */
    public static GitProcess.GitResult stashPush(Path repo, String message) {
        return GitProcess.in(repo).exec("stash", "push", "-u", "-m", message);
    }

    public static GitProcess.GitResult stashPop(Path repo) {
        return GitProcess.in(repo).exec("stash", "pop");
    }

    /** 中止进行中的合并/变基/拣选(按仓库当前状态挑命令)。 */
    public static GitProcess.GitResult abortInProgress(Path repo) {
        Path g = repo.resolve(".git");
        if (java.nio.file.Files.isDirectory(g.resolve("rebase-merge"))
                || java.nio.file.Files.isDirectory(g.resolve("rebase-apply"))) {
            return GitProcess.in(repo).exec("rebase", "--abort");
        }
        if (java.nio.file.Files.exists(g.resolve("CHERRY_PICK_HEAD"))) {
            return GitProcess.in(repo).exec("cherry-pick", "--abort");
        }
        return mergeAbort(repo);
    }

    /** 这类报错说明"等一下重试也没用",适合弹对话框;网络类则适合提示后重试。 */
    public static boolean isLocalObstruction(String message) {
        String m = lower(message);
        return m.contains("your local changes") || m.contains("would be overwritten")
                || m.contains("please commit your changes") || m.contains("local changes to the following files");
    }

    /**
     * 把 git 的英文报错归一成中文提示 + 下一步建议。
     * 只做归类,不吞原始输出(调用方把原文放进错误框的详情里)。
     */
    public static String friendlyError(String message) {
        String m = message == null ? "" : message;
        String low = lower(m);
        if (low.contains("could not read username") || low.contains("terminal prompts disabled")
                || low.contains("authentication failed") || low.contains("invalid username or password")
                || low.contains("http 401") || low.contains("http 403")) {
            return "远程要登录才能访问,而界面里不能输入账号密码。\n"
                    + "先在终端里对同一个远程执行一次 git pull,让凭据管理器记住凭据,再回来重试。";
        }
        if (low.contains("permission denied (publickey)") || low.contains("no supported authentication")) {
            return "SSH 公钥认证失败(界面里无法输入口令)。\n"
                    + "确认 ssh-agent 已加载密钥;带口令的密钥请先执行 ssh-add。";
        }
        if (low.contains("host key verification failed") || low.contains("remote host identification has changed")) {
            return "SSH 主机密钥未确认。\n先在终端执行一次 ssh -T <主机> 接受主机密钥。";
        }
        if (low.contains("could not resolve host") || low.contains("unable to access")
                || low.contains("connection refused") || low.contains("failed to connect")
                || low.contains("connection timed out") || low.contains("network is unreachable")) {
            return "连不上远程(网络 / 代理 / 地址问题)。\n检查网络与代理设置,或稍后重试。";
        }
        if (low.contains("no tracking information") || low.contains("no upstream")) {
            return "当前分支还没有上游分支,不知道从哪里拉取。\n"
                    + "可先「推送」一次(会自动设置上游),或抓取后手动合并。";
        }
        if (low.contains("your local changes") || low.contains("would be overwritten")
                || low.contains("please commit your changes")) {
            return "本地未提交的改动会被覆盖,git 拒绝了本次拉取。\n可先提交,或让 EasyGit 暂存改动后重试。";
        }
        if (low.contains("stale info")) {
            return "本地对远端的记录已过期(别人可能已推送),git 拒绝了这次强制推送。\n"
                    + "先「抓取」刷新远端状态,再重新推送。";
        }
        if (low.contains("[rejected]") || low.contains("non-fast-forward")
                || low.contains("fetch first") || low.contains("behind its remote counterpart")) {
            return "推送被拒绝:远端已有别人推送的新提交,直接覆盖会丢掉他们的提交。\n"
                    + "建议先「拉取」合并后再推送;确实要覆盖远端时,用推送对话框里的强制推送(--force-with-lease)。";
        }
        if (low.contains("pre-receive hook declined") || low.contains("hook declined")) {
            return "远端服务器的钩子(hook)拒绝了这次推送。\n"
                    + "通常是分支保护规则或代码检查没通过,详情见远端平台的说明。";
        }
        if (low.contains("protected branch") || low.contains("not permitted")
                || low.contains("permission to") || low.contains("insufficient permission")) {
            return "没有推送权限(分支受保护或账号受限)。\n到远端平台确认分支保护规则与你账号的权限。";
        }
        if (low.contains("not possible to fast-forward") || low.contains("non-fast-forward")
                || low.contains("divergent branches") || low.contains("have diverged")) {
            return "本地与远端各有新提交(已分叉),不能快进。\n用「合并拉取」或「变基拉取」都可以继续。";
        }
        if (low.contains("refusing to merge unrelated histories")) {
            return "两个历史没有共同祖先,git 拒绝合并。\n确认远程地址是否换成了另一个项目的仓库。";
        }
        return m.strip().isEmpty() ? "未知错误" : m.strip();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    // ---------- 拉取流程的数据类型与编排(与界面无关,可无头测试) ----------

    /** 拉取那一步的结果(含冲突数,便于给出"去变更页解决"的引导)。 */
    public record PullStep(GitProcess.GitResult result, int incoming, int conflicts) {}

    /** 暂存后重试的结果。stashError / pullError 非空表示对应步骤失败。 */
    public record StashRetry(String stashError, String pullError, GitProcess.GitResult pop,
                             int incoming, int conflicts) {}

    /** 抓取上游并把仓库现状整理成拉取分流依据。 */
    public static org.easygit.core.model.PullPlan pullPlan(Path repo) {
        String up = upstream(repo);
        if (up == null) return org.easygit.core.model.PullPlan.noUpstream();
        GitProcess.GitResult f = fetchUpstream(repo);
        if (!f.ok()) return org.easygit.core.model.PullPlan.failed(f.message());
        int[] ab = aheadBehind(repo);
        return new org.easygit.core.model.PullPlan(up, ab[0], ab[1], unmergedCount(repo), dirty(repo), null);
    }

    /** 跑一步拉取动作(合并/变基/快进),顺便量出新增了几个提交、有没有产生冲突。 */
    public static PullStep runPullStep(Path repo, java.util.function.Supplier<GitProcess.GitResult> step) {
        String headBefore = headSha(repo);
        GitProcess.GitResult r = step.get();
        int incoming = r.ok() && headBefore != null && !headBefore.isBlank()
                ? countRange(repo, headBefore + "..HEAD") : -1;
        return new PullStep(r, incoming, unmergedCount(repo));
    }

    /** 暂存本地改动 → 重试拉取 → 无论成败都恢复改动。 */
    public static StashRetry stashRetryPull(Path repo, String name,
                                            java.util.function.Supplier<GitProcess.GitResult> step) {
        GitProcess.GitResult stash = stashPush(repo, "EasyGit 自动暂存(" + name + ")");
        if (!stash.ok()) return new StashRetry(stash.message(), null, null, -1, 0);
        PullStep step1 = runPullStep(repo, step);
        GitProcess.GitResult pop = stashPop(repo);   // 成败都要恢复本地改动
        return new StashRetry(null, step1.result().ok() ? null : step1.result().message(),
                pop, step1.incoming(), step1.conflicts());
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

    // ---------- 推送流程的数据类型与编排(与界面无关,可无头测试) ----------

    /** 推送那一步的结果(pushed = 这次实际推上去的提交数,取不到为 -1)。 */
    public record PushStep(GitProcess.GitResult result, int pushed) {}

    /** 指定分支的上游(如 origin/main);未设置返回 null。与 HEAD 无关,任意分支都能查。 */
    public static String upstreamOf(Path repo, String branch) {
        GitProcess.GitResult r = GitProcess.in(repo)
                .exec("rev-parse", "--abbrev-ref", "--symbolic-full-name", branch + "@{u}");
        if (!r.ok()) return null;
        String s = r.out().strip();
        return s.isEmpty() || s.contains("@{u}") ? null : s;
    }

    /** 指定分支相对其上游的提交数,返回 {领先, 落后}。取不到时返回 {0,0}。 */
    public static int[] aheadBehindOf(Path repo, String branch, String upstream) {
        GitProcess.GitResult r = GitProcess.in(repo)
                .exec("rev-list", "--count", "--left-right", upstream + "..." + branch);
        if (!r.ok()) return new int[]{0, 0};
        String[] t = r.out().strip().split("\\s+");
        if (t.length < 2) return new int[]{0, 0};
        try {
            return new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[0])};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    /** 推送前置探测:分支相对上游的领先/落后(基于上次 fetch 的本地引用,无网络操作)。 */
    public static org.easygit.core.model.PushPlan pushPlan(Path repo, String branch) {
        String up = upstreamOf(repo, branch);
        if (up == null) return new org.easygit.core.model.PushPlan(branch, null, 0, 0);
        int[] ab = aheadBehindOf(repo, branch, up);
        return new org.easygit.core.model.PushPlan(branch, up, ab[0], ab[1]);
    }

    /** 跑一步推送,顺便量出这次实际推上去的提交数。 */
    public static PushStep runPushStep(Path repo, String branch, String remote,
                                       boolean setUpstream, boolean force) {
        GitProcess.GitResult up = GitProcess.in(repo)
                .exec("rev-parse", "--verify", "--quiet", branch + "@{u}");
        String oldUp = up.ok() ? up.out().strip() : null;
        GitProcess.GitResult r = pushTo(repo, branch, remote, setUpstream, force);
        int pushed = r.ok() ? (oldUp == null ? 0 : countRange(repo, oldUp + ".." + branch)) : -1;
        return new PushStep(r, pushed);
    }

    /** 推送被远端拒绝(非快进:远端有别人推的新提交)。注意凭据类失败没有这些标记。 */
    public static boolean isPushRejected(String message) {
        String m = lower(message);
        return m.contains("[rejected]") || m.contains("non-fast-forward")
                || m.contains("fetch first") || m.contains("behind its remote counterpart");
    }

    /**
     * 更新一个未检出的本地分支:git fetch <remote> <remoteBranch>:<localBranch>,
     * 默认只允许快进(非快进会被 git 拒绝并返回错误)。
     */
    public static GitProcess.GitResult fetchBranch(Path repo, String remote, String remoteBranch, String localBranch) {
        return GitProcess.in(repo).execNet("fetch", remote, remoteBranch + ":" + localBranch);
    }

    public static GitProcess.GitResult clone(String url, Path targetDir) {
        return GitProcess.globalNet("clone", "--progress", url, targetDir.toString());
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
     * 所有引用的指纹(HEAD + 本地/远程分支 + 标签)。
     *
     * 轮询只比对 HEAD 是不够的:勾选「所有分支」时,历史页要显示全部分支的提交,
     * 而侧支分支、远程跟踪分支出现新提交并不会移动 HEAD —— 于是历史页永远不刷新,
     * 用户必须手动点刷新(2026-10-01 反馈的实际现象)。
     *
     * 用 for-each-ref 一次拿到全部引用,拼成一个字符串当指纹:比逐个 rev-parse 省进程,
     * 内容变化(新增/移动/删除引用)都能反映出来。
     */
    public static String refsFingerprint(Path repo) {
        GitProcess.GitResult r = GitProcess.in(repo)
                .exec("for-each-ref", "--format=%(objectname) %(refname)");
        if (!r.ok()) return headSha(repo);   // 取不到引用时退回只盯 HEAD,至少不比原来差
        return r.out();
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

    /** 当前分支尚未推送到上游的提交 SHA 集合(无上游/失败时为空集)。 */
    public static java.util.Set<String> unpushedShas(Path repo, int max) {
        try {
            GitProcess.GitResult up = GitProcess.in(repo).exec("rev-parse", "--abbrev-ref",
                    "--symbolic-full-name", "@{upstream}");
            if (up.ok() && !up.out().isBlank()) {
                return java.util.Set.copyOf(revListShas(repo, up.out().strip() + "..HEAD", max));
            }
        } catch (Exception ignored) {
        }
        return java.util.Set.of();
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
