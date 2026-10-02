package org.easygit.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并行取数(见 {@link Parallel})。
 *
 * 这里的判据不用墙钟时间(那东西随机器负载能差 10 倍),而用**闩锁**:
 * 两路都必须"已经跑起来"才放行 —— 串行实现会在这里卡到超时,所以"真的并行"这件事
 * 是可判定的,不是靠跑得快不快猜的。
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ParallelTest {

    @Test
    @DisplayName("两路真的同时在跑(串行实现会卡死在这里)")
    void bothRunConcurrently() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Parallel.Both<String, String>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try {
                result.set(Parallel.both(
                        () -> { started.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return "first"; },
                        () -> { started.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return "second"; }));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "parallel-test-caller");
        caller.start();

        // 两路都进了"已开始"才放行:只有真的并发才可能到 0
        assertTrue(started.await(10, TimeUnit.SECONDS),
                "两路必须同时在跑 —— 串行实现到不了 started=0");
        release.countDown();
        caller.join(10_000);

        assertNull(failure.get(), "不该有异常: " + failure.get());
        assertNotNull(result.get());
        assertEquals("first", result.get().first());
        assertEquals("second", result.get().second());
    }

    @Test
    @DisplayName("一路抛异常 -> 原样抛出(不能被吞掉,否则刷新会静默失败)")
    void errorPropagates() {
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> Parallel.both(
                () -> { throw new IllegalStateException("第一路失败"); },
                () -> "second"));
        assertTrue(boom.getMessage().contains("第一路失败"), "异常要原样传给调用方");

        // git 层的失败(GitException)同样要被判定为失败,而不是变成"空结果"
        assertThrows(NativeGit.GitException.class, () -> Parallel.both(
                () -> { throw new NativeGit.GitException("读取失败"); },
                () -> "second"));
    }

    @Test
    @DisplayName("调用线程被中断(切仓取消刷新)-> 抛 CancellationException,两路都收到中断")
    void interruptionCancelsBoth() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try {
                Parallel.both(
                        () -> sleepUntilInterrupted(started, interrupted),
                        () -> sleepUntilInterrupted(started, interrupted));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "parallel-test-cancel");
        caller.start();

        assertTrue(started.await(10, TimeUnit.SECONDS), "两路都应已开始");
        caller.interrupt();          // 模拟切仓:外层刷新任务被 Future.cancel(true)
        caller.join(10_000);

        assertInstanceOf(CancellationException.class, thrown.get(),
                "被取消不是失败,应当是 CancellationException(调用方据此静默丢弃)");
        // 中断是"通知",两路需要各自醒来才置位 —— 用闩锁等,不要醒来前就断言
        assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                "两路都必须收到中断,否则它们正在跑的 git 子进程会漏着跑完:" + interrupted.getCount() + " 路没收到");
        assertFalse(caller.isAlive());
    }

    private static String sleepUntilInterrupted(CountDownLatch started, CountDownLatch interrupted) {
        started.countDown();
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            interrupted.countDown();
            throw new RuntimeException(e);
        }
        return "never";
    }
}
