/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    //参数一：内部线程数量 ，假设当前平台为 8 cpu 平台，此时 DEFAULT_EVENT_LOOP_THREADS == 16
    //参数二：执行器 null
    //参数三：选择器提供器，通过这个可以获取到jdk层面的selector实例。 args[0] == selectorProvider
    //参数四：选择器工作策略 DefaultSelectStrategy   args[1] == selectStrategy
    //参数五：线程池拒绝策略 args[2]
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        //参数一：内部线程数量 ，假设当前平台为 8 cpu 平台，此时 DEFAULT_EVENT_LOOP_THREADS == 16
        //参数二：执行器 null
        //参数三：ChooserFactory 用来生成Chooser实例。
        //参数四：选择器提供器，通过这个可以获取到jdk层面的selector实例。 args[0] == selectorProvider
        //参数五：选择器工作策略 DefaultSelectStrategy   args[1] == selectStrategy
        //参数六：线程池拒绝策略 args[2]
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    //参数一：内部线程数量 ，假设当前平台为 8 cpu 平台，此时 DEFAULT_EVENT_LOOP_THREADS == 16
    //参数二：执行器 null
    //参数三：ChooserFactory 用来生成Chooser实例。
    //参数四：选择器提供器，通过这个可以获取到jdk层面的selector实例。 args[0] == selectorProvider
    //参数五：选择器工作策略 DefaultSelectStrategy   args[1] == selectStrategy
    //参数六：线程池拒绝策略 args[2]
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (executor == null) {
            // 真正生产出来执行任务的线程的作用，executor
            // newDefaultThreadFactory() 创建了一个线程工厂，线程工厂内具有prefix字段，命名规则 className + poolId
            // 通过这个线程工厂 创建出来的 线程实例，线程名称为 className + poolId + 线程id，并且线程实例类型为: FastThreadLocalThread
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        // 这里假设平台是 8 核心 ，这里会创建 长度是 16 的 EventExecutor 数组
        children = new EventExecutor[nThreads];

        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                // newChild(...) 都会返回一个 NioEventLoop 实例

                // executor: ThreadPerTaskExecutor实例，这个实例里面包含着一个 ThreadFactory实例，PerTaskExecutor通过内部线程工厂可以制造出来线程，
                // 并且线程名称为 className + poolId + 线程id,并且线程实例类型为：FastThreadLocalThread
                //参数一：选择器提供器，通过这个可以获取到jdk层面的selector实例。 args[0] == selectorProvider
                //参数二：选择器工作策略 DefaultSelectStrategy   args[1] == selectStrategy
                //参数三：线程池拒绝策略 args[2]
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // 通过 ChooserFactory 根据当前children数量 构建一个合适的 chooser 实例。
        // 后面，外部资源想要 获取 或者 注册... 到 NioEventLoop，都是通过chooser 来分配NioEventLoop的。
        chooser = chooserFactory.newChooser(children);

        // 结束监听
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
