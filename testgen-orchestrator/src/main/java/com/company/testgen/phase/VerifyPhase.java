package com.company.testgen.phase;

import com.company.testgen.phases.AuthSessionProvider;
import com.company.testgen.phases.ProcessExecutor;

import java.time.Duration;

/**
 * Package alias for {@link com.company.testgen.phases.VerifyPhase}.
 */
public class VerifyPhase extends com.company.testgen.phases.VerifyPhase {
    public VerifyPhase(AuthSessionProvider sessions, Duration timeout) {
        super(sessions, timeout);
    }

    public VerifyPhase(AuthSessionProvider sessions, Duration timeout, ProcessExecutor processExecutor) {
        super(sessions, timeout, processExecutor);
    }
}
