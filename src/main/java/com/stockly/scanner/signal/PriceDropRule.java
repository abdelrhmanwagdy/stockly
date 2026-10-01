package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The downward counterpart to {@link PriceSpikeRule} - fires when price has *fallen* at
 * least {@code thresholdPercent} compared to the price {@code lookback} ago, using the
 * same rolling reference point. Flagged in the design doc (Section 2, "Direction") as a
 * candidate future signal alongside the original upward-only spike rule.
 *
 * <p>{@code thresholdPercent} is given as a positive magnitude (e.g. {@code 3.0} means
 * "fires on a 3% or larger drop"), matching {@code PriceSpikeRule}'s convention rather
 * than requiring callers to pass a negative number.
 */
public class PriceDropRule implements ScanRule {

    private final BigDecimal thresholdPercent;
    private final Duration lookback;
    private final Duration cooldown;

    public PriceDropRule(double thresholdPercent, Duration lookback, Duration cooldown) {
        this.thresholdPercent = BigDecimal.valueOf(thresholdPercent);
        this.lookback = lookback;
        this.cooldown = cooldown;
    }

    @Override
    public Optional<ScanMatch> evaluate(String symbol, PriceHistory history, Instant now) {
        return PriceChangeCalculator.computeChange(symbol, history, now, lookback, "DROP")
                .filter(match -> match.pctChange().compareTo(thresholdPercent.negate()) <= 0);
    }

    @Override
    public Duration lookback() {
        return lookback;
    }

    @Override
    public Duration cooldown() {
        return cooldown;
    }
}
