package com.maruseron.zeron.runtime;

import zeron.runtime.CancellationSupport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

/// Host-side handle for one task started by `zeron.concurrent.ConcurrentScope`.
///
/// The task body and result receiver are ordinary Zeron function objects. They are invoked through
/// their generated `invoke` method so the runtime accepts effectful bodies without converting them
/// to a Java SAM. A body's effects are captured as a throwable and re-thrown unchanged by `await`,
/// which is the only point where they cross back into the joining thread.
public final class TaskSupport {
    private final ScopeSupport scope;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final FutureTask<Void> future;
    private final CancellationSupport.Request cancellation = new CancellationSupport.Request();
    private volatile boolean started;
    private volatile ScopeSupport.Cause cancellationCause;
    private volatile Throwable failure;
    private volatile boolean failureObserved;

    TaskSupport(final ScopeSupport scope, final Object block, final Object receive) {
        this.scope = scope;
        this.future = new FutureTask<>(() -> {
            run(block, receive);
            return null;
        }) {
            @Override
            protected void done() {
                // Cancellation before the worker starts never enters run(), so close the latch here.
                // Cancellation while running waits for run()'s finally block, after cleanup.
                if (!started) finished.countDown();
            }
        };
    }

    FutureTask<Void> future() {
        return future;
    }

    ScopeSupport scope() {
        return scope;
    }

    ScopeSupport.Cause cancellationCause() {
        return cancellationCause;
    }

    Throwable failure() {
        return failure;
    }

    boolean failureObserved() {
        return failureObserved;
    }

    boolean isGenuineFailure() {
        return failure != null && cancellationCause == null;
    }

    void markFailureObserved() {
        failureObserved = true;
    }

    /// Waits for a task without re-throwing its outcome. This is the operation an `Await`
    /// handler uses; the typed join still reports failure and cancellation.
    public static void waitDone(final TaskSupport task) {
        task.awaitFinished();
    }

    void awaitFinished() {
        var interrupted = false;
        for (;;) {
            try {
                finished.await();
                break;
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /// Waits for the task and re-raises its captured effect, host failure, or cancellation.
    public static void await(final TaskSupport task) {
        task.awaitFinished();
        final var cause = task.cancellationCause;
        if (cause == ScopeSupport.Cause.SCOPE_FAILURE) {
            final var primary = task.scope.primaryFailure();
            if (primary != null && primary.failure != null) {
                primary.failureObserved = true;
                rethrow(primary.failure);
            }
        }
        if (cause != null) {
            final var cancellation = new CancellationException();
            if (task.failure != null) {
                task.failureObserved = true;
                cancellation.addSuppressed(task.failure);
            }
            throw cancellation;
        }
        if (task.failure != null) {
            task.failureObserved = true;
            rethrow(task.failure);
        }
    }

    public boolean isDone() {
        return finished.getCount() == 0;
    }

    public boolean isCancelled() {
        return cancellationCause != null;
    }

    public void cancel() {
        cancelFromScope(ScopeSupport.Cause.USER);
    }

    void cancelFromScope(final ScopeSupport.Cause cause) {
        if (isDone()) return;
        synchronized (this) {
            if (cancellationCause != null) return;
            cancellationCause = cause;
        }
        // The record reaches a body suspended in a continuation through the level-3 driver's
        // checkpoints; the interrupt wakes an interruptible wait on the task thread, and a task
        // that has not started is prevented from running at all.
        cancellation.cancel();
        future.cancel(true);
    }

    private void run(final Object block, final Object receive) {
        started = true;
        CancellationSupport.enter(cancellation);
        try {
            if (cancellationCause != null || Thread.currentThread().isInterrupted()) {
                if (cancellationCause == null) cancellationCause = ScopeSupport.Cause.USER;
                return;
            }
            CancellationSupport.checkpoint();
            final var value = invoke(block, new Object[0]);
            // Cancellation wins over an already-produced value: the scope promised that a cancelled
            // task never publishes a result.
            if (cancellationCause != null) return;
            invoke(receive, new Object[]{value});
        } catch (final Throwable throwable) {
            failure = throwable;
            scope.recordTaskFailure(this, throwable);
        } finally {
            CancellationSupport.exit();
            finished.countDown();
        }
    }

    static void rethrow(final Throwable throwable) {
        TaskSupport.<RuntimeException>sneakyThrowRaw(throwable);
    }

    private static Object invoke(final Object callable, final Object[] arguments) throws Throwable {
        final Method method = findInvoke(callable.getClass(), arguments.length);
        if (method == null) {
            throw new IllegalArgumentException("A task callback must expose invoke"
                    + " with " + arguments.length + " parameter(s).");
        }
        try {
            return method.invoke(callable, arguments);
        } catch (final InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Method findInvoke(final Class<?> type, final int parameterCount) {
        Method fallback = null;
        for (final var method : type.getMethods()) {
            if (!method.getName().equals("invoke") || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (parameterCount == 1 && method.getParameterTypes()[0] == Object.class) {
                method.trySetAccessible();
                return method;
            }
            if (fallback == null) {
                method.trySetAccessible();
                fallback = method;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrowRaw(final Throwable throwable) throws E {
        throw (E) throwable;
    }
}
