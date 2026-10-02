package org.easygit.core.model;

/**
 * 推送前的现状快照(基于上次 fetch 后的本地引用,不做网络操作)。
 *
 * 与 {@link PullPlan} 对称:界面层只做分支判断,不必自己拼装 rev-list。
 * behind > 0 说明远端有别人推的新提交,直接推送会被拒绝 —— 要提前预警而不是事后甩英文报错。
 */
public record PushPlan(String branch, String upstream, int ahead, int behind) {

    /** 分支没有设置上游(推送时应自动设置,或走推送对话框)。 */
    public boolean noUpstream() { return upstream == null; }

    /** 本地与远端一致,没有可推送的提交。 */
    public boolean synced() { return upstream != null && ahead == 0 && behind == 0; }

    /** 只有本地领先:一键推送即可成功。 */
    public boolean pushable() { return upstream != null && ahead > 0 && behind == 0; }

    /** 两端各有提交:直接推送会被拒绝,需要先拉取或强制推送。 */
    public boolean diverged() { return upstream != null && ahead > 0 && behind > 0; }
}
