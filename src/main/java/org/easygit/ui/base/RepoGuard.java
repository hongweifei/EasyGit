package org.easygit.ui;

import org.easygit.core.RepoManager;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 仓库切换守卫:连续快速切换仓库时,旧仓库的在途异步任务必须整段作废。
 *
 * 不做守卫会同时出三类问题(连点仓库列表就能复现):
 * <ol>
 *   <li><b>串仓</b>:旧仓库的 status/log 结果晚到,回投 UI 后覆盖新仓库的界面——
 *       状态栏显示 A 的路径与分支、变更清单是 A 的文件、历史列表是 A 的提交;</li>
 *   <li><b>积压</b>:每次切换都排 2 个 git 子进程刷新,连点数次就堆出一串注定被丢弃的任务,
 *       占满 Fx 的 4 个后台线程,新仓库的首屏数据被排在后面;</li>
 *   <li><b>误报</b>:旧仓库的任务报错后弹出与新仓库无关的"操作失败"对话框。</li>
 * </ol>
 *
 * 守卫因此在三个位置各拦一次:排队前(过期就不排)、取数前(过期就不跑 git)、回投前(过期就丢弃)。
 * 判定基准是 {@link RepoManager#epoch()}(仓库代号)+ 路径双重比对:
 * 代号能区分"切走又切回来"(A→B→A),此时旧任务同样作废,由新一轮刷新重新取数。
 */
public final class RepoGuard {

    private final long epoch;
    private final Path repo;

    private RepoGuard(long epoch, Path repo) {
        this.epoch = epoch;
        this.repo = repo;
    }

    /** 在 UI 线程(用户操作/刷新入口处)捕获当前仓库。 */
    public static RepoGuard capture() {
        return new RepoGuard(RepoManager.get().epoch(), RepoManager.get().current());
    }

    /** 捕获时所在的仓库,null 表示当时没有打开仓库。 */
    public Path repo() { return repo; }

    /** 仓库已切换过 -> 与本次任务相关的一切都应丢弃。 */
    public boolean stale() {
        return epoch != RepoManager.get().epoch() || !Objects.equals(repo, RepoManager.get().current());
    }
}
