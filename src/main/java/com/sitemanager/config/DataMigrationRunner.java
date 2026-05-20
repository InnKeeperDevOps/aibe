package com.sitemanager.config;

import com.sitemanager.model.enums.SuggestionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
@Order(1)
public class DataMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        migrateStatusCheckConstraint();
        ensureAutoMergePrColumn();
        ensureRegistrationsEnabledColumn();
        ensureMaxConcurrentSuggestionsColumn();

        int updated = jdbcTemplate.update(
                "UPDATE suggestions SET status = 'DEV_COMPLETE' WHERE status = 'COMPLETED'");
        if (updated > 0) {
            log.info("Data migration: updated {} suggestion(s) from COMPLETED to DEV_COMPLETE", updated);
        }

        seedRegisteredUserGroup();
    }

    /**
     * Adds the auto_merge_pr column to site_settings if it does not already exist.
     * Hibernate ddl-auto:update handles this on a fresh schema, but this guard
     * ensures existing databases are also migrated safely.
     */
    private void ensureAutoMergePrColumn() {
        String tableSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='site_settings'",
                String.class);
        if (tableSql == null || tableSql.contains("auto_merge_pr")) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE site_settings ADD COLUMN auto_merge_pr INTEGER NOT NULL DEFAULT 0");
        log.info("Data migration: added auto_merge_pr column to site_settings");
    }

    /**
     * Adds the registrations_enabled column to site_settings if it does not already exist.
     * Hibernate ddl-auto:update handles this on a fresh schema, but this guard
     * ensures existing databases are also migrated safely.
     */
    private void ensureRegistrationsEnabledColumn() {
        String tableSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='site_settings'",
                String.class);
        if (tableSql == null || tableSql.contains("registrations_enabled")) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE site_settings ADD COLUMN registrations_enabled INTEGER NOT NULL DEFAULT 1");
        log.info("Data migration: added registrations_enabled column to site_settings");
    }

    /**
     * SQLite CHECK constraints are baked into the CREATE TABLE statement and cannot be
     * altered in place. When the SuggestionStatus enum changes, the constraint must be
     * updated via the standard SQLite table-rebuild pattern: create a new table with the
     * correct constraint, copy data, drop the old table, and rename.
     */
    private void ensureMaxConcurrentSuggestionsColumn() {
        String tableSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='site_settings'",
                String.class);
        if (tableSql == null || tableSql.contains("max_concurrent_suggestions")) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE site_settings ADD COLUMN max_concurrent_suggestions INTEGER DEFAULT 1");
        log.info("Data migration: added max_concurrent_suggestions column to site_settings");
    }

    private void migrateStatusCheckConstraint() {
        String currentSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='suggestions'",
                String.class);

        if (currentSql == null) {
            return;
        }

        // Only rebuild when an existing status CHECK constraint is present and needs updating.
        // Without this guard, a schema lacking the constraint would trigger an unnecessary
        // rebuild on every startup (none of the enum values appear in the SQL).
        boolean hasStatusCheckConstraint = currentSql.matches(
                "(?is).*check\\s*\\(\\s*status\\s+in\\s*\\([^)]+\\)\\).*");
        if (!hasStatusCheckConstraint) {
            return;
        }

        String validStatuses = Arrays.stream(SuggestionStatus.values())
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(","));

        boolean needsRebuild = Arrays.stream(SuggestionStatus.values())
                .anyMatch(s -> !currentSql.contains("'" + s.name() + "'"));

        if (!needsRebuild) {
            return;
        }

        log.info("Data migration: rebuilding suggestions table to update status CHECK constraint");

        // Replace the old CHECK constraint with one derived from the current enum
        String newSql = currentSql.replaceFirst(
                "(?i)check\\s*\\(\\s*status\\s+in\\s*\\([^)]+\\)\\)",
                "check(status in (" + validStatuses + "))");

        // Use the rebuilt CREATE TABLE statement with a temporary name.
        // Allow optional IF NOT EXISTS and quoting around the table name — different DDL
        // sources (Hibernate, Flyway migrations) emit these differently, and sqlite_master
        // may retain the IF NOT EXISTS clause as written.
        String tempCreateSql = newSql.replaceFirst(
                "(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[\"`']?suggestions[\"`']?",
                "CREATE TABLE suggestions_rebuild");

        // Safety check: if the rewrite failed, executing the unmodified statement would
        // try to CREATE TABLE suggestions against the existing table and crash startup.
        // Skip the rebuild and let the app come up rather than failing.
        if (!tempCreateSql.contains("suggestions_rebuild")) {
            log.warn("Skipping suggestions CHECK constraint rebuild: could not rewrite table name in stored DDL: {}",
                    currentSql);
            return;
        }

        // Drop any orphan suggestions_rebuild left behind by a previous failed migration
        // so the retry starts from a clean state.
        jdbcTemplate.execute("DROP TABLE IF EXISTS suggestions_rebuild");

        jdbcTemplate.execute("PRAGMA foreign_keys=OFF");
        jdbcTemplate.execute(tempCreateSql);
        jdbcTemplate.execute("INSERT INTO suggestions_rebuild SELECT * FROM suggestions");
        jdbcTemplate.execute("DROP TABLE suggestions");
        jdbcTemplate.execute("ALTER TABLE suggestions_rebuild RENAME TO suggestions");
        jdbcTemplate.execute("PRAGMA foreign_keys=ON");

        log.info("Data migration: status CHECK constraint updated to: {}", validStatuses);
    }

    /**
     * Ensures the default 'Registered User' group exists and that all existing
     * USER-role accounts are assigned to it.
     */
    private void seedRegisteredUserGroup() {
        int inserted = jdbcTemplate.update(
                "INSERT INTO user_groups (name, can_create_suggestions, can_vote, can_reply, " +
                "can_approve_deny_suggestions, can_manage_settings, can_manage_users) " +
                "SELECT 'Registered User', 1, 1, 1, 0, 0, 0 " +
                "WHERE NOT EXISTS (SELECT 1 FROM user_groups WHERE name = 'Registered User')");
        if (inserted > 0) {
            log.info("Data migration: created default 'Registered User' group");
        }

        int reassigned = jdbcTemplate.update(
                "UPDATE app_users SET group_id = (SELECT id FROM user_groups WHERE name = 'Registered User') " +
                "WHERE role = 'USER' AND group_id IS NULL");
        if (reassigned > 0) {
            log.info("Data migration: assigned {} USER-role account(s) to 'Registered User' group", reassigned);
        }
    }
}
