package org.easygit.core;

import org.easygit.core.model.ConflictModels.ConflictFile;
import org.easygit.core.model.ConflictModels.ConflictRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析冲突标记(<<<<<<< / ======= / >>>>>>>,兼容 diff3 的 |||||||)。
 */
public final class ConflictParser {
    private ConflictParser() {}

    private static final Pattern OURS = Pattern.compile("^<{7}( .*)?$");
    private static final Pattern BASE = Pattern.compile("^\\|{7}( .*)?$");
    private static final Pattern SEP = Pattern.compile("^={7}$");
    private static final Pattern THEIRS = Pattern.compile("^>{7}( .*)?$");

    public static ConflictFile parse(String path, List<String> lines) {
        ConflictFile cf = new ConflictFile(path, lines);
        int state = 0; // 0 普通,1 ours,2 base,3 theirs
        ConflictRegion region = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            switch (state) {
                case 0 -> {
                    if (OURS.matcher(line).matches()) {
                        region = new ConflictRegion(i + 1);
                        cf.regions.add(region);
                        state = 1;
                    }
                }
                case 1 -> {
                    if (BASE.matcher(line).matches()) state = 2;
                    else if (SEP.matcher(line).matches()) state = 3;
                    else if (THEIRS.matcher(line).matches()) { state = 0; region = null; }
                    else if (region != null) region.ours.add(line);
                }
                case 2 -> {
                    if (SEP.matcher(line).matches()) state = 3;
                    else if (region != null) region.base.add(line);
                }
                case 3 -> {
                    if (THEIRS.matcher(line).matches()) { state = 0; region = null; }
                    else if (region != null) region.theirs.add(line);
                }
            }
        }
        return cf;
    }

    /** 按给定策略生成解决后的完整文件行。 */
    public static List<String> resolve(ConflictFile cf, List<Integer> resolvedRegions, String strategy) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < cf.lines.size(); i++) {
            String line = cf.lines.get(i);
            if (idx < cf.regions.size() && i == cf.regions.get(idx).startLine - 1) {
                ConflictRegion r = cf.regions.get(idx);
                if (resolvedRegions.contains(idx)) {
                    switch (strategy) {
                        case "ours" -> out.addAll(r.ours);
                        case "theirs" -> out.addAll(r.theirs);
                        case "base" -> out.addAll(r.base);
                        case "both" -> { out.addAll(r.ours); out.addAll(r.theirs); }
                        default -> { }
                    }
                } else {
                    // 保留原始冲突标记
                    out.add("<<<<<<<");
                    out.addAll(r.ours);
                    if (!r.base.isEmpty()) {
                        out.add("|||||||");
                        out.addAll(r.base);
                    }
                    out.add("=======");
                    out.addAll(r.theirs);
                    out.add(">>>>>>>");
                }
                // 跳过整个冲突块:<<< + ours + [||||||| + base] + ======= + theirs + >>>
                int skip = 1 + r.ours.size() + (r.base.isEmpty() ? 0 : r.base.size() + 1)
                        + 1 + r.theirs.size() + 1;
                i += skip - 1;
                idx++;
                continue;
            }
            out.add(line);
        }
        return out;
    }
}
