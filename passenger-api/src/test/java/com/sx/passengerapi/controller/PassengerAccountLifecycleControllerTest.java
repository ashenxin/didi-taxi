package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.GlobalExceptionHandler;
import com.sx.passengerapi.service.PassengerAccountLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PassengerAccountLifecycleControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PassengerAccountLifecycleService lifecycle = mock(PassengerAccountLifecycleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PassengerAccountLifecycleController(lifecycle))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void cancellationWithoutIdempotencyHeaderReturnsBadRequest() throws Exception {
        mvc.perform(post("/app/api/v1/account-lifecycle/cancellations")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedLifecycleVersion":0,"code":"123456","confirm":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求头[Idempotency-Key]不能为空"));
    }

    @Test
    void phoneChangeWithoutIdempotencyHeaderReturnsBadRequest() throws Exception {
        mvc.perform(post("/app/api/v1/account-lifecycle/phone-changes")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedLifecycleVersion":0,"newPhone":"13900139000","code":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求头[Idempotency-Key]不能为空"));
    }
}
