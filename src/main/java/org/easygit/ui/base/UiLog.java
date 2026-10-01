package org.easygit.ui;

import java.util.function.Consumer;

/**
 * 全局 UI 日志管道:git 操作的输出经此进入输出面板。
 * 输出面板未创建时静默丢弃。
 */
public final class UiLog {
    private UiLog() {}

    private static volatile Consumer<String> sink;

    public static void bind(Consumer<String> s) { sink = s; }

    /** 单行日志(自动带时间戳)。以 ✖ 开头的行会自动展开输出面板。 */
    public static void line(String line) {
        Consumer<String> s = sink;
        if (s != null) s.accept(line);
    }

    /** 记录一条 git 命令的完整输出。 */
    public static void op(String op, String out, String err) {
        Consumer<String> s = sink;
        if (s != null) s.accept("\u0001" + op + "\u0001" + (out == null ? "" : out)
                + "\u0001" + (err == null ? "" : err));
    }
}
