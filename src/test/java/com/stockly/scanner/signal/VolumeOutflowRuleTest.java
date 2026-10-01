package com.stockly.scanner.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Money exiting": high relative dollar volume WHILE price falls. The mirror of
 * {@link VolumeInflowRuleTest}.
 */
class VolumeOutflowRuleTest {

    private final VolumeOutflowRule rule =
            new VolumeOutflowRule(300.0, Duration.ofMinutes(20), Duration.ofMinutes(15));

    @Test
    void matchesOnHighRelativeVolumeWithPriceFalling() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(1000));
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(99), BigDecimal.valueOf(1000)); // baseline avg = 1000
        Instant now = t0.plusSeconds(20 * 60);
        history.append(now, BigDecimal.valueOf(97), BigDecimal.valueOf(5000)); // 5x baseline, price -3%

        var match = rule.evaluate("AAPL", history, now);

        assertThat(match).isPresent();
        assertThat(match.get().label()).isEqualTo("OUTFLOW");
        assertThat(match.get().pctChange()).isEqualByComparingTo("-3");
        assertThat(match.get().detail()).contains("5.0x");
    }

    @Test
    void noMatchWhenVolumeHighButPriceRises() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(1000));
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(99), BigDecimal.valueOf(1000));
        Instant now = t0.plusSeconds(20 * 60);
        history.append(now, BigDecimal.valueOf(103), BigDecimal.valueOf(5000)); // 5x baseline, but price +3%

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWhenPriceFallsButVolumeRatioIsNotHighEnough() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(1000));
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(99), BigDecimal.valueOf(1000));
        Instant now = t0.plusSeconds(20 * 60);
        history.append(now, BigDecimal.valueOf(97), BigDecimal.valueOf(1200)); // only 1.2x baseline

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWithoutEnoughHistoryForTheLookbackWindow() {
        PriceHistory history = new PriceHistory();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        history.append(now, BigDecimal.valueOf(100), BigDecimal.valueOf(5000)); // only point - no 20min-back reference

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(1000));
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(99), BigDecimal.valueOf(1000));
        Instant now = t0.plusSeconds(20 * 60);
        history.append(now, BigDecimal.valueOf(97), BigDecimal.valueOf(3000)); // exactly 3.0x baseline

        assertThat(rule.evaluate("AAPL", history, now)).isPresent();
    }
}
