package com.sitemanager.repository;

import com.sitemanager.model.ClaudeCliLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClaudeCliLogRepository extends JpaRepository<ClaudeCliLog, Long> {
    /** Most recent CLI invocations, newest first, capped to keep the admin page responsive. */
    List<ClaudeCliLog> findTop500ByOrderByCreatedAtDesc();
}
