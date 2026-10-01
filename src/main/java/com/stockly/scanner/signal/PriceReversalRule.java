package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A "bounce off a low" signal: fires when a symbol fell at least {@code dropPercent}
 * from its rolling reference price into a low point within the lookback window, and
 * has since recovered at least {@code recoveryPercent} off that low. Distinct from
 * {@link PriceSpikeRule}/{@link PriceDropRule}'s single two-point comparison - this
 * one needs the window's *lowest* price as an intermediate reference, not just the
 * price exactly {@code lookback} ago.
 *
 * <p>Anchoring the low-search at the reference point's own timestamp (not a separately
 * computed window start) means a monotonically-rising window naturally yields
 * {@code low == reference} - a 0% drop, correctly not a reversal - rather than
 * mistaking "distance from the window's start" for a real dip-then-bounce.
 */
public class PriceReversalRule implements ScanRule {

    private final BigDecimal dropPercent;
    private final BigDecimal recoveryPercent;
    private final Duration lookback;
    private final Duration cooldown;

    public PriceReversalRule(double dropPercent, double recoveryPercent, Duration lookback, Duration cooldown) {
        this.dropPercent = BigDecimal.valueOf(dropPercent);
        this.recoveryPercent = BigDecimal.valueOf(recoveryPercent);
        this.lookback = lookback;
        this.cooldown = cooldown;
    }

    @Override
    public Optional<ScanMatch> evaluate(String symbol, PriceHistory history, Instant now) {
        Optional<PricePoint> reference = history.priceAtOrBefore(now.minus(lookback));
        if (reference.isEmpty()) {
            return Optional.empty();
        }

        PricePoint referencePoint = reference.get();
        if (referencePoint.price().signum() == 0) {
            return Optional.empty();
        }

        PricePoint low = history.lowestBetween(referencePoint.timestamp(), now).orElse(referencePoint);

        BigDecimal dropPct = low.price().subtract(referencePoint.price())
                .divide(referencePoint.price(), MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));
        if (dropPct.compareTo(dropPercent.negate()) > 0) {
            return Optional.empty();
        }

        Optional<PricePoint> current = history.latest();
        if (current.isEmpty() || low.price().signum() == 0) {
            return Optional.empty();
        }

        BigDecimal recoveryPct = current.get().price().subtract(low.price())
                .divide(low.price(), MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));
        if (recoveryPct.compareTo(recoveryPercent) < 0) {
            return Optional.empty();
        }

        return Optional.of(new ScanMatch(symbol, recoveryPct, low.price(), current.get().price(), now, lookback, "REVERSAL", ""));
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
