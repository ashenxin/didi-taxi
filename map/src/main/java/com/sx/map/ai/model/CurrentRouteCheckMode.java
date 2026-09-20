package com.sx.map.ai.model;

/**
 * 乘客核查当前路线与目标地点关系时真正想确认的含义。
 *
 * 模型只负责从原话中选择该枚举；地图事实仍由确定性的路线核查服务判断。
 * UNSPECIFIED 表示“经过出口”这类表达无法区分路过附近和实际使用出入口，
 * 编排层必须追问，不能自行选择一种含义。
 */
public enum CurrentRouteCheckMode {
    PASS_NEARBY,
    USE_EXIT,
    ENTER_HIGHWAY,
    VISIT_PLACE,
    UNSPECIFIED
}
