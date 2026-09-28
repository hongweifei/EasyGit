package org.easygit.core.model;

/**
 * 分支/标签/远程引用信息(来自 for-each-ref)。
 */
public class BranchInfo {
    public enum Kind { LOCAL, REMOTE, TAG }

    public final Kind kind;
    /** 简短名:main、origin/main、v1.0 */
    public final String name;
    /** 全名:refs/heads/main 等 */
    public final String fullName;
    public final String tip;      // 指向的提交 sha
    public final boolean current; // HEAD 所指(仅 LOCAL)
    public final String upstream; // 上游简称,如 origin/main,无则 null
    public final String track;    // ahead/behind 标记,如 "[ahead 2]",无则 ""
    public final long time;

    public BranchInfo(Kind kind, String name, String fullName, String tip,
                      boolean current, String upstream, String track, long time) {
        this.kind = kind;
        this.name = name;
        this.fullName = fullName;
        this.tip = tip;
        this.current = current;
        this.upstream = upstream;
        this.track = track == null ? "" : track;
        this.time = time;
    }

    @Override
    public String toString() { return name; }
}
