package zeron.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SuspensionGuardTest {
    @Test
    public void markerNestsAndUnwinds() {
        assertEquals(0, SuspensionGuard.depth());
        SuspensionGuard.enter(SuspensionGuard.HOST_CALLBACK);
        SuspensionGuard.enter("native");
        assertEquals(2, SuspensionGuard.depth());
        SuspensionGuard.exit();
        assertEquals(1, SuspensionGuard.depth());
        SuspensionGuard.exit();
        assertEquals(0, SuspensionGuard.depth());
    }

    @Test
    public void suspensionAcrossAHostRegionIsRejected() {
        SuspensionGuard.enter(SuspensionGuard.HOST_CALLBACK);
        try {
            final var failure = assertThrows(SuspensionGuard.BoundaryException.class,
                    () -> SuspensionGuard.requireUnchanged(0));
            assertEquals("Cannot suspend a continuation across a host callback boundary.",
                    failure.getMessage());
        } finally {
            SuspensionGuard.exit();
        }
    }

    @Test
    public void handlerInstalledInsideTheHostRegionMaySuspend() {
        SuspensionGuard.enter(SuspensionGuard.HOST_CALLBACK);
        try {
            SuspensionGuard.requireUnchanged(SuspensionGuard.depth());
        } finally {
            SuspensionGuard.exit();
        }
    }
}
