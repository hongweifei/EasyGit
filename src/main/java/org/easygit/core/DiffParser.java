package org.easygit.core;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffHunk;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.LineType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析统一 diff(git diff / diff-tree 输出),按文件切分并统计行数。
 */
public final class DiffParser {
    private DiffParser() {}

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    public static List<DiffFile> parse(String patch) {
        List<DiffFile> files = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return files;

        DiffFile cur = null;
        DiffHunk hunk = null;
        int oldNo = 0, newNo = 0;
        int added = 0, deleted = 0;

        String[] lines = patch.split("\n", -1);
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("diff --git ")) {
                if (cur != null) finish(cur, added, deleted);
                cur = new DiffFile();
                files.add(cur);
                hunk = null;
                added = 0;
                deleted = 0;
                // diff --git a/x b/y
                String[] parts = line.substring("diff --git ".length()).split(" ");
                if (parts.length >= 2) {
                    cur.oldPath = stripA(parts[parts.length - 2]);
                    cur.newPath = stripA(parts[parts.length - 1]);
                }
                continue;
            }
            if (cur == null) continue;
            if (line.startsWith("new file mode")) { cur.newFile = true; continue; }
            if (line.startsWith("deleted file mode")) { cur.deletedFile = true; continue; }
            if (line.startsWith("rename from ")) { cur.renamed = true; cur.oldPath = line.substring("rename from ".length()); continue; }
            if (line.startsWith("rename to ")) { cur.renamed = true; cur.newPath = line.substring("rename to ".length()); continue; }
            if (line.startsWith("copy from ")) { cur.renamed = true; cur.oldPath = line.substring("copy from ".length()); continue; }
            if (line.startsWith("copy to ")) { cur.renamed = true; cur.newPath = line.substring("copy to ".length()); continue; }
            if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                cur.binary = true; hunk = null; continue;
            }
            if (line.startsWith("--- ")) { String p = line.substring(4); if (!p.equals("/dev/null")) cur.oldPath = stripA(p); continue; }
            if (line.startsWith("+++ ")) { String p = line.substring(4); if (!p.equals("/dev/null")) cur.newPath = stripA(p); continue; }

            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                hunk = new DiffHunk(
                        Integer.parseInt(m.group(1)),
                        m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)),
                        m.group(4) == null ? 1 : Integer.parseInt(m.group(4)),
                        line);
                cur.hunks.add(hunk);
                oldNo = hunk.oldStart;
                newNo = hunk.newStart;
                continue;
            }
            if (hunk == null) continue; // 其他元数据行
            if (line.startsWith("+")) {
                hunk.lines.add(new DiffLine(LineType.ADD, -1, newNo, line.substring(1)));
                newNo++; added++;
            } else if (line.startsWith("-")) {
                hunk.lines.add(new DiffLine(LineType.DEL, oldNo, -1, line.substring(1)));
                oldNo++; deleted++;
            } else if (line.startsWith(" ")) {
                hunk.lines.add(new DiffLine(LineType.CONTEXT, oldNo, newNo, line.substring(1)));
                oldNo++; newNo++;
            } else if (line.startsWith("\\")) {
                // "\ No newline at end of file"
                hunk.lines.add(new DiffLine(LineType.CONTEXT, -1, -1, line));
            }
            // 空行结尾
        }
        if (cur != null) finish(cur, added, deleted);
        return files;
    }

    private static void finish(DiffFile f, int added, int deleted) {
        f.added = added;
        f.deleted = deleted;
    }

    private static String stripA(String p) {
        if (p.length() > 2 && p.charAt(1) == '/' ) return p.substring(2);
        if (p.startsWith("\"") && p.length() > 4 && p.charAt(2) == '/') return unquote(p).substring(2);
        return p;
    }

    public static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1)
                    .replace("\\\\", "\\").replace("\\t", "\t").replace("\\\"", "\"");
        }
        return s;
    }
}
