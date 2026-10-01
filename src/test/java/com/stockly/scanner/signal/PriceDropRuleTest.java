package com.stockly.scanner.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors PriceSpikeRuleTest - same rolling-reference mechanics, opposite direction.
 */
class PriceDropRuleTest {

    private final PriceDropRule rule = new PriceDropRule(3.0, Duration.ofMinutes(15), Duration.ofMinutes(15));

    @Test
    void matchesWhenPriceFellAtLeastThresholdOverLookback() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(96.5), BigDecimal.ZERO);

        var match = rule.evaluate("AAPL", history, now);

        assertThat(match).isPresent();
        assertThat(match.get().pctChange()).isEqualByComparingTo("-3.5");
        assertThat(match.get().label()).isEqualTo("DROP");
    }

    @Test
    void noMatchWhenDropIsBelowThreshold() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(98), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchOnARiseEvenIfLarge() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(110), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWithoutEnoughHistoryForTheLookbackWindow() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(5 * 60);
        history.append(now, BigDecimal.valueOf(90), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(97), BigDecimal.ZERO); // exactly -3%

        assertThat(rule.evaluate("AAPL", history, now)).isPresent();
    }
}
