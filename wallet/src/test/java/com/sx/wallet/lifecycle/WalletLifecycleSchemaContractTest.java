package com.sx.wallet.lifecycle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WalletLifecycleSchemaContractTest {
    private static final Path ROOT = Path.of("src/main/resources/sql");

    @Test
    void allSchemasContainLifecycleTables() throws Exception {
        String canonical = Files.readString(ROOT.resolve("wallet_schema.sql"));
        String patch = Files.readString(ROOT.resolve("wallet_account_lifecycle_p5_patch.sql"));
        String h2 = Files.readString(Path.of("src/test/resources/schema.sql"));
        for (String table : new String[]{"wallet_account_lifecycle_event_inbox",
                "wallet_account_lifecycle_projection", "wallet_lifecycle_participant_inbox",
                "wallet_auto_pay_termination"}) {
            assertThat(canonical).contains(table);
            assertThat(patch).contains(table);
            assertThat(h2).contains(table);
        }
    }

    @Test
    void backfillContainsFourZeroCountChecks() throws Exception {
        String sql = Files.readString(ROOT.resolve("wallet_account_lifecycle_p5_backfill.sql"));
        assertThat(sql).contains("missing_projection_count", "version_mismatch_count",
                "status_mismatch_count", "duplicate_source_event_count");
    }
}
