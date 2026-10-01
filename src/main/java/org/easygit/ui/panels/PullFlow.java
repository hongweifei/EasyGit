package org.easygit.ui.panels;

import org.easygit.core.GitProcess;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.model.PullPlan;
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;
import org.easygit.ui.dialogs.Dialogs;

import java.nio.file.Path;

/**
 * 拉取流程编排:先抓上游,再按「已是最新 / 可快进 / 已分叉」分流。
 *
 * 与界面解耦的部分全部在 {@link org.easygit.core.NativeGit} 和 {@link PullPlan};
 * 这里只负责"何时弹哪个框、状态栏说什么、何时刷新",通过 {@link Host} 回调宿主:
 *  - 与仓库/PullPlan 无关的纯逻辑可以脱离 JavaFX 单测;
 *  - 主窗口不必再背着这 150 行流程码。
 */
public final class PullFlow {

    /** 宿主窗口需要提供的能力。 */
    public interface Host {
        /** 刷新整仓(成功后调用)。 */
        void refreshAll();
        /** 切到「变更」页(冲突时需要用户去解决)。 */
        void showChangesTab();
    }

    private final Host host;

    public PullFlow(Host host) { this.host = host; }

    /**
     * 拉取入口。
     *
     * 以前直接 `git pull --no-edit`:本地没有提交时也会产生合并提交,分叉时没有任何选择,
     * 失败时只丢一段英文报错。现在:
     *  - 已是最新 → 不执行合并,直接提示;
     *  - 本地无提交、远端有 → --ff-only 快进,不产生合并提交;
     *  - 已分叉 → 让用户选「合并拉取 / 变基拉取」;
     *  - 被本地未提交改动挡住 → 询问后暂存、重试、自动恢复;
     *  - 冲突/网络/凭据等失败 → 中文提示 + 下一步建议。
     */
    public void pull() {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg("获取远程更新…", () -> NativeGit.pullPlan(repo), this::onPlan);
    }

    private void onPlan(PullPlan plan) {
        if (plan.error() != null) {
            Fx.error("拉取失败", NativeGit.friendlyError(plan.error()), plan.error());
            return;
        }
        if (plan.upstream() == null) {
            Fx.info("无法拉取", "当前分支还没有设置上游分支,不知道从哪里拉取。\n\n"
                    + "可先「推送」一次(会自动设置上游),或用「抓取」后手动合并。");
            return;
        }
        if (plan.conflicts() > 0) {
            Fx.info("先处理冲突", "当前已有 " + plan.conflicts() + " 个文件处于冲突状态。\n\n"
                    + "解决冲突或「中止合并」之后再拉取。");
            host.showChangesTab();
            return;
        }
        if (plan.behind() == 0) {
            Fx.status("拉取完成:已是最新");
            host.refreshAll();
            return;
        }
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        if (plan.ahead() == 0) {
            // 纯快进:本地没有额外提交,不该产生合并提交
            runStep(repo, "拉取中…", "拉取", () -> NativeGit.mergeUpstreamFfOnly(repo));
            return;
        }
        String mode = Dialogs.pullStrategy(plan.ahead(), plan.behind(), plan.dirty());
        if (mode == null) return;   // 用户取消
        boolean rebase = "rebase".equals(mode);
        String name = rebase ? "变基拉取" : "合并拉取";
        runStep(repo, name + "…", name,
                () -> rebase ? NativeGit.rebaseOntoUpstream(repo) : NativeGit.mergeUpstream(repo));
    }

    /** 执行合并/变基那一步,并把结果翻译成用户能懂的状态。 */
    private void runStep(Path repo, String busy, String name,
                         java.util.function.Supplier<GitProcess.GitResult> step) {
        Fx.bg(busy, () -> NativeGit.runPullStep(repo, step), res -> {
            GitProcess.GitResult r = res.result();
            UiLog.op("git " + name + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                reportDone(name, res.incoming(), res.conflicts());
                return;
            }
            if (NativeGit.isLocalObstruction(r.message())) {
                if (Fx.confirm(name + "被本地改动挡住",
                        "本地未提交的改动会被覆盖,git 拒绝了本次" + name + "。\n\n"
                                + "先暂存(stash)这些改动,重试" + name + ",成功后再自动恢复?")) {
                    retryWithStash(repo, name, step);
                }
                return;
            }
            String hint = NativeGit.friendlyError(r.message());
            if (hint.equals(r.message().strip())) {
                Fx.error(name + "失败", r.message(), null);
            } else {
                Fx.error(name + "失败", hint, r.message());
            }
        });
    }

    /** 暂存本地改动 → 重试拉取 → 自动恢复改动。 */
    private void retryWithStash(Path repo, String name,
                                java.util.function.Supplier<GitProcess.GitResult> step) {
        Fx.bg("暂存改动并重试…", () -> NativeGit.stashRetryPull(repo, name, step), res -> {
            if (res.stashError() != null) {
                Fx.error("暂存失败", NativeGit.friendlyError(res.stashError()), res.stashError());
                return;
            }
            if (res.pullError() != null) {
                Fx.error(name + "失败", NativeGit.friendlyError(res.pullError()), res.pullError());
                return;
            }
            UiLog.op("git 暂存→" + name + "→恢复 ✓", "", "");
            if (res.pop() != null && !res.pop().ok()) {
                UiLog.op("git stash pop ✖", res.pop().out(), res.pop().err());
                Fx.status(name + "完成,但恢复暂存改动时有冲突,请到「变更」页查看");
                host.showChangesTab();
                host.refreshAll();
                return;
            }
            reportDone(name, res.incoming(), res.conflicts());
            Fx.status("本地改动已恢复");
        });
    }

    /** 拉取成功后的状态栏文案 + 冲突引导。 */
    private void reportDone(String name, int incoming, int conflicts) {
        if (conflicts > 0) {
            Fx.status(name + "完成,但有 " + conflicts + " 个文件冲突,请到「变更」页解决");
            UiLog.line("⚠ " + name + " 产生 " + conflicts + " 个冲突文件");
            host.showChangesTab();
        } else if (incoming > 0) {
            Fx.status(name + "完成:新增 " + incoming + " 个提交");
        } else if (incoming == 0) {
            Fx.status(name + "完成:已是最新");
        } else {
            Fx.status(name + "完成");
        }
        host.refreshAll();
    }
}
