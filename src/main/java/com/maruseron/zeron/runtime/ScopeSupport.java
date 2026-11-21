package com.maruseron.zeron.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/// Host-side structured scope used by `zeron.concurrent.Scope`.
///
/// A scope owns its tasks until they finish. Closing or joining waits for every task, and cancelling
/// interrupts every task before the closing join. The deadline scheduler is a single daemon platform
/// thread shared by all scopes; it only interrupts tasks and never carries user code.
public final class ScopeSupport implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("zeron-scope-deadline"));

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Object lock = new Object();
    private final List<TaskSupport> tasks = new ArrayList<>();
    private boolean closed;
    private boolean cancelled;
    private ScheduledFuture<?> deadline;

    private ScopeSupport(final ExecutorService executor, final boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /// Opens a scope whose tasks run on named virtual threads.
    public static ScopeSupport open() {
        final ThreadFactory factory = Thread.ofVirtual().name("zeron-task-", 0).factory();
        return new ScopeSupport(Executors.newThreadPerTaskExecutor(factory), true);
    }

    /// Opens a scope on an injected executor. The scope never shuts down an injected executor.
    public static ScopeSupport openWith(final ExecutorService executor) {
        if (executor == null) throw new NullPointerException("executor");
        return new ScopeSupport(executor, false);
    }

    /// Starts one task. Effects remain inside the task until `TaskSupport.await` re-raises them.
    public static TaskSupport start(final ScopeSupport scope,
                                    final Object block,
                                    final Object receive) {
        if (scope == null) throw new NullPointerException("scope");
        if (block == null) throw new NullPointerException("block");
        if (receive == null) throw new NullPointerException("receive");
        final var task = new TaskSupport(scope, block, receive);
        synchronized (scope.lock) {
            if (scope.closed || scope.cancelled) {
                throw new IllegalStateException("The scope is closed.");
            }
            scope.tasks.add(task);
        }
        try {
            scope.executor.execute(task.future());
        } catch (final RuntimeException exception) {
            task.cancel();
            throw exception;
        }
        return task;
    }

    public static void join(final ScopeSupport scope) {
        scope.joinAll();
    }

    public static void cancel(final ScopeSupport scope) {
        scope.cancelAll();
    }

    public static void deadline(final ScopeSupport scope, final long nanoseconds) {
        scope.scheduleDeadline(nanoseconds);
    }

    private void joinAll() {
        final List<TaskSupport> snapshot;
        synchronized (lock) {
            closed = true;
            // The deadline remains armed while joining: it is how a joining scope interrupts a task
            // that never finishes on its own. Cancel it only after every task has completed.
            snapshot = List.copyOf(tasks);
        }
        for (final var task : snapshot) task.awaitFinished();
        synchronized (lock) {
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
        }
        if (ownsExecutor) executor.shutdown();
    }

    private void cancelAll() {
        final List<TaskSupport> snapshot;
        synchronized (lock) {
            cancelled = true;
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
            snapshot = List.copyOf(tasks);
        }
        for (final var task : snapshot) task.cancel();
    }

    private void scheduleDeadline(final long nanoseconds) {
        synchronized (lock) {
            if (closed || cancelled) return;
            if (deadline != null) deadline.cancel(false);
            final var delay = Math.max(0L, nanoseconds);
            deadline = DEADLINE_SCHEDULER.schedule(this::cancelAll, delay, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void close() {
        joinAll();
    }

    private static ThreadFactory daemonFactory(final String name) {
        return runnable -> {
            final var thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
