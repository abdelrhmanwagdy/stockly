package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared rolling percent-change computation used by both {@link PriceSpikeRule} and
 * {@link PriceDropRule} - identical math, just compared against the threshold in
 * opposite directions. Not a {@link ScanRule} itself: it always returns the match when
 * there's enough history, regardless of magnitude, leaving the threshold check to the caller.
 */
final class PriceChangeCalculator {

    private PriceChangeCalculator() {
    }

    static Optional<ScanMatch> computeChange(String symbol, PriceHistory history, Instant now, Duration lookback, String label) {
        Optional<PricePoint> current = history.latest();
        if (current.isEmpty()) {
            return Optional.empty();
        }

        Optional<PricePoint> reference = history.priceAtOrBefore(now.minus(lookback));
        if (reference.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal referencePrice = reference.get().price();
        if (referencePrice.signum() == 0) {
            return Optional.empty();
        }

        BigDecimal currentPrice = current.get().price();
        BigDecimal pctChange = currentPrice.subtract(referencePrice)
                .divide(referencePrice, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));

        return Optional.of(new ScanMatch(symbol, pctChange, referencePrice, currentPrice, now, lookback, label, ""));
    }
}
