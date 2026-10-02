package org.easygit.ui.panels;

import org.easygit.core.GitProcess;
import org.easygit.core.NativeGit;
import org.easygit.core.RepoManager;
import org.easygit.core.model.BranchInfo;
import org.easygit.core.model.PushPlan;
import org.easygit.ui.base.Fx;
import org.easygit.ui.base.UiLog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 推送流程编排(与 {@link PullFlow} 对称)。
 *
 * 点「推送」:先探测当前分支相对上游的领先/落后,再打开推送对话框 ——
 * 对话框里会显示这个状态(同步/领先/分叉),「强制推送」复选框始终可选
 * (用户可能就是想覆盖远端)。确认推送后:
 *  - 已分叉且未勾选强制 → 弹预警(先拉取再推 / 强制覆盖 / 取消);
 *  - 被远端拒绝(竞态:探测后别人又推了) → 询问后 拉取重推;
 *  - 失败 → 中文报错归类;成功 → 报"已推送 N 个提交"。
 *
 * 拉取编排复用 {@link PullFlow}(通过它实现"拉取后自动重试推送"),避免两套流程互相复制。
 */
public final class PushFlow {

    /** 宿主窗口需要提供的能力。 */
    public interface Host {
        /** 刷新整仓(成功后调用)。 */
        void refreshAll();

        /** 打开推送对话框(带上预探测结果,对话框据此显示状态并预选)。 */
        void openPushDialog(Preflight pre);
    }

    /** 预探测结果:交给对话框显示与预选。 */
    public record Preflight(List<BranchInfo> locals, Map<String, String> remotes,
                            PushPlan plan, boolean detached) {}

    private final Host host;
    private final PullFlow pullFlow;

    public PushFlow(Host host, PullFlow pullFlow) {
        this.host = host;
        this.pullFlow = pullFlow;
    }

    /** 推送入口:预探测当前分支状态,然后打开推送对话框。 */
    public void push() {
        Path repo = RepoManager.get().current();
        if (repo == null) return;
        Fx.bg("检查推送状态…", () -> {
            List<BranchInfo> locals = NativeGit.branches(repo).stream()
                    .filter(b -> b.kind == BranchInfo.Kind.LOCAL).toList();
            Map<String, String> remotes = NativeGit.remotes(repo);
            boolean detached = locals.stream().noneMatch(b -> b.current);
            PushPlan plan = detached ? null
                    : NativeGit.pushPlan(repo, locals.stream()
                            .filter(b -> b.current).findFirst().orElseThrow().name);
            return new Preflight(locals, remotes, plan, detached);
        }, pre -> host.openPushDialog(pre));
    }

    /**
     * 推送执行 + 结果翻译(对话框确认后与"拉取后重推"都走这里)。
     *
     * @param setUpstream 推送并建立上游
     * @param force       强制推送(--force-with-lease,比 --force 安全:远端有本地不知道的提交时会被拒)
     */
    public void execute(Path repo, String branch, String remote, boolean setUpstream, boolean force) {
        Fx.bg("推送中…", () -> NativeGit.runPushStep(repo, branch, remote, setUpstream, force), res -> {
            GitProcess.GitResult r = res.result();
            UiLog.op("git push" + (force ? " --force-with-lease" : "") + " " + branch + " → " + remote
                    + (r.ok() ? " ✓" : " ✖"), r.out(), r.err());
            if (r.ok()) {
                if (res.pushed() > 0) Fx.status("已推送 " + res.pushed() + " 个提交 → " + remote);
                else Fx.status("已推送 " + branch + " → " + remote);
                host.refreshAll();
                return;
            }
            // 竞态:预探测说可以推,但推送前别人又推了 → 提供拉取后重推
            if (NativeGit.isPushRejected(r.message()) && pullFlow != null
                    && Fx.confirm("推送被拒绝",
                    "远端已有别人推送的新提交,直接覆盖会丢掉他们的提交。\n\n"
                            + "先拉取(合并/变基)再自动重新推送?")) {
                pullFlow.pull(() -> execute(repo, branch, remote, setUpstream, force));
                return;
            }
            String hint = NativeGit.friendlyError(r.message());
            if (hint.equals(r.message().strip())) {
                Fx.error("推送失败", r.message(), null);
            } else {
                Fx.error("推送失败", hint, r.message());
            }
        });
    }
}
