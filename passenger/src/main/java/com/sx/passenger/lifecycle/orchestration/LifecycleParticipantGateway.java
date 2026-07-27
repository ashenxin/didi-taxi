package com.sx.passenger.lifecycle.orchestration;

import java.util.Optional;

/** 编排器调用远程生命周期参与者的统一端口。 */
public interface LifecycleParticipantGateway {
    /** 同步执行前置检查并立即取得裁决。 */
    LifecycleParticipantResult executeCheck(LifecycleParticipantCommand command);

    /** 查询异步命令结果；空值表示尚未形成最终结果。 */
    Optional<LifecycleParticipantResult> queryResult(
            String participantCode, String operationNo, String stepCode);
}
