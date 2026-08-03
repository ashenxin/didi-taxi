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
        assertThat(tableDefinition(sql, "account_lifecycle_step"))
                .contains("`operation_id` BIGINT NOT NULL");
        assertThat(tableDefinition(sql, "account_lifecycle_outbox"))
                .contains("`operation_id` BIGINT NULL");
        assertThat(sql).contains("`causation_event_id` VARCHAR(64)");
        assertThat(sql).contains("`trace_id` VARCHAR(64)");
    }

    @Test
    void registrationPatchAllowsOutboxWithoutLifecycleOperation() throws IOException {
        String sql = resourceText("sql/passenger_registration_lifecycle_outbox_patch.sql");

        assertThat(sql).contains("ALTER TABLE `account_lifecycle_outbox`")
                .contains("MODIFY COLUMN `operation_id` BIGINT NULL");
    }

    @Test
    void canonicalAndTestSchemasContainLifecycleTargetStructure() throws IOException {
        String canonical = resourceText("sql/passenger_schema.sql");
        String testSchema = resourceText("schema-test.sql");

        assertThat(canonical)
                .contains("`lifecycle_status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'")
                .contains("`lifecycle_version` BIGINT NOT NULL DEFAULT 0")
                .contains("`auth_epoch` BIGINT NOT NULL DEFAULT 0");
        assertThat(testSchema)
                .contains("lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'")
                .contains("lifecycle_version BIGINT NOT NULL DEFAULT 0")
                .contains("auth_epoch BIGINT NOT NULL DEFAULT 0");

        for (String table : new String[]{
                "account_lifecycle_operation",
                "account_lifecycle_step",
                "account_lifecycle_blocker",
                "account_lifecycle_event",
                "account_lifecycle_outbox",
                "customer_phone_binding_history"}) {
            assertThat(canonical).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
            assertThat(testSchema).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
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

    private static String tableDefinition(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
        int end = sql.indexOf(";", start);
        return sql.substring(start, end + 1);
    }
}
