package org.easygit.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 冲突文件解析模型(基于 <<<<<<< ======= >>>>>>> 标记)。
 */
public final class ConflictModels {
    private ConflictModels() {}

    /** 一个冲突块。 */
    public static final class ConflictRegion {
        public final int startLine;              // 文件中起始行(1-based,含 <<<<<<< 行)
        public final List<String> ours = new ArrayList<>();
        public final List<String> base = new ArrayList<>();   // diff3 时存在
        public final List<String> theirs = new ArrayList<>();

        public ConflictRegion(int startLine) { this.startLine = startLine; }
    }

    /** 一个冲突文件的完整解析结果。 */
    public static final class ConflictFile {
        public final String path;
        /** 原始文件所有行 */
        public final List<String> lines;
        public final List<ConflictRegion> regions = new ArrayList<>();

        public ConflictFile(String path, List<String> lines) {
            this.path = path;
            this.lines = lines;
        }

        public boolean hasConflicts() { return !regions.isEmpty(); }
    }
}
