package org.easygit.core;

import org.easygit.core.model.CommitEntry;
import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.LineType;
import org.easygit.core.model.FileChange;
import org.easygit.core.StatusParser.StatusResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsersTest {

    @Test
    void statusParserPorcelainV2() {
        String z = "# branch.oid abc123"
                + "\0# branch.head main"
                + "\0# branch.upstream origin/main"
                + "\0# branch.ab +2 -1"
                + "\0" + "1 MM N... mH mI mW hH hI modified.txt"
                + "\0" + "1 M  N... mH mI mW hH hI staged.txt"
                + "\0" + "2 R. N... mH mI mW hH hI R100 new.txt" + "\0old.txt"
                + "\0" + "u AU N... m1 m2 m3 mW h1 h2 h3 conflict.txt"
                + "\0" + "? untracked.txt"
                + "\0" + "! ignored.txt"
                + "\0";
        StatusResult st = StatusParser.parse(z);
        assertEquals("main", st.branch());
        assertEquals("origin/main", st.upstream());
        assertEquals(2, st.ahead());
        assertEquals(1, st.behind());
        assertEquals(5, st.changes().size()); // modified/staged/renamed/conflict/untracked,ignored 跳过

        FileChange modified = st.changes().get(0);
        assertEquals("modified.txt", modified.path);
        assertEquals('M', modified.indexState);
        assertEquals('M', modified.wtState);
        assertTrue(modified.shortStatus().equals("M"));

        FileChange renamed = st.changes().get(2);
        assertEquals("new.txt", renamed.path);
        assertEquals("old.txt", renamed.origPath);
        assertEquals('R', renamed.indexState);

        FileChange conflict = st.changes().get(3);
        assertTrue(conflict.unmerged);
        assertEquals("U", conflict.shortStatus());

        FileChange untracked = st.changes().get(4);
        assertTrue(untracked.untracked);
    }

    @Test
    void logParserParsesFields() {
        String out = "1111111111111111111111111111111111111111\u0001abc1111\u0001222222222222222222222222222222222222222"
                + "\u0001Author One\u0001a@x.com\u00011700000000\u0001HEAD -> main, tag: v1\u0001subject here\u0001body line\0"
                + "3333333333333333333333333333333333333333\u0001ccc3333\u0001\u0001Author Two\u0001b@x.com\u00011700000050"
                + "\u0001\u0001root commit\u0001\0";
        List<CommitEntry> list = LogParser.parse(out);
        assertEquals(2, list.size());
        CommitEntry c1 = list.get(0);
        assertEquals("subject here", c1.subject);
        assertEquals("abc1111", c1.abbr);
        assertEquals(1, c1.parents.size());
        assertEquals(2, c1.refs.size()); // "HEAD -> main" 与 "tag: v1"
        assertTrue(c1.isHead());
        CommitEntry c2 = list.get(1);
        assertTrue(c2.parents.isEmpty());
        assertEquals("root commit", c2.subject);
    }

    @Test
    void diffParserUnifiedPatch() {
        String patch = """
                diff --git a/a.txt b/a.txt
                index 111..222 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -1,3 +1,4 @@ context tail
                 keep1
                -removed line
                +added line
                 keep2
                +new tail
                diff --git a/bin.dat b/bin.dat
                index 333..444 100644
                Binary files a/bin.dat and b/bin.dat differ
                """;
        List<DiffFile> files = DiffParser.parse(patch);
        assertEquals(2, files.size());

        DiffFile f = files.get(0);
        assertEquals("a.txt", f.newPath);
        assertEquals(2, f.added);
        assertEquals(1, f.deleted);
        assertEquals(1, f.hunks.size());
        assertEquals(1, f.hunks.get(0).oldStart);
        assertEquals(3, f.hunks.get(0).oldCount);
        var lines = f.hunks.get(0).lines;
        assertEquals(5, lines.size());
        assertEquals(LineType.CONTEXT, lines.get(0).type());
        assertEquals(1, lines.get(0).oldNo());
        assertEquals(LineType.DEL, lines.get(1).type());
        assertEquals("removed line", lines.get(1).text());
        assertEquals(LineType.ADD, lines.get(2).type());
        assertEquals(2, lines.get(2).newNo());
        assertEquals(4, lines.get(4).newNo());

        DiffFile bin = files.get(1);
        assertTrue(bin.binary);
        assertTrue(bin.hunks.isEmpty());
    }

    @Test
    void graphBuilderAssignsLanesAndEdges() {
        // 三条提交:A <- B(A, C 合并) ;C 独立
        CommitEntry c = new CommitEntry("c", "c", "", "a", "a@x", 1, "", "c", "");
        CommitEntry a = new CommitEntry("a", "a", "", "a", "a@x", 3, "", "a", "");
        CommitEntry b = new CommitEntry("b", "b", "a c", "a", "a@x", 2, "", "b", "");
        // 时间序:c(根) -> a; b 合并 a 和 c
        List<CommitEntry> commits = List.of(b, a, c); // b 最新
        GraphBuilder.build(commits);

        assertEquals(0, commits.get(0).lane); // b 在 0 道
        // b 的父: a -> 沿 0 道继续(渲染器画竖线,无显式边); c -> 新开 1 道,有弯边
        assertTrue(commits.get(0).edges.stream().anyMatch(e -> e[0] == 0 && e[1] == 1));
        assertTrue(commits.get(0).edges.stream().noneMatch(e -> e[0] == 0 && e[1] == 0));
        assertFalse(commits.get(0).parents.isEmpty());

        // a 行:0 道贯穿;incoming 是 0;另有 c 泳道(1 道)贯穿
        assertEquals(0, commits.get(1).lane);
        assertTrue(commits.get(1).hasIncoming);
        assertTrue(commits.get(1).edges.stream().anyMatch(e -> e[0] == 1 && e[1] == 1));

        // c 行:在 1 道,被 b 合并(incoming lane 1);第一父为空,无下方竖线
        assertEquals(1, commits.get(2).lane);
        assertTrue(commits.get(2).hasIncoming);
        assertTrue(commits.get(2).parents.isEmpty());
    }

    @Test
    void conflictParserDiff3Style() {
        List<String> lines = List.of(
                "before",
                "<<<<<<< HEAD",
                "ours1",
                "ours2",
                "||||||| base",
                "common",
                "=======",
                "theirs1",
                ">>>>>>> other",
                "after"
        );
        var cf = ConflictParser.parse("x", lines);
        assertEquals(1, cf.regions.size());
        var r = cf.regions.get(0);
        assertEquals(2, r.startLine);
        assertEquals(2, r.ours.size());
        assertEquals(1, r.base.size());
        assertEquals(1, r.theirs.size());

        List<String> resolved = ConflictParser.resolve(cf, List.of(0), "theirs");
        assertEquals(List.of("before", "theirs1", "after"), resolved);
    }
}
