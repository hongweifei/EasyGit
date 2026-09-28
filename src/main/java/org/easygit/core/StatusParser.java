package org.easygit.core;

import org.easygit.core.model.FileChange;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 git status --porcelain=v2 -z --branch 输出。
 */
public final class StatusParser {
    private StatusParser() {}

    public record StatusResult(String oid, String branch, String upstream, boolean detached,
                               int ahead, int behind, List<FileChange> changes) {
        public boolean hasUpstream() { return upstream != null && !upstream.isEmpty(); }
    }

    public static StatusResult parse(String z) {
        String oid = "", branch = "", upstream = "";
        boolean detached = false;
        int ahead = 0, behind = 0;
        List<FileChange> changes = new ArrayList<>();

        // -z 模式:记录以 NUL 分隔;2 号(重命名)记录占用两个 NUL 段
        String[] recs = z.split("\0", -1);
        for (int i = 0; i < recs.length; i++) {
            String rec = recs[i];
            if (rec.isEmpty()) continue;
            if (rec.startsWith("# branch.oid ")) {
                oid = rec.substring("# branch.oid ".length()).strip();
            } else if (rec.startsWith("# branch.head ")) {
                branch = rec.substring("# branch.head ".length()).strip();
                detached = branch.equals("(detached)");
            } else if (rec.startsWith("# branch.upstream ")) {
                upstream = rec.substring("# branch.upstream ".length()).strip();
            } else if (rec.startsWith("# branch.ab ")) {
                String ab = rec.substring("# branch.ab ".length()).strip(); // +2 -1
                for (String tok : ab.split(" ")) {
                    try {
                        if (tok.startsWith("+")) ahead = Integer.parseInt(tok.substring(1));
                        else if (tok.startsWith("-")) behind = Integer.parseInt(tok.substring(1));
                    } catch (NumberFormatException ignored) {}
                }
            } else if (rec.startsWith("1 ")) {
                FileChange fc = parseNumbered(rec, 1, null);
                if (fc != null) changes.add(fc);
            } else if (rec.startsWith("2 ")) {
                // 格式:2 XY sub mH mI mW hH hI X<score> path <NUL> origPath
                String orig = (i + 1 < recs.length) ? recs[i + 1] : null;
                i++;
                FileChange fc = parseNumbered(rec, 2, (orig == null || orig.isEmpty()) ? null : orig);
                if (fc != null) changes.add(fc);
            } else if (rec.startsWith("u ")) {
                // u XY sub m1 m2 m3 mW h1 h2 h3 path
                if (rec.length() > 5) {
                    char x = norm(rec.charAt(2)), y = norm(rec.charAt(3));
                    String[] rest = rec.substring(5).split(" ");
                    if (rest.length >= 9) {
                        changes.add(new FileChange(rest[8], null, x, y, true, false));
                    }
                }
            } else if (rec.startsWith("? ")) {
                changes.add(new FileChange(rec.substring(2), null, '?', '?', false, true));
            }
            // "! " 忽略的文件,跳过
        }
        return new StatusResult(oid, branch, upstream, detached, ahead, behind, changes);
    }

    /**
     * 解析 "1"/"2" 记录。XY 是固定两字符(可含空格),必须定长提取后再按空格拆分,
     * 否则 XY 中的空格会让字段串位。
     * 1: [sub, mH, mI, mW, hH, hI, path]          -> path 下标 6
     * 2: [sub, mH, mI, mW, hH, hI, X<score>, path] -> path 下标 7
     */
    private static FileChange parseNumbered(String rec, int kind, String origPath) {
        if (rec.length() < 5) return null;
        // XY 中未修改用 '.' 表示,归一化为 ' ',下游按 ' ' 判断未修改
        char x = norm(rec.charAt(2)), y = norm(rec.charAt(3));
        // rec: "1 XY ..." -> 跳过 "N " (N=1|2)
        String[] rest = rec.substring(5).split(" ");
        int pathIdx = (kind == 1) ? 6 : 7;
        if (rest.length <= pathIdx) return null;
        String path = rest[pathIdx];
        return new FileChange(path, origPath, x, y, false, false);
    }

    private static char norm(char c) {
        return c == '.' ? ' ' : c;
    }
}
