package com.stockly.scanner.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Bounce off a low": confirms both a real drop into the window's low AND a real
 * recovery off it - distinct from PriceSpikeRule/PriceDropRule's single two-point check.
 */
class PriceReversalRuleTest {

    private final PriceReversalRule rule =
            new PriceReversalRule(3.0, 2.0, Duration.ofMinutes(30), Duration.ofMinutes(15));

    @Test
    void matchesOnConfirmedDropThenRecovery() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z"); // reference, 30min back
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(95), BigDecimal.ZERO); // low: -5% drop
        Instant now = t0.plusSeconds(30 * 60);
        history.append(now, BigDecimal.valueOf(97), BigDecimal.ZERO); // recovery off low: (97-95)/95 = 2.1%

        var match = rule.evaluate("AAPL", history, now);

        assertThat(match).isPresent();
        assertThat(match.get().label()).isEqualTo("REVERSAL");
        assertThat(match.get().fromPrice()).isEqualByComparingTo("95");
        assertThat(match.get().toPrice()).isEqualByComparingTo("97");
    }

    @Test
    void noMatchWhenDropIntoTheLowIsNotEnough() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(99), BigDecimal.ZERO); // only -1% drop
        Instant now = t0.plusSeconds(30 * 60);
        history.append(now, BigDecimal.valueOf(102), BigDecimal.ZERO); // recovery off low would be +3%, but drop insufficient

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWhenRecoveryOffTheLowIsNotEnough() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(94), BigDecimal.ZERO); // -6% drop, plenty
        Instant now = t0.plusSeconds(30 * 60);
        history.append(now, BigDecimal.valueOf(95), BigDecimal.ZERO); // only +1.06% off the low, needs 2%

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchWithoutEnoughHistoryForTheLookbackWindow() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(5 * 60); // only 5min of history, needs ~30min back
        history.append(now, BigDecimal.valueOf(90), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }

    @Test
    void noMatchOnAMonotonicallyRisingWindow() {
        // No real dip: the window's low is just the reference point itself (0% drop).
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(10 * 60), BigDecimal.valueOf(105), BigDecimal.ZERO);
        Instant now = t0.plusSeconds(30 * 60);
        history.append(now, BigDecimal.valueOf(110), BigDecimal.ZERO);

        assertThat(rule.evaluate("AAPL", history, now)).isEmpty();
    }
}
