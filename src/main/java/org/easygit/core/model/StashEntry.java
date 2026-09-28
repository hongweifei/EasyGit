package org.easygit.core.model;

/**
 * 一条 stash 记录。
 */
public class StashEntry {
    public final int index;     // stash@{index}
    public final String sha;
    public final long time;     // epoch seconds
    public final String message;

    public StashEntry(int index, String sha, long time, String message) {
        this.index = index;
        this.sha = sha;
        this.time = time;
        this.message = message;
    }

    public String ref() { return "stash@{" + index + "}"; }

    @Override
    public String toString() { return ref() + " " + message; }
}
