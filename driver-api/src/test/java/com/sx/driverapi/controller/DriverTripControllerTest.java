package com.sx.driverapi.controller;
import com.sx.driverapi.client.*;
import com.sx.driverapi.common.exception.GlobalExceptionHandler;
import com.sx.driverapi.model.trip.*;
import com.sx.driverapi.service.DriverTripBffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverTripControllerTest {
    DriverTripClient client=mock(DriverTripClient.class);MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new DriverTripController(new DriverTripBffService(client)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void identityAlwaysComesFromTrustedHeader() throws Exception {
        var response=new CoreResponseVo<DriverTripPage>();response.setCode(200);response.setData(new DriverTripPage(List.of(),0,1,20,"0"));
        when(client.page("80001","ALL",null,null,"ACCEPTED",1,20,null)).thenReturn(response);
        mvc.perform(get("/driver/api/v1/profile/orders?driverId=99999").header("X-User-Id","80001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(client).page("80001","ALL",null,null,"ACCEPTED",1,20,null);
    }
    @Test void missingOrInvalidIdentityNeverCallsDownstream() throws Exception {
        mvc.perform(get("/driver/api/v1/profile/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/driver/api/v1/dashboard/today").header("X-User-Id","-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/driver/api/v1/profile/orders/1").header("X-User-Id","abc")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
    @Test void unauthorizedRecordUsesSame404AsMissing() throws Exception {
        var response=new CoreResponseVo<DriverTripView>();response.setCode(404);response.setMsg("行程不存在");
        when(client.detail("80001",12)).thenReturn(response);
        mvc.perform(get("/driver/api/v1/profile/orders/12").header("X-User-Id","80001"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.msg").value("行程不存在"));
    }
    @Test void badDownstreamResponseIsNotPresentedAsZeroOperations() throws Exception {
        when(client.today("80001")).thenReturn(null);
        mvc.perform(get("/driver/api/v1/dashboard/today").header("X-User-Id","80001")).andExpect(status().isBadGateway());
    }
    @Test void forwardsDateBasisAndSnapshotWithoutChangingIdentity() throws Exception {
        var response=new CoreResponseVo<DriverTripPage>();response.setCode(200);response.setData(new DriverTripPage(List.of(),0,2,10,"55"));
        when(client.page("80001","FINISHED","2026-09-06","2026-09-06","FINISHED",2,10,55L)).thenReturn(response);
        mvc.perform(get("/driver/api/v1/profile/orders?status=FINISHED&startDate=2026-09-06&endDate=2026-09-06&dateField=FINISHED&pageNo=2&pageSize=10&snapshotId=55")
                .header("X-User-Id","80001")).andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshotId").value("55"));
    }
}
