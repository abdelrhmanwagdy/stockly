package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Section 2 of the design doc: fires when price has risen at least
 * {@code thresholdPercent} compared to the price {@code lookback} ago,
 * using a rolling reference point (not a fixed daily anchor).
 */
public class PriceSpikeRule implements ScanRule {

    private final BigDecimal thresholdPercent;
    private final Duration lookback;
    private final Duration cooldown;

    public PriceSpikeRule(double thresholdPercent, Duration lookback, Duration cooldown) {
        this.thresholdPercent = BigDecimal.valueOf(thresholdPercent);
        this.lookback = lookback;
        this.cooldown = cooldown;
    }

    @Override
    public Optional<ScanMatch> evaluate(String symbol, PriceHistory history, Instant now) {
        return PriceChangeCalculator.computeChange(symbol, history, now, lookback, "SPIKE")
                .filter(match -> match.pctChange().compareTo(thresholdPercent) >= 0);
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
