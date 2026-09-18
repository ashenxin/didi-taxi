package com.sx.map.ai.model;

/**
 * 乘客提问经编排层识别后的业务意图。短时地图上下文保存此值和原始文字，
 * 后续确认或选项选择只恢复原意图，不重新从一条“对”等简短回复猜测业务动作。
 */
public enum PassengerRouteIntent {
    CURRENT_ROUTE_CHECK,
    ROUTE_EXISTS,
    VIA_ROUTE_PLAN,
    TOLL_ROUTE_OPTIONS,
    DEFAULT_ROUTE
}
