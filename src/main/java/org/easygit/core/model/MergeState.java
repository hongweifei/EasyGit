package org.easygit.core.model;

/**
 * 仓库进行中的多步操作状态(合并 / 变基 / 拣选 / 回滚 / 打补丁)。
 *
 * 冲突很少是孤立事件:变基停在冲突上时,用户解决完还要「继续」才能把剩下的提交放完;
 * 合并停在冲突上时,提交才算完成这次合并。界面靠这个记录渲染变更页顶部的横幅
 * (什么操作、第几个提交、剩几处冲突),并决定给哪几个动作(继续 / 跳过 / 中止)。
 *
 * @param kind    进行中的操作类型
 * @param detail  一句话上下文(合并的默认说明首行 / 变基的分支名 / 拣选的提交号)
 * @param message 默认提交说明(合并才有:MERGE_MSG,已去掉注释行),可直接填进提交框
 * @param step    变基已进行到第几个提交(取不到为 0)
 * @param total   变基一共几个提交(取不到为 0)
 */
public record MergeState(Kind kind, String detail, String message, int step, int total) {

    /** 进行中的操作类型。 */
    public enum Kind {
        NONE(""), MERGE("合并"), REBASE("变基"), CHERRY_PICK("拣选"), REVERT("回滚"), AM("打补丁");

        private final String label;

        Kind(String label) { this.label = label; }

        /** 中文操作名(用于界面文案)。 */
        public String label() { return label; }

        public boolean inProgress() { return this != NONE; }
    }

    /** 没有进行中的操作。 */
    public static final MergeState NONE = new MergeState(Kind.NONE, "", "", 0, 0);

    public boolean inProgress() { return kind.inProgress(); }

    /** 只有变基能"跳过当前提交"。 */
    public boolean canSkip() { return kind == Kind.REBASE; }

    /** 进度文案(变基才有):「第 2/5 个提交」;取不到进度时返回空串。 */
    public String progressText() {
        if (total <= 0) return "";
        return step > 0 ? "第 " + step + "/" + total + " 个提交" : "共 " + total + " 个提交";
    }
}
