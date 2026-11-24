package com.maruseron.zeron.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/// Host-side structured scope used by `zeron.concurrent.ConcurrentScope`.
///
/// A scope owns its tasks until they finish. Closing or joining waits for every task, and cancelling
/// interrupts every task before the closing join. The default scope is fail-fast: the first genuine
/// task failure cancels its siblings and is reported at close when no join observed it.
public final class ScopeSupport implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("zeron-scope-deadline"));

    public enum Cause {
        USER,
        DEADLINE,
        SCOPE_FAILURE
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final boolean failFast;
    private final Object lock = new Object();
    private final List<TaskSupport> tasks = new ArrayList<>();
    private boolean closed;
    private Cause cancellationCause;
    private TaskSupport primaryFailure;
    private ScheduledFuture<?> deadline;

    private ScopeSupport(final ExecutorService executor, final boolean ownsExecutor,
                         final boolean failFast) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.failFast = failFast;
    }

    /// Opens a fail-fast scope whose tasks run on named virtual threads.
    public static ScopeSupport open() {
        final ThreadFactory factory = Thread.ofVirtual().name("zeron-task-", 0).factory();
        return new ScopeSupport(Executors.newThreadPerTaskExecutor(factory), true, true);
    }

    /// Opens a manual scope whose tasks run on named virtual threads.
    public static ScopeSupport openManual() {
        final ThreadFactory factory = Thread.ofVirtual().name("zeron-task-", 0).factory();
        return new ScopeSupport(Executors.newThreadPerTaskExecutor(factory), true, false);
    }

    /// Opens a fail-fast scope on an injected executor. The scope never shuts down an injected
    /// executor.
    public static ScopeSupport openWith(final ExecutorService executor) {
        if (executor == null) throw new NullPointerException("executor");
        return new ScopeSupport(executor, false, true);
    }

    /// Opens a manual scope on an injected executor.
    public static ScopeSupport openManualWith(final ExecutorService executor) {
        if (executor == null) throw new NullPointerException("executor");
        return new ScopeSupport(executor, false, false);
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
            if (scope.closed || scope.cancellationCause != null) {
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
        scope.cancelAll(Cause.USER);
    }

    public static void deadline(final ScopeSupport scope, final long nanoseconds) {
        scope.scheduleDeadline(nanoseconds);
    }

    /// Joins every task and reports the first unobserved genuine failure in fail-fast mode.
    public static void dispose(final ScopeSupport scope) {
        scope.joinAll();
        scope.reportUnobservedFailure();
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

    void cancelAll(final Cause cause) {
        final List<TaskSupport> snapshot;
        synchronized (lock) {
            if (cancellationCause == null) cancellationCause = cause;
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
            snapshot = List.copyOf(tasks);
        }
        for (final var task : snapshot) task.cancelFromScope(cause);
    }

    void recordTaskFailure(final TaskSupport task, final Throwable failure) {
        final List<TaskSupport> siblings;
        synchronized (lock) {
            if (!failFast || cancellationCause != null || task.cancellationCause() != null) return;
            cancellationCause = Cause.SCOPE_FAILURE;
            primaryFailure = task;
            siblings = new ArrayList<>(tasks);
            siblings.remove(task);
        }
        for (final var sibling : siblings) sibling.cancelFromScope(Cause.SCOPE_FAILURE);
    }

    TaskSupport primaryFailure() {
        synchronized (lock) {
            return primaryFailure;
        }
    }

    private void reportUnobservedFailure() {
        final TaskSupport reported;
        final List<TaskSupport> suppressed = new ArrayList<>();
        synchronized (lock) {
            if (!failFast) return;
            TaskSupport first = null;
            for (final var task : tasks) {
                if (task.isGenuineFailure() && !task.failureObserved()) {
                    if (first == null) first = task;
                    else suppressed.add(task);
                }
            }
            reported = first;
            if (reported != null) reported.markFailureObserved();
            for (final var task : suppressed) task.markFailureObserved();
        }
        if (reported == null) return;
        for (final var task : suppressed) {
            final var failure = task.failure();
            if (failure != null) reported.failure().addSuppressed(failure);
        }
        TaskSupport.rethrow(reported.failure());
    }

    private void scheduleDeadline(final long nanoseconds) {
        synchronized (lock) {
            if (closed || cancellationCause != null) return;
            if (deadline != null) deadline.cancel(false);
            final var delay = Math.max(0L, nanoseconds);
            deadline = DEADLINE_SCHEDULER.schedule(
                    () -> cancelAll(Cause.DEADLINE), delay, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void close() {
        dispose(this);
    }

    private static ThreadFactory daemonFactory(final String name) {
        return runnable -> {
            final var thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
