package org.easygit.core;

import org.easygit.core.model.CommitEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提交泳道图不变量(纯逻辑,不依赖 JavaFX/真实仓库)。
 *
 * 泳道是"每行一个 Canvas"拼出来的,所以上下两行在同一列必须能接上 ——
 * 一旦分道算错,用户看到的就是"泳道断开 / merge 不画弯出 / 所有提交挤在一列"。
 */
class GraphBuilderTest {

    /** 造一条提交:parents 用 "p1 p2" 形式,id 自动生成。 */
    private static CommitEntry c(String id, String parents) {
        return new CommitEntry(id, id.substring(0, 7), parents, "t", "t@t", 0, "", "s", "");
    }

    /**
     * 上行底边的线段端点(泳道号集合)。
     * 贯穿边 {a,a}、分出边 {a,b} 都从底部离开;弯入边 {a,-1} 止于节点,不算。
     */
    private static Set<Integer> bottomEnds(CommitEntry e) {
        Set<Integer> s = new HashSet<>();
        for (int[] ed : e.edges) {
            if (ed[1] != -1) s.add(ed[1]);
        }
        if (!e.parents.isEmpty()) s.add(e.lane);
        return s;
    }

    /** 下行顶边的起点(泳道号集合)。 */
    private static Set<Integer> topStarts(CommitEntry e) {
        Set<Integer> s = new HashSet<>();
        for (int[] ed : e.edges) {
            if (ed[1] == -1) s.add(ed[0]);
            else if (ed[0] == ed[1]) s.add(ed[0]);
        }
        if (e.hasIncoming) s.add(e.lane);
        return s;
    }

    private static void assertContinuous(List<CommitEntry> log) {
        GraphBuilder.build(log);
        for (int i = 0; i + 1 < log.size(); i++) {
            Set<Integer> bot = bottomEnds(log.get(i));
            Set<Integer> top = topStarts(log.get(i + 1));
            assertEquals(bot, top, "行 " + i + "(" + log.get(i).abbr + ")与行 " + (i + 1)
                    + "(" + log.get(i + 1).abbr + ")的泳道没接上");
        }
    }

    @Test
    @DisplayName("重复构图幂等:同一份列表 build 两次结果一致")
    void buildIsIdempotent() {
        // 构图现在收在 HistoryPanel.setCommits 这个唯一注入点里,而它在"缓存上屏 + 后续新鲜数据"
        // 这类路径上会被调用两次;build 若不幂等,第二次就会把泳道画乱。
        List<CommitEntry> log = new ArrayList<>(List.of(
                c("merge001", "local001 remote01"),
                c("local001", "base0001"),
                c("remote01", "base0001"),
                c("base0001", "")));
        GraphBuilder.build(log);
        List<String> first = snapshot(log);
        GraphBuilder.build(log);
        assertEquals(first, snapshot(log), "同一份提交列表重复构图必须得到相同结果");
    }

    private static List<String> snapshot(List<CommitEntry> log) {
        List<String> out = new ArrayList<>();
        for (CommitEntry c : log) {
            StringBuilder sb = new StringBuilder(c.abbr).append(":lane=").append(c.lane)
                    .append(":in=").append(c.hasIncoming).append(":edges=");
            List<String> es = new ArrayList<>();
            for (int[] e : c.edges) es.add(e[0] + "-" + e[1]);
            java.util.Collections.sort(es);
            sb.append(es);
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    @DisplayName("线性历史:一条泳道贯穿,每行都有进入")
    void linear() {
        List<CommitEntry> log = new ArrayList<>(List.of(
                c("aaaaaaa1", "aaaaaaa2"),
                c("aaaaaaa2", "aaaaaaa3"),
                c("aaaaaaa3", "")));
        assertContinuous(log);
        assertEquals(0, log.get(0).lane);
        assertFalse(log.get(0).hasIncoming, "首个提交上方没有线");
        assertTrue(log.get(1).hasIncoming);
        assertTrue(log.get(2).hasIncoming);
        assertTrue(log.get(0).edges.isEmpty(), "线性历史除首尾外没有横向边");
    }

    @Test
    @DisplayName("合并:第二父开新泳道,下一行接得上")
    void merge() {
        // m 是 merge(第一父 l1,第二父 r1)
        List<CommitEntry> log = new ArrayList<>(List.of(
                c("merge001", "local001 remote01"),
                c("local001", "base0001"),
                c("remote01", "base0001"),
                c("base0001", "")));
        assertContinuous(log);
        CommitEntry m = log.get(0);
        assertEquals(1, m.edges.size(), "merge 应有一条分出边");
        int[] e = m.edges.get(0);
        assertEquals(m.lane, e[0]);
        assertNotEquals(e[0], e[1], "第二父必须落到另一条泳道");
    }

    @Test
    @DisplayName("分叉后再合并:泳道槽位复用,不无限右漂")
    void lanesAreReused() {
        // 连续 8 次"从主线分叉一条短支线再合并"。若泳道只新增不复用,最大泳道号会涨到 8+。
        // 注意必须按 git log 的拓扑序(新提交在前、父提交在后)摆放,否则测的不是真实输入。
        int n = 8;
        List<CommitEntry> log = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            String side = "side" + String.format("%04d", i);
            String merge = "mrge" + String.format("%04d", i);
            String prev = i == 0 ? "base0000" : "mrge" + String.format("%04d", i - 1);
            // merge 比 side 新:merge 的父是 prev(上一条 merge)与 side
            log.add(c(merge, prev + " " + side));
            log.add(c(side, prev));
        }
        log.add(c("base0000", ""));

        assertContinuous(log);
        int maxLane = 0;
        for (CommitEntry e : log) {
            maxLane = Math.max(maxLane, e.lane);
            for (int[] ed : e.edges) {
                maxLane = Math.max(maxLane, ed[0]);
                if (ed[1] >= 0) maxLane = Math.max(maxLane, ed[1]);
            }
        }
        // 8 条支线串行出现,同时最多只需 2 条泳道(主线 + 当前支线);放宽到 4 容忍实现细节
        assertTrue(maxLane <= 4, "泳道槽位没有复用:最大泳道号=" + maxLane);
    }

    @Test
    @DisplayName("无关泳道贯穿:与节点无关的既有泳道原样画竖线")
    void unrelatedLanesPassThrough() {
        List<CommitEntry> log = new ArrayList<>(List.of(
                c("merge001", "local001 remote01"),
                c("remote01", "base0001"),   // 远端链先走,主线在 lane0 贯穿
                c("local001", "base0001"),
                c("base0001", "")));
        assertContinuous(log);
        CommitEntry remote = log.get(1);
        assertNotEquals(0, remote.lane, "远端链应在非 0 泳道");
        assertTrue(remote.edges.stream().anyMatch(e -> e[0] == 0 && e[1] == 0),
                "lane0 的主线应作为贯穿边存在");
    }

    @Test
    @DisplayName("空历史/单条提交不崩")
    void degenerate() {
        List<CommitEntry> empty = new ArrayList<>();
        GraphBuilder.build(empty);
        assertTrue(empty.isEmpty());

        List<CommitEntry> single = new ArrayList<>(List.of(c("root0001", "")));
        assertContinuous(single);
        assertEquals(0, single.get(0).lane);
    }

    @Test
    @DisplayName("laneX:泳道中心坐标随宽度线性")
    void laneX() {
        assertEquals(6.0, GraphBuilder.laneX(0, 12), 0.001);
        assertEquals(18.0, GraphBuilder.laneX(1, 12), 0.001);
    }
}
