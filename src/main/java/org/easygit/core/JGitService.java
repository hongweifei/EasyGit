package org.easygit.core;

import org.easygit.core.model.StashEntry;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JGit 的本地 git 操作:暂存/提交/检出/分支/合并/stash/tag。
 * (网络操作走 NativeGit,以复用系统 git 凭据管理器。)
 */
public final class JGitService {

    public record MergeOutcome(String status, List<String> conflicts, String message) {}

    private final Path repoDir;

    public JGitService(Path repoDir) { this.repoDir = repoDir; }

    private Git open() {
        try {
            // 显式定位 .git(支持 worktree 的 .git 文件),避免 findGitDir 扫描到外层仓库
            java.io.File dir = repoDir.toFile();
            java.io.File dotGit = new java.io.File(dir, ".git");
            RepositoryBuilder builder = new RepositoryBuilder();
            if (dotGit.isFile()) {
                String first = Files.readAllLines(dotGit.toPath()).get(0);
                if (first.toLowerCase().startsWith("gitdir:")) {
                    String target = first.substring("gitdir:".length()).strip();
                    java.io.File gitDir = java.nio.file.Paths.get(target).isAbsolute()
                            ? new java.io.File(target)
                            : new java.io.File(dir, target);
                    builder.setGitDir(gitDir).setWorkTree(dir);
                } else {
                    throw new IOException("无法解析 .git 文件: " + first);
                }
            } else if (dotGit.isDirectory()) {
                builder.setGitDir(dotGit).setWorkTree(dir);
            } else {
                throw new IOException("不是 git 仓库: " + dir);
            }
            return new Git(builder.build());
        } catch (IOException e) {
            throw new NativeGit.GitException("打开仓库失败: " + e.getMessage());
        }
    }

    /**
     * LFS 仓库下 JGit 不执行 clean/smudge 过滤器,会破坏 LFS 内容;
     * 因此所有涉及工作区/索引写入的操作(stage/commit/checkout/merge/stash)改走 CLI
     * (CLI 已保证是 Git Bash 的 git,自带 git-lfs)。
     */
    private Boolean lfsRepo;
    private boolean lfsRepo() {
        if (lfsRepo == null) lfsRepo = LfsService.repoUsesLfs(repoDir);
        return lfsRepo;
    }

    // ---------- 暂存区 ----------

    public void stage(List<String> paths) {
        if (paths.isEmpty()) return;
        if (lfsRepo()) {
            List<String> args = new ArrayList<>(List.of("add", "--"));
            args.addAll(paths);
            GitProcess.GitResult r = GitProcess.in(repoDir).exec(args.toArray(String[]::new));
            if (!r.ok()) throw new NativeGit.GitException("暂存失败: " + r.message());
            return;
        }
        try (Git git = open()) {
            AddCommand add = git.add();
            for (String p : paths) add.addFilepattern(p);
            add.call();
        } catch (Exception e) {
            throw new NativeGit.GitException("暂存失败: " + e.getMessage());
        }
    }

    public void stageAll() {
        if (lfsRepo()) {
            GitProcess.GitResult r = GitProcess.in(repoDir).exec("add", "-A");
            if (!r.ok()) throw new NativeGit.GitException("暂存全部失败: " + r.message());
            return;
        }
        try (Git git = open()) {
            git.add().addFilepattern(".").call();
        } catch (Exception e) {
            throw new NativeGit.GitException("暂存全部失败: " + e.getMessage());
        }
    }

    public void unstage(List<String> paths) {
        if (paths.isEmpty()) return;
        try (Git git = open()) {
            ResetCommand reset = git.reset();
            for (String p : paths) reset.addPath(p);
            reset.call();
        } catch (Exception e) {
            throw new NativeGit.GitException("取消暂存失败: " + e.getMessage());
        }
    }

    /** 丢弃工作区改动(未暂存修改/删除);未跟踪文件直接删除。 */
    public void discard(List<String> paths, List<String> untracked) {
        try (Git git = open()) {
            if (untracked != null) {
                for (String p : untracked) {
                    Files.deleteIfExists(repoDir.resolve(p));
                }
            }
            if (paths != null && !paths.isEmpty()) {
                if (lfsRepo()) {
                    List<String> args = new ArrayList<>(List.of("checkout", "--"));
                    args.addAll(paths);
                    GitProcess.GitResult r = GitProcess.in(repoDir).exec(args.toArray(String[]::new));
                    if (!r.ok()) throw new NativeGit.GitException("丢弃改动失败: " + r.message());
                } else {
                    git.checkout().addPaths(paths).call();
                }
            }
        } catch (NativeGit.GitException e) {
            throw e;
        } catch (Exception e) {
            throw new NativeGit.GitException("丢弃改动失败: " + e.getMessage());
        }
    }

