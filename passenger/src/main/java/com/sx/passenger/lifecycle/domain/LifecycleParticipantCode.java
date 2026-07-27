package com.sx.passenger.lifecycle.domain;

/** 生命周期计划中可被编排的业务参与者代码。 */
public enum LifecycleParticipantCode {
    /** 订单域，负责未完成订单等注销条件和订单侧投影。 */
    ORDER,
    /** 钱包域，负责余额、支付单、自动支付协议等资金风险。 */
    WALLET,
    /** 计价权益域，负责优惠券、积分等虚拟资产。 */
    CALCULATE,
    /** 身份域，负责手机号绑定及账号身份提交。 */
    IDENTITY,
    /** 会话域，负责 HTTP/WS 会话失效与认证代次同步。 */
    SESSION,
    /** passenger 本地账号域，负责最终注销提交。 */
    ACCOUNT
}
