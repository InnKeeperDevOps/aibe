package com.sitemanager.controller;

import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.service.CostRollupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoints for cost roll-ups. Lets the UI fetch the current
 * total for one suggestion or the system-wide total on demand, separately
 * from the real-time WebSocket push.
 */
@RestController
@RequestMapping("/api/costs")
public class CostRollupController {

    private final CostRollupService rollupService;

    public CostRollupController(CostRollupService rollupService) {
        this.rollupService = rollupService;
    }

    @GetMapping("/suggestion/{id}")
    public ResponseEntity<CostSummaryDto> getSuggestionCosts(@PathVariable Long id) {
        return ResponseEntity.ok(rollupService.getSuggestionSummary(id));
    }

    @GetMapping("/global")
    public ResponseEntity<CostSummaryDto> getGlobalCosts() {
        return ResponseEntity.ok(rollupService.getGlobalSummary());
    }
}
