package com.sx.passenger.ai;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** AI 客服会话、消息与请求的业务编号，服务端生成且不透明，不能信任客户端自报。 */
@Component
public class AiIdentifierGenerator {

    public String nextConversationNo() {
        return "AIC" + compactUuid();
    }

    public String nextMessageNo() {
        return "AIM" + compactUuid();
    }

    public String nextRequestNo() {
        return "REQ" + compactUuid();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
