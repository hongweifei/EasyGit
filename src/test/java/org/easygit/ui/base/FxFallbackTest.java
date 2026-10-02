package org.easygit.ui.base;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对话框弹不出来时的兜底:**真正的错误信息必须留下**。
 *
 * 触发场景真实发生过:实例 jar 在运行中被替换 → JVM 懒加载 `Alert$1` 时按旧偏移读到新文件,
 * 抛 `NoClassDefFoundError`,"显示错误的对话框"就建不起来了。而显示错误失败**绝不能**把原始报错
 * 一起吞掉 —— 当时用户只看到「界面更新失败: javafx/scene/control/Alert$1」,真正的失败原因全丢。
 *
 * 本测试跑在没有 JavaFX 工具包的 JUnit 进程里:实测 `new Alert(...)` 会抛
 * `ExceptionInInitializerError`(且不会开窗),所以这里能**确定性**地走到兜底分支。
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FxFallbackTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        UiLog.bind(null);
    }

    @Test
    @DisplayName("Fx.error 建不出对话框时:标题/消息/详情落进输出面板,且不抛异常")
    void errorKeepsRealMessageWhenDialogFails() {
        UiLog.bind(lines::add);
        assertDoesNotThrow(() -> Fx.error("拉取失败",
                "连不上远程（网络 / 代理 / 地址问题）。", "fatal: unable to access 'https://example/x'"));
        String all = String.join("\n", lines);
        assertTrue(all.contains("连不上远程"), "真正的错误消息不能被吞掉: " + lines);
        assertTrue(all.contains("fatal: unable to access"), "原始详情也要留下: " + lines);
        assertTrue(all.contains("对话框无法显示"), "要说明为什么没弹出对话框: " + lines);
    }

    @Test
    @DisplayName("Fx.confirm 建不出确认框时必须当作取消(fail-closed)")
    void confirmFailsClosed() {
        UiLog.bind(lines::add);
        assertFalse(Fx.confirm("丢弃改动", "确定丢弃 x.txt 的工作区改动?此操作不可撤销。"),
                "确认框弹不出来时不能放行破坏性操作");
        assertTrue(String.join("\n", lines).contains("丢弃改动"), "内容要落进日志: " + lines);
    }

    @Test
    @DisplayName("Fx.info 同样兜底")
    void infoFallsBack() {
        UiLog.bind(lines::add);
        assertDoesNotThrow(() -> Fx.info("无法拉取", "当前分支还没有设置上游分支。"));
        assertTrue(String.join("\n", lines).contains("还没有设置上游分支"), "内容要落进日志: " + lines);
    }

    @Test
    @DisplayName("异常描述必须带类型:否则 NoClassDefFoundError 只剩一个类名,看不出发生了什么")
    void describeIncludesType() {
        String s = Fx.describe(new NoClassDefFoundError("javafx/scene/control/Alert$1"));
        assertTrue(s.contains("NoClassDefFoundError"), "要带异常类型: " + s);
        assertTrue(s.contains("Alert$1"), "也要带原始消息: " + s);
        assertEquals("IllegalStateException", Fx.describe(new IllegalStateException()));
    }
}