    // ---------- 提交 ----------

    public String commit(String message, boolean amend) {
        if (message == null || message.isBlank()) {
            throw new NativeGit.GitException("提交说明不能为空");
        }
        if (lfsRepo()) {
            List<String> args = new ArrayList<>(List.of("commit", "-m", message.strip()));
            if (amend) args.add("--amend");
            GitProcess.GitResult r = GitProcess.in(repoDir).exec(args.toArray(String[]::new));
            if (!r.ok()) throw new NativeGit.GitException("提交失败: " + r.message());
            String sha = NativeGit.headSha(repoDir);
            if (sha.isEmpty()) throw new NativeGit.GitException("提交后无法读取 HEAD");
            return sha;
        }
        try (Git git = open()) {
            RevCommit c = git.commit()
                    .setMessage(message.strip())
                    .setAmend(amend)
                    .call();
            return c.getId().getName();
        } catch (Exception e) {
            throw new NativeGit.GitException("提交失败: " + e.getMessage());
        }
    }

    // ---------- 检出 / 分支 ----------

    public void checkout(String name, boolean createBranch, String startPoint) {
        if (lfsRepo()) {
            List<String> args = new ArrayList<>(List.of("checkout"));
            if (createBranch) args.add("-b");
            args.add(name);
            if (createBranch && startPoint != null && !startPoint.isBlank()) args.add(startPoint);
            GitProcess.GitResult r = GitProcess.in(repoDir).execNet(args.toArray(String[]::new));
            if (!r.ok()) throw new NativeGit.GitException("检出失败: " + r.message());
            return;
        }
        try (Git git = open()) {
            CheckoutCommand cc = git.checkout().setName(name);
            if (createBranch) {
                cc.setCreateBranch(true)
                        .setName(name)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .setStartPoint(startPoint == null ? null : startPoint);
            }
            cc.call();
        } catch (Exception e) {
            throw new NativeGit.GitException("检出失败: " + e.getMessage());
        }
    }

    public void checkoutCommit(String sha) {
        if (lfsRepo()) {
            GitProcess.GitResult r = GitProcess.in(repoDir).execNet("checkout", sha);
            if (!r.ok()) throw new NativeGit.GitException("检出提交失败: " + r.message());
            return;
        }
        try (Git git = open()) {
            git.checkout().setName(sha).call();
        } catch (Exception e) {
            throw new NativeGit.GitException("检出提交失败: " + e.getMessage());
        }
    }

    public void createBranch(String name, String startPoint) {
        try (Git git = open()) {
            git.branchCreate().setName(name)
                    .setStartPoint(startPoint)
                    .setForce(false)
                    .call();
        } catch (Exception e) {
            throw new NativeGit.GitException("创建分支失败: " + e.getMessage());
        }
    }

    public void deleteBranch(String name, boolean force) {
        try (Git git = open()) {
            git.branchDelete().setBranchNames(name).setForce(force).call();
        } catch (Exception e) {
            throw new NativeGit.GitException("删除分支失败: " + e.getMessage());
        }
    }

    public void renameBranch(String oldName, String newName) {
        try (Git git = open()) {
            git.branchRename().setOldName(oldName).setNewName(newName).call();
        } catch (Exception e) {
            throw new NativeGit.GitException("重命名分支失败: " + e.getMessage());
        }
    }

