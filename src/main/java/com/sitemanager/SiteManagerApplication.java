package com.sitemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableAsync
@EnableScheduling
public class SiteManagerApplication {

    public static void main(String[] args) {
        ensureSqliteDirectoryExists();
        SpringApplication.run(SiteManagerApplication.class, args);
    }

    // SQLite refuses to open a database when its parent directory is missing.
    // Resolve the URL exactly the same way Spring will (env var > system property
    // > YAML default) and create the parent directory before Hikari connects.
    private static void ensureSqliteDirectoryExists() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || url.isEmpty()) {
            url = System.getProperty("spring.datasource.url");
        }
        if (url == null || url.isEmpty()) {
            String dbPath = System.getenv("SQLITE_DB_PATH");
            if (dbPath == null || dbPath.isEmpty()) {
                dbPath = "/app/data/sitemanager.db";
            }
            url = "jdbc:sqlite:" + dbPath;
        }
        if (!url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String dbPath = url.substring("jdbc:sqlite:".length());
        if (dbPath.isEmpty() || dbPath.equals(":memory:") || dbPath.startsWith("file::memory:")) {
            return;
        }
        Path parent = Paths.get(dbPath).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create SQLite data directory: " + parent, e);
        }
    }
}
