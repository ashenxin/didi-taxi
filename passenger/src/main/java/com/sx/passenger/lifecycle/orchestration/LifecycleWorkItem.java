package com.sx.passenger.lifecycle.orchestration;

/** 单次编排事务向外层循环返回的下一步动作。 */
record LifecycleWorkItem(Kind kind, LifecycleParticipantCommand command) {
    /** 远程检查、继续推进、等待异步结果或终止本轮。 */
    enum Kind { REMOTE_CHECK, CONTINUE, WAIT, STOP }

    /** 创建需要在事务外执行的远程检查工作项。 */
    static LifecycleWorkItem remote(LifecycleParticipantCommand command) {
        return new LifecycleWorkItem(Kind.REMOTE_CHECK, command);
    }

    /** 创建不携带远程命令的控制工作项。 */
    static LifecycleWorkItem of(Kind kind) {
        return new LifecycleWorkItem(kind, null);
    }
}