    public String resolve(String rev) {
        try (Git git = open()) {
            ObjectId id = git.getRepository().resolve(rev);
            return id == null ? null : id.getName();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 合并 ----------

    public MergeOutcome merge(String refName) {
        if (lfsRepo()) {
            // CLI 合并(JGit 不做 LFS smudge);冲突通过 status 未合并文件判断
            String before = NativeGit.headSha(repoDir);
            GitProcess.GitResult r = GitProcess.in(repoDir).execNet("merge", refName);
            StatusParser.StatusResult st = StatusParser.parse(
                    GitProcess.in(repoDir).exec("status", "--porcelain=v2", "-z", "--untracked-files=all").out());
            List<String> conflicts = st.changes().stream()
                    .filter(f -> f.unmerged).map(f -> f.path).toList();
            if (!conflicts.isEmpty()) {
                return new MergeOutcome("CONFLICTING", conflicts, "合并存在 " + conflicts.size() + " 个冲突文件");
            }
            if (!r.ok()) throw new NativeGit.GitException("合并失败: " + r.message());
            String after = NativeGit.headSha(repoDir);
            if (before.equals(after)) {
                return new MergeOutcome("ALREADY_UP_TO_DATE", List.of(), "已是最新的,无需合并");
            }
            return new MergeOutcome("MERGED", List.of(), "合并完成");
        }
        try (Git git = open()) {
            Repository repo = git.getRepository();
            ObjectId id = repo.resolve(refName);
            if (id == null) throw new NativeGit.GitException("找不到引用: " + refName);
            MergeResult result = git.merge()
                    .include(id)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .call();
            List<String> conflicts = new ArrayList<>();
            if (result.getConflicts() != null) {
                conflicts.addAll(result.getConflicts().keySet());
            }
            String msg = switch (result.getMergeStatus()) {
                case FAST_FORWARD -> "快进合并完成";
                case ALREADY_UP_TO_DATE -> "已是最新的,无需合并";
                case MERGED -> "合并完成";
                case CONFLICTING -> "合并存在 " + conflicts.size() + " 个冲突文件";
                case FAILED -> "合并失败: " + result.getFailingPaths();
                default -> result.getMergeStatus().toString();
            };
            return new MergeOutcome(result.getMergeStatus().name(), conflicts, msg);
        } catch (NativeGit.GitException e) {
            throw e;
        } catch (Exception e) {
            throw new NativeGit.GitException("合并失败: " + e.getMessage());
        }
    }

    // ---------- Stash ----------

    public String stash(String message, boolean includeUntracked) {
        if (lfsRepo()) {
            List<String> args = new ArrayList<>(List.of("stash", "push"));
            if (includeUntracked) args.add("-u");
            if (message != null && !message.isBlank()) {
                args.add("-m");
                args.add(message.strip());
            }
            GitProcess.GitResult r = GitProcess.in(repoDir).exec(args.toArray(String[]::new));
            if (!r.ok()) throw new NativeGit.GitException("创建 stash 失败: " + r.message());
            if (r.out().contains("No local changes to save")) return null;
            GitProcess.GitResult sha = GitProcess.in(repoDir).exec("rev-parse", "-q", "--verify", "refs/stash");
            return sha.ok() ? sha.out().strip() : "";
        }
        try (Git git = open()) {
            var sc = git.stashCreate().setIncludeUntracked(includeUntracked);
            if (message != null && !message.isBlank()) {
                sc.setWorkingDirectoryMessage(message.strip());
                sc.setIndexMessage(message.strip());
            }
            RevCommit c = sc.call();
            return c == null ? null : c.getId().getName();
        } catch (Exception e) {
            throw new NativeGit.GitException("创建 stash 失败: " + e.getMessage());
        }
    }

    public List<StashEntry> stashList() {
        try (Git git = open()) {
            List<StashEntry> list = new ArrayList<>();
            int i = 0;
            for (RevCommit c : git.stashList().call()) {
                String msg = c.getShortMessage();
                list.add(new StashEntry(i, c.getId().getName(), c.getCommitTime(), msg));
                i++;
            }
            return list;
        } catch (Exception e) {
            throw new NativeGit.GitException("读取 stash 列表失败: " + e.getMessage());
        }
    }

    public void stashApply(int index) {
        if (lfsRepo()) {
            GitProcess.GitResult r = GitProcess.in(repoDir).execNet("stash", "apply", "stash@{" + index + "}");
            if (!r.ok()) throw new NativeGit.GitException("应用 stash 失败: " + r.message());
            return;
        }
        try (Git git = open()) {
            git.stashApply().setStashRef("stash@{" + index + "}").call();
        } catch (Exception e) {
            throw new NativeGit.GitException("应用 stash 失败: " + e.getMessage());
        }
    }

    public void stashDrop(int index) {
        try (Git git = open()) {
            git.stashDrop().setStashRef(index).call();
        } catch (Exception e) {
            throw new NativeGit.GitException("删除 stash 失败: " + e.getMessage());
        }
    }

    // ---------- Tag ----------

    public void createTag(String name, String targetSha, String message) {
        try (Git git = open()) {
            var tc = git.tag().setName(name);
            if (targetSha != null && !targetSha.isBlank()) {
                ObjectId id = git.getRepository().resolve(targetSha);
                if (id != null) {
                    try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                        tc.setObjectId(walk.parseAny(id));
                    }
                }
            }
            if (message != null && !message.isBlank()) tc.setMessage(message.strip());
            tc.call();
        } catch (Exception e) {
            throw new NativeGit.GitException("创建标签失败: " + e.getMessage());
        }
    }

    // ---------- 历史改写(修改提交消息) ----------

    /**
     * 修改 targetSha 的提交消息,并改写其后代提交(重新挂父链)。
     * 树对象全部保持不变 —— 文件内容零变化,不要求工作区干净。
     * 作者信息保留,提交者(committer)变为当前用户/当前时间(与 git rebase 一致)。
     *
     * @return 新的 HEAD sha
     */
    public String rewriteCommitMessage(String targetSha, String newMessage) {
        if (newMessage == null || newMessage.isBlank()) {
            throw new NativeGit.GitException("提交消息不能为空");
        }
        // 1. 目标必须是当前分支 HEAD 的祖先
        GitProcess.GitResult anc = GitProcess.in(repoDir).exec("merge-base", "--is-ancestor", targetSha, "HEAD");
        if (!anc.ok()) {
            throw new NativeGit.GitException("该提交不在当前分支的历史上,无法改写");
        }
        try (Git git = open()) {
            Repository repo = git.getRepository();
            ObjectId headId = repo.resolve("HEAD");
            ObjectId targetId = repo.resolve(targetSha);
            if (headId == null) throw new NativeGit.GitException("仓库还没有提交");
            if (targetId == null) throw new NativeGit.GitException("找不到提交 " + targetSha);

            // 2. 收集 target..HEAD(含 target),拓扑序从旧到新
            List<RevCommit> seq = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit head = walk.parseCommit(headId);
                RevCommit target = walk.parseCommit(targetId);
                walk.markStart(head);
                for (RevCommit p : target.getParents()) {
                    walk.markUninteresting(walk.parseCommit(p));
                }
                walk.sort(RevSort.TOPO);
                for (RevCommit c : walk) seq.add(c);
                Collections.reverse(seq);
            }

            // 3. 从旧到新重建提交链:同树、映射后的父、原作者;目标提交换消息
            Map<ObjectId, ObjectId> map = new HashMap<>();
            ObjectId newHead = null;
            PersonIdent committer = new PersonIdent(repo);
            try (ObjectInserter ins = repo.newObjectInserter()) {
                for (RevCommit c : seq) {
                    CommitBuilder cb = new CommitBuilder();
                    cb.setTreeId(c.getTree());
                    for (RevCommit p : c.getParents()) {
                        ObjectId mapped = map.get(p.getId());
                        cb.addParentId(mapped != null ? mapped : p.getId());
                    }
                    cb.setAuthor(c.getAuthorIdent());
                    cb.setCommitter(committer);
                    cb.setMessage(c.getId().equals(targetId)
                            ? newMessage.strip()
                            : c.getFullMessage());
                    ObjectId id = ins.insert(cb);
                    map.put(c.getId(), id);
                    newHead = id;
                }
                ins.flush();
            }

            // 4. 更新分支引用(非快进,强制)
            Ref head = repo.getRefDatabase().findRef("HEAD");
            String refName = head.isSymbolic() ? head.getTarget().getName() : "HEAD";
            RefUpdate ru = repo.updateRef(refName);
            ru.setNewObjectId(newHead);
            ru.setRefLogMessage("EasyGit: 修改提交消息", false);
            RefUpdate.Result res = ru.forceUpdate();
            if (res != RefUpdate.Result.NEW && res != RefUpdate.Result.FORCED
                    && res != RefUpdate.Result.FAST_FORWARD) {
                throw new NativeGit.GitException("更新分支引用失败: " + res);
            }
            return newHead.getName();
        } catch (NativeGit.GitException e) {
            throw e;
        } catch (Exception e) {
            throw new NativeGit.GitException("改写提交历史失败: " + e.getMessage());
        }
    }
}
