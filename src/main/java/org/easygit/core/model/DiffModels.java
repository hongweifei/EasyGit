package org.easygit.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 diff 的数据模型:DiffFile -> DiffHunk -> DiffLine。
 */
public final class DiffModels {
    private DiffModels() {}

    public enum LineType { HUNK, ADD, DEL, CONTEXT }

    /** 行尾风格。diff 的原始输出**逐行保留**行尾,所以能区分出来。 */
    public enum Eol {
        /** 该行以 CRLF 结尾。 */
        CRLF,
        /** 该行以 LF 结尾。 */
        LF,
        /** 该行以单独的 CR 结尾(老 Mac)。 */
        CR,
        /** 该行没有行尾 —— git 的 "\ No newline at end of file" 就是这种。 */
        NONE;

        public String text() {
            return switch (this) {
                case CRLF -> "\r\n";
                case LF -> "\n";
                case CR -> "\r";
                case NONE -> "";
            };
        }

        public String label() {
            return switch (this) {
                case CRLF -> "CRLF";
                case LF -> "LF";
                case CR -> "CR";
                case NONE -> "无行尾";
            };
        }

        /** 从一段行尾文本判定(空串 = 没有行尾)。 */
        public static Eol of(String eol) {
            if (eol == null || eol.isEmpty()) return NONE;
            return switch (eol) {
                case "\r\n" -> CRLF;
                case "\n" -> LF;
                case "\r" -> CR;
                default -> NONE;
            };
        }
    }

    /**
     * diff 输出中的一行。oldNo/newNo 从 1 开始,-1 表示不存在。
     *
     * {@code eol} 是**这一行自己的行尾**:git diff 的原始输出逐行保留行尾,所以"整份文件从 LF
     * 改成 CRLF"与"只改了一行内容"在数据上是可以区分的 —— 不记它就只能看见"每行都变了",
     * 于是界面会把一次纯行尾改动显示成"内容全改了"(用户最容易被这个误导)。
     */
    public record DiffLine(LineType type, int oldNo, int newNo, String text, Eol eol) {
        /** 兼容旧调用:没有行尾信息时按 LF 处理(界面渲染不依赖它)。 */
        public DiffLine(LineType type, int oldNo, int newNo, String text) {
            this(type, oldNo, newNo, text, Eol.LF);
        }
    }

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
        /** 这次改动**只是行尾**(内容一字未变,如 LF → CRLF)。由 {@code DiffParser} 判定。 */
        public boolean eolOnly;
        /** 纯行尾改动时:改动前后的行尾名(LF / CRLF / CR / 无行尾)。 */
        public String eolFrom = "", eolTo = "";
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
