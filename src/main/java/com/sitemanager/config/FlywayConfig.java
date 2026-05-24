package com.sitemanager.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs Flyway repair before migrate. Migration files in this repo have been
 * renumbered multiple times to resolve duplicate-version conflicts from
 * concurrently-merged PRs, leaving production schema_history rows whose
 * checksums and descriptions no longer match the current resources. Without
 * repair, Flyway fails startup with "Validate failed: Migrations have failed
 * validation". repair() realigns history with the current resolved migrations
 * (recomputes checksums, removes entries for versions no longer present).
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy repairAndMigrate() {
        return flyway -> {
            try {
                flyway.repair();
            } catch (Exception e) {
                log.warn("Flyway repair failed; continuing to migrate", e);
            }
            flyway.migrate();
        };
    }
}
