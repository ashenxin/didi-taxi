package com.sx.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.dto.AssignOrderBody;
import com.sx.order.model.dto.CancelOrderBody;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.CreateOrderResult;
import com.sx.order.model.dto.DriverIdBody;
import com.sx.order.model.dto.FinishOrderBody;
import com.sx.order.model.dto.OpenDriverOfferBody;
import com.sx.order.model.dto.PendingDispatchOrderDto;
import com.sx.order.service.TripOrderWriteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单核心接口：创建、指派、乘客取消、司机状态推进、分页与详情查询。
 * 统一前缀：{@code /api/v1/orders}；供各 BFF 与内部调度调用。
 * 司机相关写接口错误语义：{@code 403} 非指派司机；{@code 404} 订单不存在；{@code 409} 状态冲突或 CAS 失败。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class TripOrderController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final TripOrderEntityMapper tripOrderEntityMapper;
    private final TripOrderWriteService tripOrderWriteService;

    public TripOrderController(TripOrderEntityMapper tripOrderEntityMapper, TripOrderWriteService tripOrderWriteService) {
        this.tripOrderEntityMapper = tripOrderEntityMapper;
        this.tripOrderWriteService = tripOrderWriteService;
    }

    /**
     * 创建订单：落库 {@code trip_order(status=CREATED)} 并写 {@code order_event(ORDER_CREATED)}。
     * 同一乘客若已有进行中订单（状态非 FINISHED/CANCELLED），返回 {@code code=409}。
     * {@code POST /api/v1/orders}
     */
    @PostMapping
    public ResponseVo<CreateOrderResult> create(@RequestBody @Valid CreateOrderBody body) {
        String orderNo = tripOrderWriteService.create(body);
        return ResultUtil.success(new CreateOrderResult(orderNo));
    }

    /**
     * 指派司机：状态机 {@code CREATED → ASSIGNED}，写入司机/车辆/公司及时戳，并记 {@code ORDER_ASSIGNED}。
     * 若目标司机已有 {@code ACCEPTED}～{@code STARTED} 订单，返回 {@code code=409}（司机正在服务中）。
     * {@code POST /api/v1/orders/{orderNo}/assign}
     */
    @PostMapping("/{orderNo}/assign")
    public ResponseVo<Void> assign(@PathVariable String orderNo, @RequestBody @Valid AssignOrderBody body) {
        try {
            tripOrderWriteService.assign(orderNo, body);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            // 简化处理：参数/状态冲突统一按 400 返回；后续可升级为 409 等更精细的语义
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 打开司机确认窗口：{@code ASSIGNED → PENDING_DRIVER_CONFIRM}，写入 {@code offer_expires_at}。
     * {@code POST /api/v1/orders/{orderNo}/offer/open}
     */
    @PostMapping("/{orderNo}/offer/open")
    public ResponseVo<Void> openDriverOffer(@PathVariable String orderNo,
                                            @RequestBody(required = false) OpenDriverOfferBody body) {
        try {
            tripOrderWriteService.openDriverOffer(orderNo, body != null ? body : new OpenDriverOfferBody());
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 乘客取消：仅允许 {@code CREATED/ASSIGNED/PENDING_DRIVER_CONFIRM/ACCEPTED → CANCELLED}，且校验 {@code passengerId} 归属。
     * {@code POST /api/v1/orders/{orderNo}/cancel}
     */
    @PostMapping("/{orderNo}/cancel")
    public ResponseVo<Void> cancel(@PathVariable String orderNo, @RequestBody @Valid CancelOrderBody body) {
        try {
            tripOrderWriteService.cancelByPassenger(orderNo, body);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage();
            if ("无权操作该订单".equals(msg)) {
                return ResultUtil.error(403, msg);
            }
            return ResultUtil.requestError(msg);
        }
    }

    /**
     * 内部：待派单（{@code CREATED}）列表，供运力服务迟滞匹配；须带起点经纬度。
     * {@code GET /api/v1/orders/internal/pending-dispatch?cityCode=&limit=}
     */
    @GetMapping("/internal/pending-dispatch")
    public ResponseVo<List<PendingDispatchOrderDto>> internalPendingDispatch(
            @RequestParam String cityCode,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        try {
            return ResultUtil.success(tripOrderWriteService.listCreatedForDispatch(cityCode, limit));
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 司机名下「待确认指派」订单列表（{@code ASSIGNED} 或 {@code PENDING_DRIVER_CONFIRM}）。
     * {@code GET /api/v1/orders/assigned}
     * 身份从 {@code X-User-Id} 获取；为兼容旧调用方，可选传 {@code driverId}，但必须与登录身份一致。
     */
    @GetMapping("/assigned")
    public ResponseVo<List<TripOrder>> listAssigned(@RequestParam(required = false) Long driverId,
                                                    @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        try {
            Long authedDriverId = requireAuthedDriverId(userId);
            assertSameDriverIfPresent(driverId, authedDriverId);
            return ResultUtil.success(tripOrderWriteService.listAssignedToDriver(authedDriverId));
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage();
            if ("未授权，请重新登录".equals(msg)) {
                return ResultUtil.error(401, msg);
            }
            if ("禁止操作其他司机数据".equals(msg)) {
                return ResultUtil.error(403, msg);
            }
            return ResultUtil.requestError(msg);
        }
    }

    /**
     * 司机接单：{@code ASSIGNED} 或 {@code PENDING_DRIVER_CONFIRM → ACCEPTED}（幂等：已为 ACCEPTED 则成功）。
     * {@code POST /api/v1/orders/{orderNo}/accept}
     */
    @PostMapping("/{orderNo}/accept")
    public ResponseVo<Void> accept(@PathVariable String orderNo,
                                   @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                   @RequestBody @Valid DriverIdBody body) {
        return handleDriverWrite(() -> {
            Long authedDriverId = requireAuthedDriverId(userId);
            assertSameDriverIfPresent(body == null ? null : body.getDriverId(), authedDriverId);
            tripOrderWriteService.accept(orderNo, authedDriverId);
        });
    }

    /**
     * 到达上车点：{@code ACCEPTED → ARRIVED}。
     * {@code POST /api/v1/orders/{orderNo}/arrive}
     */
    @PostMapping("/{orderNo}/arrive")
    public ResponseVo<Void> arrive(@PathVariable String orderNo,
                                   @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                   @RequestBody @Valid DriverIdBody body) {
        return handleDriverWrite(() -> {
            Long authedDriverId = requireAuthedDriverId(userId);
            assertSameDriverIfPresent(body == null ? null : body.getDriverId(), authedDriverId);
            tripOrderWriteService.arrive(orderNo, authedDriverId);
        });
    }

    /**
     * 开始行程：{@code ARRIVED → STARTED}。
     * {@code POST /api/v1/orders/{orderNo}/start}
     */
    @PostMapping("/{orderNo}/start")
    public ResponseVo<Void> start(@PathVariable String orderNo,
                                  @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                  @RequestBody @Valid DriverIdBody body) {
        return handleDriverWrite(() -> {
            Long authedDriverId = requireAuthedDriverId(userId);
            assertSameDriverIfPresent(body == null ? null : body.getDriverId(), authedDriverId);
            tripOrderWriteService.start(orderNo, authedDriverId);
        });
    }

    /**
     * 完单：{@code STARTED → FINISHED}，写入 {@code final_amount}（未传则回退 {@code estimated_amount}）。
     * {@code POST /api/v1/orders/{orderNo}/finish}
     */
    @PostMapping("/{orderNo}/finish")
    public ResponseVo<Void> finish(@PathVariable String orderNo,
                                   @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                   @RequestBody @Valid FinishOrderBody body) {
        return handleDriverWrite(() -> {
            Long authedDriverId = requireAuthedDriverId(userId);
            assertSameDriverIfPresent(body == null ? null : body.getDriverId(), authedDriverId);
            if (body != null) {
                body.setDriverId(authedDriverId);
            }
            tripOrderWriteService.finish(orderNo, body);
        });
    }

    /**
     * 司机写操作统一异常转业务码：403/404/409/400。
     */
    private static ResponseVo<Void> handleDriverWrite(Runnable action) {
        try {
            action.run();
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage();
            if ("未授权，请重新登录".equals(msg)) {
                return ResultUtil.error(401, msg);
            }
            if ("禁止操作其他司机数据".equals(msg)) {
                return ResultUtil.error(403, msg);
            }
            if ("非本单指派司机".equals(msg)) {
                return ResultUtil.error(403, msg);
            }
            if ("订单不存在".equals(msg)) {
                return ResultUtil.error(404, msg);
            }
            if (msg != null && msg.contains("不允许")) {
                return ResultUtil.error(409, msg);
            }
            if (msg != null && (msg.contains("失败") || msg.contains("请重试"))) {
                return ResultUtil.error(409, msg);
            }
            return ResultUtil.requestError(msg);
        }
    }

    private static Long requireAuthedDriverId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未授权，请重新登录");
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("未授权，请重新登录");
        }
    }

    private static void assertSameDriverIfPresent(Long clientDriverId, Long authedDriverId) {
        if (clientDriverId != null && !clientDriverId.equals(authedDriverId)) {
            throw new IllegalArgumentException("禁止操作其他司机数据");
        }
    }

    /**
     * 分页查询订单列表（内存分页，MVP）。
     * {@code GET /api/v1/orders?orderNo=&passengerId=&provinceCode=&cityCode=&status=&createdAtStart=&createdAtEnd=&pageNo=&pageSize=}
     */
    @GetMapping
    public ResponseVo<Map<String, Object>> page(String orderNo,
                                                Long passengerId,
                                                String provinceCode,
                                                String cityCode,
                                                Integer status,
                                                @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAtStart,
                                                @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAtEnd,
                                                Integer pageNo,
                                                Integer pageSize) {
        int current = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;

        LambdaQueryWrapper<TripOrder> wrapper = Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getIsDeleted, 0)
                .eq(orderNo != null && !orderNo.isBlank(), TripOrder::getOrderNo, orderNo)
                .eq(passengerId != null, TripOrder::getPassengerId, passengerId)
                .eq(provinceCode != null && !provinceCode.isBlank(), TripOrder::getProvinceCode, provinceCode)
                .eq(cityCode != null && !cityCode.isBlank(), TripOrder::getCityCode, cityCode)
                .eq(status != null, TripOrder::getStatus, status)
                .ge(createdAtStart != null, TripOrder::getCreatedAt, createdAtStart)
                .le(createdAtEnd != null, TripOrder::getCreatedAt, createdAtEnd)
                .orderByDesc(TripOrder::getCreatedAt);

        List<TripOrder> allRows = tripOrderEntityMapper.selectList(wrapper);
        int from = Math.max(0, (current - 1) * size);
        int to = Math.min(allRows.size(), from + size);
        List<TripOrder> rows = from >= allRows.size() ? List.of() : allRows.subList(from, to);

        Map<String, Object> resp = new HashMap<>();
        resp.put("list", rows);
        resp.put("total", allRows.size());
        resp.put("pageNo", current);
        resp.put("pageSize", size);
        return ResultUtil.success(resp);
    }

    /**
     * 按订单号查询详情（兼容路径）。
     * {@code GET /api/v1/orders/by-no/{orderNo}}，不存在时 HTTP 404。
     */
    @GetMapping("/by-no/{orderNo}")
    public ResponseEntity<ResponseVo<TripOrder>> getByOrderNo(@PathVariable String orderNo) {
        TripOrder row = tripOrderEntityMapper.selectOne(
                Wrappers.<TripOrder>lambdaQuery()
                        .eq(TripOrder::getOrderNo, orderNo)
                        .eq(TripOrder::getIsDeleted, 0)
                        .last("LIMIT 1"));
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ResultUtil.success(row));
    }

    /**
     * 按订单号查询详情（主路径）。
     * {@code GET /api/v1/orders/{orderNo}}，不存在时 HTTP 404。
     * 注意：静态路径 {@code /assigned} 优先匹配，不会被本方法当成订单号。
     */
    @GetMapping("/{orderNo}")
    public ResponseEntity<ResponseVo<TripOrder>> getByOrderNoV2(@PathVariable String orderNo) {
        return getByOrderNo(orderNo);
    }
}
