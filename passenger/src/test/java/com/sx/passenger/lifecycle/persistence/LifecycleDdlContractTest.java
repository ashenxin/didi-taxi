package com.sx.passenger.lifecycle.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleDdlContractTest {

    @Test
    void productionDdlStoresPlanDigestAndKeepsAuditSeparateFromOutbox() throws IOException {
        String sql = resourceText("sql/passenger_account_lifecycle_patch.sql");

        assertThat(sql).contains("`plan_digest` CHAR(64)");
        assertThat(sql).doesNotContain("`plan_snapshot`");
        assertThat(sql).doesNotContain("CREATE TABLE IF NOT EXISTS `account_lifecycle_plan_definition`");
        assertThat(sql).doesNotContain("CREATE TABLE IF NOT EXISTS `account_lifecycle_plan_step_definition`");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `account_lifecycle_event`");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `account_lifecycle_outbox`");
        assertThat(sql).contains("`causation_event_id` VARCHAR(64)");
        assertThat(sql).contains("`trace_id` VARCHAR(64)");
    }

    @Test
    void packageContainsOneActiveVersionOnePlanForEachOperationType() throws IOException {
        String cancellation = resourceText("account-lifecycle/account-cancel-v1.yml");
        String phoneChange = resourceText("account-lifecycle/phone-change-v1.yml");

        assertThat(cancellation)
                .contains("version: 1", "operationType: ACCOUNT_CANCEL", "status: ACTIVE");
        assertThat(phoneChange)
                .contains("version: 1", "operationType: PHONE_CHANGE", "status: ACTIVE");
    }

    private static String resourceText(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
