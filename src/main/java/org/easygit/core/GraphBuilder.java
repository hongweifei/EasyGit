package org.easygit.core;

import org.easygit.core.model.CommitEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交历史 DAG 分道算法(gitk 风格):
 * 为每条提交计算所在泳道 lane 与贯穿本行的图边 edges({fromLane,toLane})。
 */
public final class GraphBuilder {
    private GraphBuilder() {}

    public static void build(List<CommitEntry> commits) {
        Map<String, CommitEntry> byId = new HashMap<>();
        for (CommitEntry c : commits) byId.put(c.id, c);

        List<String> tips = new ArrayList<>(); // 每条泳道当前指向的提交 id,null=空闲

        for (CommitEntry c : commits) {
            // 指向本提交的泳道(从上方进入)
            List<Integer> incoming = new ArrayList<>();
            for (int i = 0; i < tips.size(); i++) {
                if (c.id.equals(tips.get(i))) incoming.add(i);
            }
            int nodeLane;
            if (incoming.isEmpty()) {
                nodeLane = tips.size();
                tips.add(c.id);
                c.hasIncoming = false;
            } else {
                nodeLane = incoming.get(0);
                c.hasIncoming = true;
            }
            c.lane = nodeLane;

            List<int[]> edges = new ArrayList<>();
            // 与本节点无关的泳道原样贯穿
            for (int i = 0; i < tips.size(); i++) {
                if (i != nodeLane && !incoming.contains(i) && tips.get(i) != null) {
                    edges.add(new int[]{i, i});
                }
            }
            // 其他进入本节点的泳道:向节点弯曲
            for (int i : incoming) {
                if (i != nodeLane) edges.add(new int[]{i, -1}); // -1 = 终止于节点
            }
            // 释放所有进入泳道
            for (int i : incoming) tips.set(i, null);

            // 父提交:第一父沿本道继续(渲染器负责画节点下方竖线);
            // 其余父寻找既有泳道或新开泳道,产生节点向下的弯边
            boolean first = true;
            for (String p : c.parents) {
                if (p == null || p.isEmpty()) continue;
                if (first) {
                    tips.set(nodeLane, p);
                    first = false;
                } else {
                    int k = -1;
                    for (int i = 0; i < tips.size(); i++) {
                        if (p.equals(tips.get(i))) { k = i; break; }
                    }
                    if (k < 0) {
                        k = tips.size();
                        tips.add(p);
                    }
                    edges.add(new int[]{nodeLane, k});
                }
            }
            c.edges = edges;
        }
    }

    /** 泳道 x 坐标(渲染用)。 */
    public static double laneX(int lane, double laneWidth) {
        return lane * laneWidth + laneWidth / 2.0;
    }
}
