package org.easygit.core.model;

/**
 * blame 的一行结果。
 */
public class BlameLine {
    public final int lineNo;
    public final String content;
    public final String sha;
    public final String abbr;
    public final String author;
    public final long time;      // epoch seconds
    public final String summary;

    public BlameLine(int lineNo, String content, String sha, String abbr, String author, long time, String summary) {
        this.lineNo = lineNo;
        this.content = content;
        this.sha = sha;
        this.abbr = abbr;
        this.author = author;
        this.time = time;
        this.summary = summary;
    }
}
