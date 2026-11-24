package zeron.runtime;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ContTest {
    @Test
    public void resumesOnceAndBecomesDone() throws Throwable {
        final var delegate = new Delegate(42);
        final var cont = new Cont<String, Integer>(delegate);

        assertFalse(cont.isDone());
        assertEquals(42, (int) cont.resume("answer"));
        assertTrue(cont.isDone());
        assertThrows(IllegalStateException.class, () -> cont.resume("again"));
    }

    @Test
    public void cancelIsIdempotentAndDoesNotResume() throws Throwable {
        final var delegate = new Delegate(42);
        final var cont = new Cont<String, Integer>(delegate);

        cont.cancel();
        assertTrue(delegate.cancelled);
        assertTrue(cont.isDone());

        cont.cancel();
        assertEquals(1, delegate.cancelCalls);
        assertThrows(IllegalStateException.class, () -> cont.resume("late"));
    }

    @Test
    public void failureBecomesTerminal() {
        final var delegate = new Delegate(42);
        delegate.failure = new IllegalArgumentException("boom");
        final var cont = new Cont<String, Integer>(delegate);

        assertThrows(IllegalArgumentException.class, () -> cont.resume("answer"));
        assertTrue(cont.isDone());
    }

    @Test
    public void resumeAndCancelRejectAnotherThread() throws Exception {
        final var cont = new Cont<String, Integer>(new Delegate(42));
        final var failure = new AtomicReference<Throwable>();
        final var thread = new Thread(() -> {
            try {
                cont.resume("off-owner");
            } catch (final Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertFalse(cont.isDone());
    }

    private static final class Delegate implements Cont.Delegate<String, Integer> {
        private final int result;
        private Throwable failure;
        private boolean done;
        private boolean cancelled;
        private int cancelCalls;

        private Delegate(final int result) {
            this.result = result;
        }

        @Override
        public Integer resume(final String response) throws Throwable {
            if (failure != null) throw failure;
            done = true;
            return result;
        }

        @Override
        public void cancel() {
            cancelCalls++;
            cancelled = true;
            done = true;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return new StackTraceElement[0];
        }
    }
}
