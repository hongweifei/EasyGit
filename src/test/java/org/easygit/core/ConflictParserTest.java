package org.easygit.core;

import org.easygit.core.model.ConflictModels.ConflictFile;
import org.easygit.core.model.ConflictModels.ConflictRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冲突标记解析与解决(纯字符串,无 IO 无 JavaFX)。
 */
class ConflictParserTest {

    private static final List<String> SIMPLE = List.of(
            "keep-1",
            "<<<<<<< HEAD",
            "ours-a",
            "ours-b",
            "=======",
            "theirs-a",
            ">>>>>>> origin/main",
            "keep-2");

    private static final List<String> DIFF3 = List.of(
            "<<<<<<< HEAD",
            "ours",
            "||||||| merged common ancestors",
            "base-1",
            "base-2",
            "=======",
            "theirs",
            ">>>>>>> other");

    @Test
    @DisplayName("解析单块冲突:行号、双方内容、原行数")
    void parsesSimpleConflict() {
        ConflictFile cf = ConflictParser.parse("f.txt", SIMPLE);
        assertEquals(1, cf.regions.size());
        assertEquals(8, cf.lines.size());
        ConflictRegion r = cf.regions.get(0);
        assertEquals(2, r.startLine, "<<<<<<< 在文件第 2 行(1-based)");
        assertEquals(List.of("ours-a", "ours-b"), r.ours);
        assertEquals(List.of("theirs-a"), r.theirs);
        assertTrue(r.base.isEmpty(), "非 diff3 时没有 base");
        assertTrue(cf.hasConflicts());
    }

    @Test
    @DisplayName("解析 diff3 的三方内容(||||||| 段)")
    void parsesDiff3() {
        ConflictFile cf = ConflictParser.parse("f.txt", DIFF3);
        assertEquals(1, cf.regions.size());
        ConflictRegion r = cf.regions.get(0);
        assertEquals(List.of("ours"), r.ours);
        assertEquals(List.of("base-1", "base-2"), r.base);
        assertEquals(List.of("theirs"), r.theirs);
        assertEquals(1, r.startLine);
    }

    @Test
    @DisplayName("多块冲突:块数与起始行都要对")
    void parsesMultipleRegions() {
        List<String> lines = List.of(
                "<<<<<<< HEAD",
                "o1",
                "=======",
                "t1",
                ">>>>>>> x",
                "middle",
                "<<<<<<< HEAD",
                "o2",
                "=======",
                "t2",
                ">>>>>>> x");
        ConflictFile cf = ConflictParser.parse("f.txt", lines);
        assertEquals(2, cf.regions.size());
        assertEquals(1, cf.regions.get(0).startLine);
        assertEquals(7, cf.regions.get(1).startLine);
        assertEquals(List.of("o2"), cf.regions.get(1).ours);
        assertEquals(List.of("t2"), cf.regions.get(1).theirs);
    }

    @Test
    @DisplayName("按策略解决:ours / theirs / both / base")
    void resolveStrategies() {
        ConflictFile cf = ConflictParser.parse("f.txt", SIMPLE);
        List<Integer> all = List.of(0);
        assertEquals(List.of("keep-1", "ours-a", "ours-b", "keep-2"),
                ConflictParser.resolve(cf, all, "ours"));
        assertEquals(List.of("keep-1", "theirs-a", "keep-2"),
                ConflictParser.resolve(cf, all, "theirs"));
        assertEquals(List.of("keep-1", "ours-a", "ours-b", "theirs-a", "keep-2"),
                ConflictParser.resolve(cf, all, "both"));

        ConflictFile d3 = ConflictParser.parse("f.txt", DIFF3);
        assertEquals(List.of("base-1", "base-2"), ConflictParser.resolve(d3, all, "base"));
    }

    @Test
    @DisplayName("未解决时解决结果仍保留标记,且解决后标记消失")
    void resolveKeepsUnresolvedMarkers() {
        ConflictFile cf = ConflictParser.parse("f.txt", SIMPLE);
        List<String> untouched = ConflictParser.resolve(cf, List.of(), "ours");
        assertTrue(ConflictParser.hasMarkers(untouched), "没点解决的块必须保留标记");
        assertEquals(SIMPLE, untouched, "整块原样保留,不能丢行");

        List<String> done = ConflictParser.resolve(cf, List.of(0), "theirs");
        assertFalse(ConflictParser.hasMarkers(done));
    }

    @Test
    @DisplayName("标记判定:======= 不算(可能只是 Markdown 下划线),外框标记才算")
    void hasMarkersIgnoresSeparatorOnly() {
        assertFalse(ConflictParser.hasMarkers(List.of("标题", "=======", "正文")));
        assertFalse(ConflictParser.hasMarkers(List.of()));
        assertTrue(ConflictParser.hasMarkers(List.of("<<<<<<< HEAD")));
        assertTrue(ConflictParser.hasMarkers(List.of(">>>>>>> origin/main")));
    }
}
