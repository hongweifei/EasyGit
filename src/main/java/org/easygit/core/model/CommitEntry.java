package org.easygit.core.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条提交记录(来自 git log),同时承载历史图布局结果。
 */
public class CommitEntry {
    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public final String id;        // 完整 sha
    public final String abbr;      // 短 sha
    public final List<String> parents = new ArrayList<>();
    public final String author;
    public final String email;
    public final long time;        // epoch seconds
    public final String subject;
    public final String body;
    /** refs 装饰:HEAD、分支名、tag:xxx、origin/xxx */
    public final List<String> refs = new ArrayList<>();

    // ---- 历史图布局 ----
    public int lane;
    /** 本行的图边:{fromLane, toLane},from 在顶部进入、to 在底部离开;-1 结尾指向节点 */
    public List<int[]> edges = List.of();
    public boolean hasIncoming;

    public CommitEntry(String id, String abbr, String parents, String author, String email,
                       long time, String refsLine, String subject, String body) {
        this.id = id;
        this.abbr = abbr;
        if (parents != null && !parents.isBlank()) {
            for (String p : parents.strip().split(" ")) if (!p.isEmpty()) this.parents.add(p);
        }
        this.author = author == null ? "" : author;
        this.email = email == null ? "" : email;
        this.time = time;
        this.subject = subject == null ? "" : subject;
        this.body = body == null ? "" : body;
        if (refsLine != null && !refsLine.isBlank()) {
            for (String r : refsLine.split(",")) {
                String s = r.strip();
                if (!s.isEmpty()) this.refs.add(s);
            }
        }
    }

    public String timeText() { return DTF.format(Instant.ofEpochSecond(time)); }

    public boolean isHead() { return refs.stream().anyMatch(r -> r.equals("HEAD") || r.startsWith("HEAD ->")); }

    public List<String> localBranchRefs() {
        return refs.stream().filter(r -> !r.startsWith("tag:") && !r.startsWith("origin/")
                && !r.startsWith("HEAD") && !r.contains("/")).toList();
    }

    @Override
    public String toString() { return abbr + " " + subject; }
}
