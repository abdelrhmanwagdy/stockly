package com.stockly.scanner.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the scenarios the JS prototype validated (doc Section 4): rolling
 * reference tracking, the exact 3% / 15min threshold, and matching against
 * the price 15 minutes back rather than a fixed anchor.
 */
class PriceSpikeRuleTest {

    private final PriceSpikeRule rule = new PriceSpikeRule(3.0, Duration.ofMinutes(15), Duration.ofMinutes(15));

    @Test
    void matchesWhenPriceRoseAtLeastThresholdOverLookback() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(103.5), BigDecimal.ZERO);

        var match = rule.evaluate("AAPL", history, now);

        assertThat(match).isPresent();
        assertThat(match.get().pctChange()).isEqualByComparingTo("3.5");
        assertThat(match.get().label()).isEqualTo("SPIKE");
    }

    @Test
    void noMatchWhenBelowThreshold() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(102), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWithoutEnoughHistoryForTheLookbackWindow() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        // Only 5 minutes of history exists; the rule needs a point ~15 minutes back.
        Instant now = t0.plusSeconds(5 * 60);
        history.append(now, BigDecimal.valueOf(110), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void usesRollingReferenceNotAFixedAnchor() {
        // Section 2.1: the reference point is "15 minutes before now", which moves
        // forward every tick, not a fixed anchor such as the day's open.
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);               // would-be "open"
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(103), BigDecimal.ZERO); // reference 15min before the check below
        Instant now = t0.plusSeconds(25 * 60);
        history.append(now, BigDecimal.valueOf(103.5), BigDecimal.ZERO);

        // vs. the fixed anchor (100), 103.5 would be a 3.5% move (a match);
        // vs. the correct rolling reference (103, from 15 min back), it's below threshold.
        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(15 * 60);
        history.append(now, BigDecimal.valueOf(103), BigDecimal.ZERO); // exactly +3%

        assertThat(rule.evaluate("AAPL", history, now)).isPresent();
    }
}
