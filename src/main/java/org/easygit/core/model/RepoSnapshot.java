package org.easygit.core.model;

import java.util.List;
import java.util.Set;

/**
 * 一个仓库的**落盘快照**:重开程序时先把上次看到的样子画出来,再后台刷新。
 *
 * 为什么值得持久化:内存版快照(SWR)只在同一次运行内有效,重启后缓存全丢 ——
 * 用户每次开程序、切到没访问过的仓库,都要从空界面开始等一轮 git。
 *
 * 只存"重绘所需的最小集合":泳道布局(lane/edges/hasIncoming)不落盘,
 * 因为 {@code GraphBuilder.build} 本来就会在注入列表时重新算(见 HistoryPanel.setCommits)。
 *
 * @param version  格式版本;读到时不一致就整份丢弃(宁可不用缓存,也不能用错数据)
 * @param savedAt  保存时刻(毫秒),用于判断新鲜度与给用户显示"多久之前的快照"
 * @param repoPath 仓库绝对路径:文件名是路径的摘要,这里再存一份用于自校验
 * @param refs     阶段一数据对应的引用指纹(轮询比较用)
 * @param status   阶段一数据(状态栏/变更清单/分支列表),可能缺失
 * @param history  历史页数据,可能缺失(用户从没打开过「历史」页时就没有)
 */
public record RepoSnapshot(int version, long savedAt, String repoPath, String refs,
                           StatusSnap status, HistorySnap history) {

    public static final int CURRENT_VERSION = 1;

    /** 重绘「变更 + 分支 + 状态栏」所需的数据。 */
    public record StatusSnap(String oid, String branch, String upstream, boolean detached,
                             int ahead, int behind, List<FileChange> changes, List<BranchInfo> branches) {}

    /**
     * 重绘「历史」页所需的数据。
     *
     * 注意 refs 是**这份历史加载时**的指纹,与 StatusSnap 的 refs 不是一回事:
     * 历史可能加载得更早,若共用"最新的"指纹,缓存就会声称自己比数据更新,
     * 于是该重载时被判成"仍然有效" —— 界面就会一直显示旧历史。
     */
    public record HistorySnap(String refs, int maxCommits, boolean allBranches, String pathFilter,
                              List<CommitEntry> log, Set<String> unpushed) {}
}
