package com.sx.passenger.lifecycle.orchestration;

record LifecycleWorkItem(Kind kind, LifecycleParticipantCommand command) {
    enum Kind { REMOTE_CHECK, CONTINUE, WAIT, STOP }

    static LifecycleWorkItem remote(LifecycleParticipantCommand command) {
        return new LifecycleWorkItem(Kind.REMOTE_CHECK, command);
    }

    static LifecycleWorkItem of(Kind kind) {
        return new LifecycleWorkItem(kind, null);
    }
}
