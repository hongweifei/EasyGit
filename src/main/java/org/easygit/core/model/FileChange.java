package org.easygit.core.model;

/**
 * 一个文件的变更(来自 git status porcelain v2)。
 * path 始终为仓库相对路径、'/' 分隔。
 */
public class FileChange {
    public enum Area { STAGED, UNSTAGED, UNTRACKED, CONFLICT }

    public final String path;
    /** 重命名/拷贝的原路径,无则为 null */
    public final String origPath;
    /** 暂存区状态字母:' ','M','A','D','R','C' */
    public final char indexState;
    /** 工作区状态字母:' ','M','D','A' */
    public final char wtState;
    public final boolean unmerged;
    public final boolean untracked;

    public FileChange(String path, String origPath, char indexState, char wtState, boolean unmerged, boolean untracked) {
        this.path = path;
        this.origPath = origPath;
        this.indexState = indexState;
        this.wtState = wtState;
        this.unmerged = unmerged;
        this.untracked = untracked;
    }

    public String displayPath() {
        if (origPath != null && !origPath.isEmpty()) return origPath + " → " + path;
        return path;
    }

    /** 状态字母(用于显示) */
    public String statusLetter(Area area) {
        switch (area) {
            case STAGED -> {
                return indexState == ' ' ? "M" : String.valueOf(indexState);
            }
            case UNSTAGED -> {
                return wtState == ' ' ? "M" : String.valueOf(wtState);
            }
            case UNTRACKED -> { return "?"; }
            case CONFLICT -> { return "U"; }
        }
        return "?";
    }

    /** 单字母状态:U(冲突) > 暂存区 > 工作区 */
    public String shortStatus() {
        if (unmerged) return "U";
        if (indexState == 'R' || indexState == 'C') return String.valueOf(indexState);
        if (indexState != ' ' && indexState != '?') return String.valueOf(indexState);
        if (untracked) return "?";
        if (wtState != ' ') return String.valueOf(wtState);
        return "M";
    }

    @Override
    public String toString() { return displayPath(); }
}
