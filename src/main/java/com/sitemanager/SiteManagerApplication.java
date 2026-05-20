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
    // The default URL points at ./data/sitemanager.db, which can disappear at
    // runtime if the container mounts an empty volume over /app/data.
    private static void ensureSqliteDirectoryExists() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || url.isEmpty()) {
            url = "jdbc:sqlite:./data/sitemanager.db";
        }
        if (!url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String dbPath = url.substring("jdbc:sqlite:".length());
        if (dbPath.isEmpty() || dbPath.equals(":memory:") || dbPath.startsWith("file::memory:")) {
            return;
        }
        Path parent = Paths.get(dbPath).getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create SQLite data directory: " + parent.toAbsolutePath(), e);
        }
    }
}
