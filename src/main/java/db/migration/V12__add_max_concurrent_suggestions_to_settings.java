package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Adds the max_concurrent_suggestions column to site_settings.
 *
 * Implemented as a Java migration (rather than plain SQL) because some existing
 * databases already have the column — DataMigrationRunner.ensureMaxConcurrentSuggestionsColumn()
 * added it via a CommandLineRunner before this Flyway migration existed. SQLite has no
 * "ADD COLUMN IF NOT EXISTS", so a straight ALTER would fail on those databases.
 */
public class V12__add_max_concurrent_suggestions_to_settings extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement stmt = context.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(site_settings)")) {
            while (rs.next()) {
                if ("max_concurrent_suggestions".equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute("ALTER TABLE site_settings ADD COLUMN max_concurrent_suggestions INTEGER DEFAULT 1");
        }
    }
}
