package com.sx.order.lifecycle.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.order.lifecycle.dao.OrderLifecycleParticipantInboxMapper;
import com.sx.order.lifecycle.exception.OrderLifecycleCommandConflictException;
import com.sx.order.lifecycle.model.OrderLifecycleBlocker;
import com.sx.order.lifecycle.model.OrderLifecycleCommand;
import com.sx.order.lifecycle.model.OrderLifecycleDecision;
import com.sx.order.lifecycle.model.OrderLifecycleParticipantInbox;
import com.sx.order.lifecycle.model.OrderLifecycleParticipantResult;
import com.sx.order.lifecycle.model.OrderLifecyclePrecheckRequest;
import com.sx.order.model.dto.BlockingOrderResult;
import com.sx.order.service.TripOrderWriteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class AccountLifecycleOrderParticipantService {
    public static final String ORDER_FINAL_CHECK = "ORDER_FINAL_CHECK";
    private static final String COMPLETED = "COMPLETED";

    private final OrderLifecycleParticipantInboxMapper inboxes;
    private final OrderLifecycleProjectionService projections;
    private final TripOrderWriteService orders;
    private final OrderLifecycleRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public AccountLifecycleOrderParticipantService(OrderLifecycleParticipantInboxMapper inboxes,
                                                   OrderLifecycleProjectionService projections,
                                                   TripOrderWriteService orders,
                                                   OrderLifecycleRequestHasher hasher,
                                                   ObjectMapper objectMapper) {
        this.inboxes = inboxes;
        this.projections = projections;
        this.orders = orders;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OrderLifecycleParticipantResult precheck(OrderLifecyclePrecheckRequest request) {
        return mapBlocking(orders.inspectBlockingOrder(request.customerId()));
    }

    @Transactional
    public OrderLifecycleParticipantResult fence(OrderLifecycleCommand command) {
        validateCommand(command);
        String operationNo = command.operationNo().trim();
        String stepCode = command.stepCode().trim();
        String requestHash = hasher.hash(command);

        OrderLifecycleParticipantInbox prior = inboxes.find(operationNo, stepCode);
        if (prior != null) {
            return replayOrConflict(prior, requestHash);
        }

        LocalDateTime now = LocalDateTime.now();
        OrderLifecycleParticipantInbox inbox = new OrderLifecycleParticipantInbox()
                .setOperationNo(operationNo)
                .setStepCode(stepCode)
                .setCustomerId(command.customerId())
                .setRequestHash(requestHash)
                .setStatus("PROCESSING")
                .setDecision(OrderLifecycleDecision.UNKNOWN.name())
                .setBlockerSnapshot("[]")
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            if (inboxes.insert(inbox) != 1) {
                throw new IllegalStateException("Order生命周期参与者命令占位失败");
            }
        } catch (DuplicateKeyException ex) {
            OrderLifecycleParticipantInbox raced = inboxes.findForUpdate(operationNo, stepCode);
            if (raced == null) {
                throw new IllegalStateException("Order生命周期参与者并发命令状态未知", ex);
            }
            return replayOrConflict(raced, requestHash);
        }

        projections.applyUnderLock(command.toProjectionCommand());
        OrderLifecycleParticipantResult result = mapBlocking(orders.findBlockingOrder(command.customerId()));
        inbox.setStatus(COMPLETED)
                .setDecision(result.decision().name())
                .setBlockerSnapshot(writeBlockers(result.blockers()))
                .setUpdatedAt(LocalDateTime.now());
        if (inboxes.updateById(inbox) != 1) {
            throw new IllegalStateException("Order生命周期参与者结果写入失败");
        }
        return result;
    }

    @Transactional(readOnly = true)
    public OrderLifecycleParticipantResult findResult(String operationNo, String stepCode) {
        validateStepCode(stepCode);
        if (operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo不能为空");
        }
        OrderLifecycleParticipantInbox inbox = inboxes.find(operationNo.trim(), stepCode.trim());
        return inbox == null ? null : fromInbox(inbox);
    }

    private OrderLifecycleParticipantResult replayOrConflict(
            OrderLifecycleParticipantInbox prior, String requestHash) {
        if (!Objects.equals(prior.getRequestHash(), requestHash)) {
            throw new OrderLifecycleCommandConflictException(
                    "同一operationNo和stepCode不能用于不同生命周期命令");
        }
        if (!COMPLETED.equals(prior.getStatus())) {
            throw new IllegalStateException("Order生命周期参与者结果尚未完成");
        }
        return fromInbox(prior);
    }

    private OrderLifecycleParticipantResult fromInbox(OrderLifecycleParticipantInbox inbox) {
        try {
            List<OrderLifecycleBlocker> blockers = objectMapper.readValue(
                    inbox.getBlockerSnapshot(), new TypeReference<>() {});
            return new OrderLifecycleParticipantResult(
                    OrderLifecycleDecision.valueOf(inbox.getDecision()), blockers);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new IllegalStateException("Order生命周期参与者结果无法解析", ex);
        }
    }

    private String writeBlockers(List<OrderLifecycleBlocker> blockers) {
        try {
            return objectMapper.writeValueAsString(blockers);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Order生命周期阻塞项无法序列化", ex);
        }
    }

    private static OrderLifecycleParticipantResult mapBlocking(BlockingOrderResult blocking) {
        if (blocking == null) {
            return new OrderLifecycleParticipantResult(OrderLifecycleDecision.PASS, List.of());
        }
        OrderLifecycleBlocker blocker;
        if ("IN_PROGRESS".equals(blocking.settlementStatus())) {
            blocker = new OrderLifecycleBlocker("ACTIVE_ORDER", "ORDER",
                    blocking.blockingOrderNo(), "CANCEL_ORDER");
        } else if ("PAYMENT_REQUIRED".equals(blocking.settlementStatus())) {
            blocker = new OrderLifecycleBlocker("UNPAID_ORDER", "ORDER",
                    blocking.blockingOrderNo(), "PAY_OUTSTANDING");
        } else {
            blocker = new OrderLifecycleBlocker("SETTLEMENT_UNKNOWN", "ORDER",
                    blocking.blockingOrderNo(), "CONTACT_OPERATIONS");
        }
        return new OrderLifecycleParticipantResult(OrderLifecycleDecision.BLOCKED, List.of(blocker));
    }

    private static void validateCommand(OrderLifecycleCommand command) {
        validateStepCode(command.stepCode());
        if (!"CANCELLING".equals(command.targetLifecycleStatus().trim())) {
            throw new IllegalArgumentException("Order生命周期参与者只接受CANCELLING目标状态");
        }
    }

    private static void validateStepCode(String stepCode) {
        if (stepCode == null || !ORDER_FINAL_CHECK.equals(stepCode.trim())) {
            throw new IllegalArgumentException("未知的Order生命周期stepCode");
        }
    }
}
