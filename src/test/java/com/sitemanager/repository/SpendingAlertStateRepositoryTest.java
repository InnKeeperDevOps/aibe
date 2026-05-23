package com.sitemanager.repository;

import com.sitemanager.model.SpendingAlertState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the alert state table enforces uniqueness over (scope, scope
 * key, window key, threshold) so the same threshold cannot fire twice per
 * window even under concurrent recording, and the bulk-delete helpers used
 * to re-arm alerts work as advertised.
 */
@SpringBootTest
class SpendingAlertStateRepositoryTest {

    @Autowired
    private SpendingAlertStateRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void save_andFind_byCompositeKey() {
        SpendingAlertState row = new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "DAILY:2026-05-23",
                75, new BigDecimal("75.00"), new BigDecimal("100.00"));
        repository.save(row);

        Optional<SpendingAlertState> found = repository
                .findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                        SpendingAlertState.Scope.GLOBAL, "GLOBAL", "DAILY:2026-05-23", 75);

        assertThat(found).isPresent();
        assertThat(found.get().getLimitAtAlert()).isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateCompositeKey_rejectedByUniqueConstraint() {
        SpendingAlertState first = new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                75, new BigDecimal("80"), new BigDecimal("100"));
        repository.saveAndFlush(first);

        SpendingAlertState duplicate = new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                75, new BigDecimal("80"), new BigDecimal("100"));

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentThreshold_allowedForSameWindow() {
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                75, new BigDecimal("80"), new BigDecimal("100")));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                90, new BigDecimal("90"), new BigDecimal("100")));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                100, new BigDecimal("100"), new BigDecimal("100")));

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void differentWindow_allowedForSameThreshold() {
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "DAILY:2026-05-23",
                75, BigDecimal.TEN, new BigDecimal("100")));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "DAILY:2026-05-24",
                75, BigDecimal.TEN, new BigDecimal("100")));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void deletePerSuggestionAlerts_onlyRemovesMatchingScopeAndKey() {
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.PER_SUGGESTION, "1", "LIFETIME",
                75, BigDecimal.ONE, BigDecimal.TEN));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.PER_SUGGESTION, "2", "LIFETIME",
                75, BigDecimal.ONE, BigDecimal.TEN));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                75, BigDecimal.ONE, BigDecimal.TEN));

        repository.deletePerSuggestionAlerts("1");
        repository.flush();

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                SpendingAlertState.Scope.PER_SUGGESTION, "1", "LIFETIME", 75)).isEmpty();
        // The other suggestion's row and the global row are untouched.
        assertThat(repository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                SpendingAlertState.Scope.PER_SUGGESTION, "2", "LIFETIME", 75)).isPresent();
        assertThat(repository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER", 75)).isPresent();
    }

    @Test
    void deleteAllGlobalAlerts_keepsPerSuggestionRowsIntact() {
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "DAILY:2026-05-23",
                75, BigDecimal.ONE, BigDecimal.TEN));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                90, BigDecimal.ONE, BigDecimal.TEN));
        repository.saveAndFlush(new SpendingAlertState(
                SpendingAlertState.Scope.PER_SUGGESTION, "5", "LIFETIME",
                75, BigDecimal.ONE, BigDecimal.TEN));

        repository.deleteAllGlobalAlerts();
        repository.flush();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                SpendingAlertState.Scope.PER_SUGGESTION, "5", "LIFETIME", 75)).isPresent();
    }
}
