package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.*;
import com.sx.order.model.*;
import com.sx.order.model.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DriverTripFlowIntegrationTest {
    private static final long A=770001L,B=770002L;
    @Autowired TripOrderWriteService writes;
    @Autowired DriverTripQueryService queries;
    @Autowired TripOrderEntityMapper orders;
    @Autowired DriverTripRecordMapper trips;
    @Autowired TripOrderSettlementMapper settlements;
    @Autowired OrderEventEntityMapper events;

    @Test void acceptsOnlyOnceForReplayAndRepeatedIntent() {
        TripOrder o=order(A,7);
        writes.accept(o.getOrderNo(),A,"trip-accept-"+o.getId());
        writes.accept(o.getOrderNo(),A,"trip-accept-"+o.getId());
        writes.accept(o.getOrderNo(),A,"trip-other-"+o.getId());
        assertThat(page(A).list()).filteredOn(t->t.getOrderNo().equals(o.getOrderNo())).hasSize(1);
        assertThat(record(o,A).getStatus()).isEqualTo(2);
    }
    @Test void redispatchKeepsCancelledEpisodeAndHidesSuccessorBill() {
        TripOrder o=order(A,7);
        writes.accept(o.getOrderNo(),A,key());
        DriverTripRecord first=record(o,A);
        writes.driverCancelBeforeArrive(o.getOrderNo(),A,"DRIVER_LOGOUT",key());
        assertThat(orders.selectById(o.getId()).getDriverId()).isNull();
        AssignOrderBody assign=new AssignOrderBody();assign.setDriverId(B);assign.setCompanyId(202L);assign.setCarId(303L);
        writes.assign(o.getOrderNo(),assign);
        writes.accept(o.getOrderNo(),B,key());
        writes.arrive(o.getOrderNo(),B,key());writes.start(o.getOrderNo(),B,key());
        FinishOrderBody finish=new FinishOrderBody();finish.setDriverId(B);
        String finishKey=key();writes.finish(o.getOrderNo(),finish,finishKey);writes.finish(o.getOrderNo(),finish,finishKey);
        TripOrderSettlement bill=settlements.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery().eq(TripOrderSettlement::getOrderNo,o.getOrderNo()));
        bill.setFinalAmount(new BigDecimal("100")).setCouponDiscountAmount(new BigDecimal("20"))
                .setPayableAmount(new BigDecimal("80")).setPaidAmount(new BigDecimal("80")).setSettlementStatus("PAID");
        settlements.updateById(bill);
        var a=queries.detail(A,first.getId());
        assertThat(a.getStatus()).isEqualTo(6);assertThat(a.getCancelReason()).isEqualTo("DRIVER_LOGOUT");
        assertThat(a.getFinalAmount()).isNull();assertThat(a.getPaidAmount()).isNull();
        assertThat(a.getDiscountAmount()).isNull();assertThat(a.getBillingDurationSeconds()).isNull();
        assertThat(a.getFinishedAt()).isNull();assertThat(a.getCompanyId()).isEqualTo(101L);
        var b=queries.detail(B,record(o,B).getId());
        assertThat(b.getStatus()).isEqualTo(5);assertThat(b.getFinalAmount()).isEqualByComparingTo("100");
        assertThat(b.getCompanyId()).isEqualTo(202L);assertThat(b.getArrivedAt()).isNotNull();
        assertThat(b.getServiceDurationSeconds()).isNotNegative();
        assertThatThrownBy(()->queries.detail(B,first.getId())).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode().value()).isEqualTo(404));
    }
    @Test void passengerCancellationIsRecordedOnlyAfterAcceptance() {
        TripOrder accepted=order(A,7);writes.accept(accepted.getOrderNo(),A,key());
        CancelOrderBody cancel=new CancelOrderBody();cancel.setPassengerId(accepted.getPassengerId());cancel.setCancelReason("计划变化");
        writes.cancelByPassenger(accepted.getOrderNo(),cancel,key());
        assertThat(record(accepted,A).getCancelBy()).isEqualTo(1);
        TripOrder pending=order(A,7);cancel.setPassengerId(pending.getPassengerId());
        writes.cancelByPassenger(pending.getOrderNo(),cancel,key());
        assertThat(trips.selectCount(Wrappers.<DriverTripRecord>lambdaQuery().eq(DriverTripRecord::getOrderNo,pending.getOrderNo()))).isZero();
    }
    @Test void rejectionAndOfferTimeoutAreNotTrips() {
        TripOrder reject=order(A,7);writes.rejectByDriver(reject.getOrderNo(),A,"TOO_FAR",key());
        TripOrder timeout=order(A,7);timeout.setOfferExpiresAt(LocalDateTime.now().minusMinutes(1));orders.updateById(timeout);
        writes.timeoutPendingDriverOffers(LocalDateTime.now());
        assertThat(trips.selectCount(Wrappers.<DriverTripRecord>lambdaQuery().in(DriverTripRecord::getOrderNo,reject.getOrderNo(),timeout.getOrderNo()))).isZero();
    }
    @Test void sameDriverMayHaveAnotherEpisodeAfterRedispatch() {
        TripOrder o=order(A,7);writes.accept(o.getOrderNo(),A,key());
        writes.driverCancelBeforeArrive(o.getOrderNo(),A,"OTHER",key());
        // 模拟隔离期结束后的合法新指派，仅验证记录模型不以 orderNo+driverId 错误去重。
        orders.update(null,Wrappers.<TripOrder>lambdaUpdate().eq(TripOrder::getId,o.getId())
                .set(TripOrder::getDriverId,A).set(TripOrder::getStatus,7));
        writes.accept(o.getOrderNo(),A,key());
        assertThat(trips.selectList(Wrappers.<DriverTripRecord>lambdaQuery().eq(DriverTripRecord::getOrderNo,o.getOrderNo())))
                .hasSize(2).extracting(DriverTripRecord::getStatus).containsExactlyInAnyOrder(6,2);
    }
    @Test void dashboardUsesCompletionDayFinalFareAndExcludesUnpricedAndCancelled() {
        LocalDate day=LocalDate.of(2026,9,6);LocalDateTime start=day.atStartOfDay();
        seed(A,5,start.minusMinutes(30),start.plusMinutes(10),new BigDecimal("100"),0,1200L);
        seed(A,5,start.plusHours(2),start.plusHours(3),null,0,3600L);
        seed(A,5,start.plusHours(3),start.plusHours(4),new BigDecimal("900"),1,null);
        seed(A,5,start.plusHours(4),start.plusHours(5),BigDecimal.ZERO,0,0L);
        seed(A,5,start.plusHours(23),start.plusDays(1),new BigDecimal("500"),0,3600L);
        seed(A,6,start.plusHours(1),start.plusHours(2),null,0,null);
        seed(B,5,start,start.plusHours(1),new BigDecimal("800"),0,3600L);
        var result=queries.today(A,day);
        assertThat(result.getCompletedCount()).isEqualTo(4);assertThat(result.getCancelledCount()).isEqualTo(1);
        assertThat(result.getOrderAmount()).isEqualByComparingTo("100");assertThat(result.getUnpricedCount()).isEqualTo(1);
        assertThat(result.getAbnormalSettlementCount()).isEqualTo(1);assertThat(result.getMissingDurationCount()).isEqualTo(1);
        assertThat(result.getServiceDurationSeconds()).isEqualTo(4800);assertThat(result.getDistanceMeters()).isEqualTo(40000);
        assertThat(result.getLatestTrip().getFinishedAt()).isEqualTo(start.plusHours(5));
        assertThat(result.getPeriodEnd()).isEqualTo(start.plusDays(1));
        var drilldown=queries.page(A,"FINISHED",day,day,"FINISHED",1,20,null);
        assertThat(drilldown.total()).isEqualTo(result.getCompletedCount());
        assertThat(queries.today(999999L,day).getOrderAmount()).isEqualByComparingTo("0");
    }
    @Test void paginationHasStableTieBreakerAndExcludesNewInserts() {
        LocalDateTime time=LocalDateTime.of(2026,9,1,8,0);
        for(int i=0;i<3;i++)seed(A,5,time,time.plusHours(1),BigDecimal.TEN,0,3600L);
        var first=queries.page(A,"ALL",null,null,"ACCEPTED",1,2,null);
        seed(A,5,time.plusHours(1),time.plusHours(2),BigDecimal.TEN,0,3600L);
        var second=queries.page(A,"ALL",null,null,"ACCEPTED",2,2,Long.valueOf(first.snapshotId()));
        assertThat(first.total()).isEqualTo(3);assertThat(second.total()).isEqualTo(3);assertThat(second.list()).hasSize(1);
        assertThat(first.list()).extracting(DriverTripView::getId).doesNotContain(second.list().getFirst().getId());
        assertThat(first.list().get(0).getId()).isGreaterThan(first.list().get(1).getId());
    }
    @Test void rejectsInvalidFiltersAndNonPositiveIdentity() {
        assertThatThrownBy(()->queries.page(A,"HACK",null,null,"ACCEPTED",1,20,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->queries.page(A,"ALL",null,null,"ACCEPTED",1,101,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->queries.page(A,"ALL",LocalDate.now(),LocalDate.now().minusDays(1),"ACCEPTED",1,20,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->queries.today(-1)).isInstanceOf(ResponseStatusException.class);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void recordFailureRollsBackOrderAcceptanceAndEvent() {
        TripOrder o=order(A,7);
        try {
            trips.insert(new DriverTripRecord().setOrderId(o.getId()).setOrderNo(o.getOrderNo()).setSourceKey(key())
                    .setDriverId(A).setStatus(2).setActiveFlag(1).setAcceptedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now()));
            assertThatThrownBy(()->writes.accept(o.getOrderNo(),A,key())).isInstanceOf(RuntimeException.class);
            assertThat(orders.selectById(o.getId()).getStatus()).isEqualTo(7);
            assertThat(events.selectCount(Wrappers.<OrderEvent>lambdaQuery().eq(OrderEvent::getOrderNo,o.getOrderNo()))).isZero();
        } finally { orders.deleteById(o.getId()); }
    }
    private DriverTripPage page(long driver) { return queries.page(driver,"ALL",null,null,"ACCEPTED",1,100,null); }
    private DriverTripRecord record(TripOrder o,long driver) {
        return trips.selectOne(Wrappers.<DriverTripRecord>lambdaQuery().eq(DriverTripRecord::getOrderNo,o.getOrderNo()).eq(DriverTripRecord::getDriverId,driver));
    }
    private TripOrder order(long driver,int status) {
        var now=LocalDateTime.now();
        TripOrder o=new TripOrder().setOrderNo("TRIP-"+key()).setPassengerId(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .setDriverId(driver).setCarId(201L).setCompanyId(101L).setCityCode("330100").setProvinceCode("330000").setProductCode("ECONOMY")
                .setOriginAddress("起点").setDestAddress("终点").setOriginLat(BigDecimal.ONE).setOriginLng(BigDecimal.ONE)
                .setDestLat(BigDecimal.TEN).setDestLng(BigDecimal.TEN).setStatus(status).setEstimatedAmount(new BigDecimal("999"))
                .setPlannedDistanceMeters(10000L).setDistanceSource("LOCAL_MOCK_ROUTE").setCreatedAt(now).setUpdatedAt(now).setIsDeleted(0);
        orders.insert(o);return o;
    }
    private void seed(long driver,int status,LocalDateTime accepted,LocalDateTime ended,BigDecimal amount,int manual,Long duration) {
        TripOrder o=order(driver,status);o.setAcceptedAt(accepted).setFinishedAt(status==5?ended:null);orders.updateById(o);
        DriverTripRecord record=new DriverTripRecord().setOrderId(o.getId()).setOrderNo(o.getOrderNo()).setSourceKey(key()).setDriverId(driver)
                .setStatus(status).setAcceptedAt(accepted).setFinishedAt(status==5?ended:null).setCancelledAt(status==6?ended:null)
                .setCancelBy(status==6?2:null).setDistanceMeters(10000L).setServiceDurationSeconds(duration).setUpdatedAt(ended);
        trips.insert(record);
        if(status==5)settlements.insert(new TripOrderSettlement().setOrderNo(o.getOrderNo()).setPassengerId(o.getPassengerId())
                .setFinalAmount(amount).setEstimatedAmount(new BigDecimal("999")).setPaidAmount(BigDecimal.ONE)
                .setManualActionRequired(manual).setSettlementStatus(amount==null?"CALCULATING":"PAYMENT_REQUIRED"));
    }
    private String key() { return UUID.randomUUID().toString(); }
}
