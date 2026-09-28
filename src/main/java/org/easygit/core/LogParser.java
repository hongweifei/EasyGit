package org.easygit.core;

import org.easygit.core.model.CommitEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 git log 的机器格式(%H US %h US %P US %an US %ae US %at US %D US %s US %b,记录间 NUL)。
 */
public final class LogParser {
    private LogParser() {}

    public static final String LOG_FORMAT =
            "--format=%H%x01%h%x01%P%x01%an%x01%ae%x01%at%x01%D%x01%s%x01%b%x00";

    public static List<CommitEntry> parse(String out) {
        List<CommitEntry> list = new ArrayList<>();
        for (String rec : out.split("\0")) {
            if (rec.isBlank()) continue;
            String[] f = rec.split("\u0001", -1);
            if (f.length < 9) continue;
            list.add(new CommitEntry(f[0], f[1], f[2], f[3], f[4],
                    parseLong(f[5], 0), f[6], f[7], f[8]));
        }
        return list;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.strip()); } catch (Exception e) { return def; }
    }
}
