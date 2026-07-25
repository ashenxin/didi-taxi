package com.sx.calculate.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CalculateLifecycleSchemaContractTest {

    @Test
    void productionPatchDefinesProjectionEventInboxAndParticipantInbox() throws IOException {
        String sql = resourceText("sql/calculate_account_lifecycle_p4_patch.sql");

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS `calculate_account_lifecycle_event_inbox`")
                .contains("CREATE TABLE IF NOT EXISTS `calculate_account_lifecycle_projection`")
                .contains("CREATE TABLE IF NOT EXISTS `calculate_lifecycle_participant_inbox`")
                .contains("PRIMARY KEY (`source_event_id`)")
                .contains("PRIMARY KEY (`customer_id`)")
                .contains("UNIQUE KEY `uk_calculate_lifecycle_inbox_op_step` (`operation_no`, `step_code`)")
                .contains("`request_hash` CHAR(64) NOT NULL")
                .contains("`blocker_snapshot` JSON NOT NULL")
                .contains("`result_snapshot` JSON NOT NULL")
                .contains("MODIFY COLUMN `biz_id` VARCHAR(160) NOT NULL");
    }

    @Test
    void canonicalAndTestSchemasContainTheSameLifecycleTables() throws IOException {
        String canonical = resourceText("sql/calculate_schema.sql");
        String testSchema = resourceText("schema-test.sql");

        for (String table : new String[]{
                "calculate_account_lifecycle_event_inbox",
                "calculate_account_lifecycle_projection",
                "calculate_lifecycle_participant_inbox"}) {
            assertThat(canonical).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
            assertThat(testSchema).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }

    @Test
    void backfillIsVersionAwareIdempotentAndContainsCoverageChecks() throws IOException {
        String sql = resourceText("sql/calculate_account_lifecycle_p4_backfill.sql");

        assertThat(sql)
                .contains("INSERT INTO `calculate`.`calculate_account_lifecycle_projection`")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("GREATEST(")
                .contains("missing_projection_count")
                .contains("version_mismatch_count")
                .contains("status_mismatch_count")
                .contains("duplicate_source_event_count");
    }

    private static String resourceText(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
