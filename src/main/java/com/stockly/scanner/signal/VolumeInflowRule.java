package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * "Money entering" a stock: dollar volume at least {@code volumeMultiplierPercent} of
 * this symbol's own recent average, while price is rising. Design doc Section 6's
 * "combined price+volume confirmation" - volume alone isn't "up" or "down," only
 * volume + direction together makes this distinct from a plain volume spike.
 */
public class VolumeInflowRule implements ScanRule {

    private final BigDecimal volumeMultiplierPercent;
    private final Duration lookback;
    private final Duration cooldown;

    public VolumeInflowRule(double volumeMultiplierPercent, Duration lookback, Duration cooldown) {
        this.volumeMultiplierPercent = BigDecimal.valueOf(volumeMultiplierPercent);
        this.lookback = lookback;
        this.cooldown = cooldown;
    }

    @Override
    public Optional<ScanMatch> evaluate(String symbol, PriceHistory history, Instant now) {
        return VolumeFlowCalculator.computeFlow(history, now, lookback)
                .filter(flow -> flow.volumeRatioPercent().compareTo(volumeMultiplierPercent) >= 0)
                .filter(flow -> flow.priceChangePercent().signum() > 0)
                .map(flow -> new ScanMatch(symbol, flow.priceChangePercent(), flow.referencePrice(),
                        flow.currentPrice(), now, lookback, "INFLOW", flow.detail()));
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
