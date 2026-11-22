package com.maruseron.zeron.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

/// Host-side handle for one task started by `zeron.concurrent.Scope`.
///
/// The task body and result receiver are ordinary Zeron function objects. They are invoked through
/// their generated `invoke` method so the runtime accepts effectful bodies without converting them
/// to a Java SAM. A body's effects are captured as a throwable and re-thrown unchanged by `await`,
/// which is the only point where they cross back into the joining thread.
public final class TaskSupport {
    private final ScopeSupport scope;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final FutureTask<Void> future;
    private volatile boolean started;
    private volatile boolean cancelled;
    private volatile Throwable failure;

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
        if (task.cancelled) {
            final var cancellation = new CancellationException("The task was cancelled.");
            if (task.failure != null) cancellation.addSuppressed(task.failure);
            throw cancellation;
        }
        if (task.failure != null) sneakyThrow(task.failure);
    }

    public boolean isDone() {
        return finished.getCount() == 0;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        if (isDone()) return;
        cancelled = true;
        // Interrupt a running virtual thread so blocking calls and `using` cleanup can observe
        // cancellation. A task that has not started is prevented from running at all.
        future.cancel(true);
    }

    private void run(final Object block, final Object receive) {
        started = true;
        try {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                cancelled = true;
                return;
            }
            final var value = invoke(block, new Object[0]);
            // Cancellation wins over an already-produced value: the scope promised that a cancelled
            // task never publishes a result.
            if (cancelled) return;
            invoke(receive, new Object[]{value});
        } catch (final Throwable throwable) {
            failure = throwable;
        } finally {
            finished.countDown();
        }
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
    private static <T, E extends Throwable> T sneakyThrow(final Throwable throwable) throws E {
        throw (E) throwable;
    }
}
