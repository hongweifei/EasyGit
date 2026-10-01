package org.easygit.core.model;

/**
 * 抓取后的仓库现状快照(拉取分流依据)。
 *
 * 这些字段是"该走哪条拉取路径"的完整输入,所以放在一起由 {@link org.easygit.core.NativeGit#pullPlan}
 * 一次算出来,界面层只做分支判断,不必自己拼装。error 非空表示抓取本身失败。
 */
public record PullPlan(String upstream, int ahead, int behind, int conflicts, boolean dirty, String error) {

    /** 当前分支没有上游(既不是错误,也没法拉)。 */
    public static PullPlan noUpstream() { return new PullPlan(null, 0, 0, 0, false, null); }

    /** 抓取失败。 */
    public static PullPlan failed(String error) { return new PullPlan(null, 0, 0, 0, false, error); }

    /** 远端没有新提交。 */
    public boolean upToDate() { return error == null && upstream != null && behind == 0; }

    /** 本地没有额外提交,可以纯快进。 */
    public boolean fastForwardable() { return error == null && upstream != null && behind > 0 && ahead == 0; }

    /** 两端各有提交,需要用户选择合并或变基。 */
    public boolean diverged() { return error == null && upstream != null && behind > 0 && ahead > 0; }
}
