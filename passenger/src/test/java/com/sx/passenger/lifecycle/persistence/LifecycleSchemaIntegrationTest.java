package com.sx.passenger.lifecycle.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void lifecycleSchemaContainsReviewedRuntimeTablesOnly() {
        assertThat(columns("customer"))
                .contains("lifecycle_status", "lifecycle_version", "auth_epoch",
                        "current_lifecycle_operation_no", "cancelled_at");
        assertThat(tableNames()).contains(
                "account_lifecycle_operation", "account_lifecycle_step",
                "account_lifecycle_blocker", "account_lifecycle_event",
                "account_lifecycle_outbox", "customer_phone_binding_history");
        assertThat(tableNames()).doesNotContain(
                "account_lifecycle_plan_definition",
                "account_lifecycle_plan_step_definition");
    }

    private Set<String> columns(String tableName) {
        List<String> names = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """, String.class, tableName);
        return lowerCase(names);
    }

    private Set<String> tableNames() {
        List<String> names = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);
        return lowerCase(names);
    }

    private static Set<String> lowerCase(List<String> values) {
        return values.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
