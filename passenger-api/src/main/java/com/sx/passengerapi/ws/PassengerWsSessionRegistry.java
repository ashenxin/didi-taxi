package com.sx.passengerapi.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class PassengerWsSessionRegistry {

    private static final Set<String> ALLOWED_CLOSE_REASONS = Set.of(
            "logout", "phone_changed", "account_cancelling", "account_cancelled");

    private static final class CustomerFence {
        private long generation;
    }

    private static final class FenceReference extends WeakReference<CustomerFence> {
        private final long customerId;

        private FenceReference(long customerId,
                               CustomerFence referent,
                               ReferenceQueue<CustomerFence> queue) {
            super(referent, queue);
            this.customerId = customerId;
        }
    }

    /** 握手在 DB 权威回查前捕获，连接建立时用于拒绝本地撤销之后到达的旧握手。 */
    public static class RegistrationPermit {
        private final long customerId;
        private final CustomerFence fence;
        private final long generation;

        private RegistrationPermit(long customerId, CustomerFence fence, long generation) {
            this.customerId = customerId;
            this.fence = fence;
            this.generation = generation;
        }
    }

    public static class PassengerSession {
        private final long customerId;
        private final WebSocketSession session;
        private final CustomerFence fence;
        private final AtomicLong lastSeenAtMs = new AtomicLong(System.currentTimeMillis());

        private PassengerSession(long customerId, WebSocketSession session, CustomerFence fence) {
            this.customerId = customerId;
            this.session = session;
            this.fence = fence;
        }

        public long getCustomerId() {
            return customerId;
        }

        public WebSocketSession getSession() {
            return session;
        }

        public void touch() {
            lastSeenAtMs.set(System.currentTimeMillis());
        }

        public long lastSeenAtMs() {
            return lastSeenAtMs.get();
        }
    }

    private final Map<Long, PassengerSession> byCustomerId = new ConcurrentHashMap<>();
    private final Map<String, Long> customerIdBySessionId = new ConcurrentHashMap<>();
    private final Map<Long, FenceReference> fences = new ConcurrentHashMap<>();
    private final ReferenceQueue<CustomerFence> collectedFences = new ReferenceQueue<>();

    public RegistrationPermit captureRegistration(long customerId) {
        if (customerId <= 0) {
            throw new IllegalArgumentException("invalid customerId");
        }
        cleanupCollectedFences();
        CustomerFence fence = findOrCreateFence(customerId);
        synchronized (fence) {
            return new RegistrationPermit(customerId, fence, fence.generation);
        }
    }

    /**
     * 注册会话；generation 不匹配时拒绝迟到握手，同乘客并发注册在同一 fence 上串行替换。
     */
    public boolean register(RegistrationPermit permit, WebSocketSession session) {
        if (permit == null || permit.customerId <= 0 || session == null) {
            safeClose(session, CloseStatus.NOT_ACCEPTABLE);
            return false;
        }
        cleanupCollectedFences();
        CustomerFence fence = permit.fence;
        synchronized (fence) {
            if (fence.generation != permit.generation) {
                safeClose(session, new CloseStatus(4001, "auth_epoch_changed"));
                return false;
            }
            long customerId = permit.customerId;
            PassengerSession previous = byCustomerId.get(customerId);
            if (previous != null && previous.getSession() != null
                    && !previous.getSession().getId().equals(session.getId())) {
                customerIdBySessionId.remove(previous.getSession().getId(), customerId);
                safeClose(previous.getSession(), new CloseStatus(4000, "replaced"));
                log.info("WS replaced previous session customerId={} oldSessionId={}",
                        customerId, previous.getSession().getId());
            }
            PassengerSession current = new PassengerSession(customerId, session, fence);
            byCustomerId.put(customerId, current);
            customerIdBySessionId.put(session.getId(), customerId);
            log.info("WS session registered customerId={} sessionId={}", customerId, session.getId());
            return true;
        }
    }

    public PassengerSession get(long customerId) {
        return byCustomerId.get(customerId);
    }

    public PassengerSession getBySessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Long customerId = customerIdBySessionId.get(sessionId);
        PassengerSession current = customerId == null ? null : byCustomerId.get(customerId);
        return current != null && sessionId.equals(current.getSession().getId()) ? current : null;
    }

    public Collection<PassengerSession> allSessions() {
        return byCustomerId.values();
    }

    public void removeBySession(WebSocketSession session) {
        if (session == null) {
            return;
        }
        Long customerId = customerIdBySessionId.get(session.getId());
        if (customerId == null) {
            return;
        }
        PassengerSession current = byCustomerId.get(customerId);
        if (current == null) {
            customerIdBySessionId.remove(session.getId(), customerId);
            return;
        }
        synchronized (current.fence) {
            customerIdBySessionId.remove(session.getId(), customerId);
            if (session.getId().equals(current.getSession().getId())) {
                byCustomerId.remove(customerId, current);
                log.info("WS session removed customerId={} sessionId={}", customerId, session.getId());
            }
        }
        cleanupCollectedFences();
    }

    public void closeCustomerSessions(long customerId, String reason) {
        cleanupCollectedFences();
        FenceReference reference = fences.get(customerId);
        CustomerFence fence = reference == null ? null : reference.get();
        if (fence == null) {
            return;
        }
        synchronized (fence) {
            fence.generation++;
            PassengerSession current = byCustomerId.remove(customerId);
            if (current == null || current.getSession() == null) {
                return;
            }
            customerIdBySessionId.remove(current.getSession().getId(), customerId);
            safeClose(current.getSession(), new CloseStatus(4001, sanitizeReason(reason)));
        }
    }

    private CustomerFence findOrCreateFence(long customerId) {
        while (true) {
            FenceReference currentReference = fences.get(customerId);
            CustomerFence current = currentReference == null ? null : currentReference.get();
            if (current != null) {
                return current;
            }
            CustomerFence created = new CustomerFence();
            FenceReference createdReference = new FenceReference(customerId, created, collectedFences);
            boolean installed = currentReference == null
                    ? fences.putIfAbsent(customerId, createdReference) == null
                    : fences.replace(customerId, currentReference, createdReference);
            if (installed) {
                return created;
            }
        }
    }

    /** 清理由已放弃握手留下的弱 fence 引用；心跳任务也会周期调用。 */
    public void cleanupCollectedFences() {
        FenceReference collected;
        while ((collected = (FenceReference) collectedFences.poll()) != null) {
            fences.remove(collected.customerId, collected);
        }
    }

    private static String sanitizeReason(String reason) {
        return ALLOWED_CLOSE_REASONS.contains(reason) ? reason : "auth_epoch_changed";
    }

    public void safeClose(WebSocketSession session, CloseStatus status) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status == null ? CloseStatus.NORMAL : status);
            }
        } catch (IOException e) {
            log.debug("WS close ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }

    public void safeSendText(WebSocketSession session, String text) {
        if (session == null || text == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.debug("WS send ignored sessionId={} err={}", session.getId(), e.toString());
        }
    }
}
