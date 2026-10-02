package org.easygit.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次刷新里**互不依赖**的两项只读工作,并行执行。
 *
 * <p>这台机器上"每个 git 子进程 100~200ms",而一次刷新里的两项读取本来是**串着**跑的,
 * 于是每次切仓都白等一整个进程的时间。实测(288 提交的仓库,7 轮中位数):
 * <pre>
 *   阶段一 status + for-each-ref   串行 223ms → 并行 128ms
 *   历史   log  + rev-list         串行 239ms → 并行 147ms
 * </pre>
 * 进程数一个都不多(还是 2 个),只是不再排队等对方。
 *
 * <p><b>取消语义</b>:外层刷新任务被切仓取消时,线程会被中断,这里立刻把两路都
 * {@code cancel(true)} —— {@link GitProcess} 收到中断会 {@code destroyForcibly} 掉 git 子进程,
 * 不会留下"没人要的进程还在扫 790MB 工作区"。中断标记会原样保留给调用方(其线程池负责清)。
 *
 * <p><b>不许嵌套</b>:{@link #both} 会阻塞等待,只能从别的线程池(如 UI 的后台池)调用,
 * 不能在 {@code easygit-read} 自己的线程里递归调用,否则会把自己的池占满。
 */
public final class Parallel {

    private Parallel() {}

    /** 两路结果。字段名固定 first/second,调用点用哪个顺序就按哪个顺序读。 */
    public record Both<A, B>(A first, B second) {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "easygit-read");
        t.setDaemon(true);
        return t;
    });

    /** 累计派发的并行子任务数(诊断:探针用来确认"读数确实是两路并发")。 */
    private static final AtomicInteger TASKS = new AtomicInteger();

    public static int tasks() { return TASKS.get(); }

    /**
     * 并行跑两项工作并等两者都完成。
     *
     * @throws CancellationException 当前线程被中断(切仓取消刷新)—— 两路子任务已一并取消
     */
    public static <A, B> Both<A, B> both(Callable<A> first, Callable<B> second) {
        Future<A> fa = submit(first);
        Future<B> fb = submit(second);
        try {
            B b = fb.get();
            A a = fa.get();
            return new Both<>(a, b);
        } catch (InterruptedException e) {
            fa.cancel(true);
            fb.cancel(true);
            Thread.currentThread().interrupt();     // 中断语义交给调用方,别在这里吞掉
            throw new CancellationException("刷新已取消");
        } catch (ExecutionException e) {
            fa.cancel(true);
            fb.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private static <T> Future<T> submit(Callable<T> work) {
        TASKS.incrementAndGet();
        return POOL.submit(work);
    }
}
