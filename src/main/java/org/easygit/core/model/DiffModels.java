package org.easygit.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 diff 的数据模型:DiffFile -> DiffHunk -> DiffLine。
 */
public final class DiffModels {
    private DiffModels() {}

    public enum LineType { HUNK, ADD, DEL, CONTEXT }

    /** diff 输出中的一行。oldNo/newNo 从 1 开始,-1 表示不存在。 */
    public record DiffLine(LineType type, int oldNo, int newNo, String text) {}

    /** 一个 hunk(@@ -a,b +c,d @@)。 */
    public static final class DiffHunk {
        public final int oldStart, oldCount, newStart, newCount;
        public final String header;   // @@ 行全文(含上下文尾部)
        public final List<DiffLine> lines = new ArrayList<>();

        public DiffHunk(int oldStart, int oldCount, int newStart, int newCount, String header) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.header = header;
        }
    }

    /** 一个文件的 diff。 */
    public static final class DiffFile {
        public String oldPath = "";
        public String newPath = "";
        public boolean binary;
        public boolean newFile, deletedFile, renamed;
        public int added, deleted;   // 行数统计(numstat)
        public final List<DiffHunk> hunks = new ArrayList<>();

        public String displayPath() {
            if (renamed && !oldPath.equals(newPath)) return oldPath + " → " + newPath;
            return newPath.isEmpty() ? oldPath : newPath;
        }

        /** 单行摘要:路径 +N -N */
        public String statText() {
            if (binary) return displayPath() + " (二进制)";
            return displayPath() + "  +" + added + " -" + deleted;
        }

        /** 全部行(供渲染)。 */
        public List<DiffLine> allLines() {
            List<DiffLine> out = new ArrayList<>();
            for (DiffHunk h : hunks) {
                out.add(new DiffLine(LineType.HUNK, -1, -1, h.header));
                out.addAll(h.lines);
            }
            return out;
        }
    }
}
