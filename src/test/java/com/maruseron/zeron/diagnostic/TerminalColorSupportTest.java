package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TerminalColorSupportTest {
    @Test
    public void enablesColorOnlyForAnInteractiveColorCapableTerminal() {
        assertTrue(TerminalColorSupport.isEnabled(true, null, "xterm-256color"));
        assertFalse(TerminalColorSupport.isEnabled(false, null, "xterm-256color"));
        assertFalse(TerminalColorSupport.isEnabled(true, "1", "xterm-256color"));
        assertFalse(TerminalColorSupport.isEnabled(true, null, "dumb"));
    }
}
