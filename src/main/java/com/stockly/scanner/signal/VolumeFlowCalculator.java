package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared computation for {@link VolumeInflowRule}/{@link VolumeOutflowRule}: how far
 * above (or below) its own recent baseline is this symbol's current dollar volume, and
 * which way did price move over the same window. Not a {@link ScanRule} itself - both
 * rules apply the same numbers, just checking the ratio + direction combination in
 * opposite ways.
 */
final class VolumeFlowCalculator {

    private VolumeFlowCalculator() {
    }

    record VolumeFlow(BigDecimal volumeRatioPercent, BigDecimal priceChangePercent,
                       BigDecimal referencePrice, BigDecimal currentPrice) {

        String detail() {
            BigDecimal multiple = volumeRatioPercent.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
            return "volume " + multiple.setScale(1, RoundingMode.HALF_UP) + "x normal";
        }
    }

    static Optional<VolumeFlow> computeFlow(PriceHistory history, Instant now, Duration lookback) {
        Optional<PricePoint> reference = history.priceAtOrBefore(now.minus(lookback));
        if (reference.isEmpty()) {
            return Optional.empty();
        }

        Optional<PricePoint> current = history.latest();
        if (current.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal referencePrice = reference.get().price();
        if (referencePrice.signum() == 0) {
            return Optional.empty();
        }

        Optional<BigDecimal> baseline = history.averageDollarVolumeExcludingLatest(reference.get().timestamp());
        if (baseline.isEmpty() || baseline.get().signum() == 0) {
            return Optional.empty();
        }

        BigDecimal volumeRatioPercent = current.get().dollarVolume()
                .divide(baseline.get(), MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal priceChangePercent = current.get().price().subtract(referencePrice)
                .divide(referencePrice, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));

        return Optional.of(new VolumeFlow(volumeRatioPercent, priceChangePercent, referencePrice, current.get().price()));
    }
}
