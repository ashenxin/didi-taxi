package com.sx.map.ai.model;

/**
 * 乘客明确表达的当前路线通行含义。
 *
 * 模型只从原话中提取“驶入/驶出/进入/附近经过”等明确动作；地图事实仍由确定性的
 * 路线核查服务判断。只问“是否经过”而不带动作时，编排层不再追问含义，而是按
 * “实际通过、仅附近经过、不经过、无法确认”给出复合关系结论。
 *
 * UNSPECIFIED 为兼容旧调用保留，语义等同于“未指定动作，请按复合关系核查”。
 */
public enum CurrentRouteCheckMode {
    PASS_NEARBY,
    USE_EXIT,
    ENTER_HIGHWAY,
    VISIT_PLACE,
    UNSPECIFIED
}
