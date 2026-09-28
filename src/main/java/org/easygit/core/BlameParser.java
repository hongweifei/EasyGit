package org.easygit.core;

import org.easygit.core.model.BlameLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 git blame --porcelain 输出。
 */
public final class BlameParser {
    private BlameParser() {}

    private static final class CommitInfo {
        String sha, author, summary;
        long time;
    }

    public static List<BlameLine> parse(String out) {
        List<BlameLine> result = new ArrayList<>();
        Map<String, CommitInfo> commits = new HashMap<>();
        CommitInfo cur = null;
        int finalLine = 0;

        for (String raw : out.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            if (raw.startsWith("\t")) {
                result.add(new BlameLine(finalLine, raw.substring(1),
                        cur.sha, cur.sha.substring(0, Math.min(8, cur.sha.length())),
                        cur.author, cur.time, cur.summary));
                continue;
            }
            String[] t = raw.split(" ");
            if (t.length >= 4 && t[0].length() == 40 && isHex(t[0])) {
                cur = commits.computeIfAbsent(t[0], k -> new CommitInfo());
                cur.sha = t[0];
                finalLine = Integer.parseInt(t[2]);
            } else if (cur != null) {
                switch (t[0]) {
                    case "author" -> cur.author = raw.substring("author ".length());
                    case "author-time" -> cur.time = parseLong(t.length > 1 ? t[1] : "0");
                    case "summary" -> cur.summary = raw.substring("summary ".length());
                    default -> {}
                }
            }
        }
        return result;
    }

    private static boolean isHex(String s) {
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c) && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.strip()); } catch (Exception e) { return 0; }
    }
}
