package com.sx.map.ai.dto;

/**
 * 起终点提取结果。缺失端点为 null，由调用方走追问分支；
 * intent 本期仅供后续分支扩展，主干编排不据此分流。
 */
public record ExtractEndpointsResponse(String originName,
                                      String destinationName,
                                      String originRegion,
                                      String destinationRegion,
                                      String intent) {
}
