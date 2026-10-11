package org.easygit.core;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffHunk;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.Eol;
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
            // **保留**行尾:git diff 的原始输出逐行带着它自己的行尾(实测同一个 hunk 里
            // 上下文行是 LF、新增行是 CRLF 都出现过)。丢掉它就无法区分"改了内容"与"只改了行尾"。
            boolean crlf = raw.endsWith("\r");
            String line = crlf ? raw.substring(0, raw.length() - 1) : raw;
            Eol lineEol = crlf ? Eol.CRLF : Eol.LF;
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
                hunk.lines.add(new DiffLine(LineType.ADD, -1, newNo, line.substring(1), lineEol));
                newNo++; added++;
            } else if (line.startsWith("-")) {
                hunk.lines.add(new DiffLine(LineType.DEL, oldNo, -1, line.substring(1), lineEol));
                oldNo++; deleted++;
            } else if (line.startsWith(" ")) {
                hunk.lines.add(new DiffLine(LineType.CONTEXT, oldNo, newNo, line.substring(1), lineEol));
                oldNo++; newNo++;
            } else if (line.startsWith("\\")) {
                // "\ No newline at end of file":标记**上一行**没有行尾。
                // 这条也要落到数据里,否则"文件末尾少了换行"会被显示成内容改动。
                hunk.lines.add(new DiffLine(LineType.CONTEXT, -1, -1, line, Eol.NONE));
                if (!hunk.lines.isEmpty()) {
                    int prev = hunk.lines.size() - 2;
                    if (prev >= 0) {
                        DiffLine p = hunk.lines.get(prev);
                        hunk.lines.set(prev, new DiffLine(p.type(), p.oldNo(), p.newNo(), p.text(), Eol.NONE));
                    }
                }
            }
            // 空行结尾
        }
        if (cur != null) finish(cur, added, deleted);
        // 标记"这次改动是否只是行尾"—— 纯行尾改动必须能被识别出来,否则界面会说"内容全改了"
        for (DiffFile f : files) markEolOnly(f);
        return files;
    }

    /**
     * 判断一个文件是不是**只改了行尾**:把删除行与新增行按顺序两两配对,文本都相同、
     * 只有行尾不同 ⇒ 纯行尾改动。
     *
     * 这样界面就能说"行尾从 LF 改成 CRLF"而不是"3 行内容都改了"(后者会让人以为代码被动过)。
     */
    private static void markEolOnly(DiffFile f) {
        List<DiffLine> dels = new ArrayList<>();
        List<DiffLine> adds = new ArrayList<>();
        for (DiffHunk h : f.hunks) {
            for (DiffLine l : h.lines) {
                if (l.type() == LineType.DEL) dels.add(l);
                else if (l.type() == LineType.ADD) adds.add(l);
            }
        }
        if (dels.isEmpty() || dels.size() != adds.size()) return;
        for (int i = 0; i < dels.size(); i++) {
            if (!dels.get(i).text().equals(adds.get(i).text())) return;
            if (dels.get(i).eol() == adds.get(i).eol()) return;   // 行尾也一样 ⇒ 不是行尾改动
        }
        f.eolOnly = true;
        f.eolFrom = dels.get(0).eol().label();
        f.eolTo = adds.get(0).eol().label();
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
