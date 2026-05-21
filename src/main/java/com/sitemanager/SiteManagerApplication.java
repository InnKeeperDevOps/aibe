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

    // SQLite refuses to open a database when its parent directory is missing,
    // and resolves relative paths against the process cwd at connect time
    // (producing errors like "/app/./data does not exist"). Resolve the URL
    // exactly the same way Spring will (env var > system property > YAML
    // default), absolutize the path, create the parent directory, and finally
    // pin the absolute URL via a JVM system property so it takes precedence
    // over any relative SPRING_DATASOURCE_URL that may be set in the runtime
    // environment.
    private static void ensureSqliteDirectoryExists() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || url.isEmpty()) {
            url = System.getProperty("spring.datasource.url");
        }
        if (url == null || url.isEmpty()) {
            String dbPath = System.getenv("SQLITE_DB_PATH");
            if (dbPath == null || dbPath.isEmpty()) {
                dbPath = "/data/aibe.db";
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
        Path absolutePath = Paths.get(dbPath).toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to create SQLite data directory: " + parent, e);
            }
        }
        // JVM system properties outrank OS env vars in Spring Boot's
        // PropertySource order, so this defeats a stale SPRING_DATASOURCE_URL
        // pointing at the old relative path.
        System.setProperty("spring.datasource.url", "jdbc:sqlite:" + absolutePath);
    }
}
