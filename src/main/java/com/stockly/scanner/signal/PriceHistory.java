package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Rolling timestamp/price/dollar-volume buffer for one symbol. Callers append on every
 * incoming tick and prune periodically; not bounded by a fixed size, only by age.
 */
public class PriceHistory {

    private final Deque<PricePoint> points = new ArrayDeque<>();

    public synchronized void append(Instant timestamp, BigDecimal price, BigDecimal dollarVolume) {
        points.addLast(new PricePoint(timestamp, price, dollarVolume));
    }

    /** Drops points older than {@code now - maxAge}. */
    public synchronized void prune(Instant now, Duration maxAge) {
        Instant cutoff = now.minus(maxAge);
        while (!points.isEmpty() && points.peekFirst().timestamp().isBefore(cutoff)) {
            points.removeFirst();
        }
    }

    /** The most recent point at or before {@code target}, if any. */
    public synchronized Optional<PricePoint> priceAtOrBefore(Instant target) {
        PricePoint candidate = null;
        for (PricePoint point : points) {
            if (point.timestamp().isAfter(target)) {
                break;
            }
            candidate = point;
        }
        return Optional.ofNullable(candidate);
    }

    public synchronized Optional<PricePoint> latest() {
        return Optional.ofNullable(points.peekLast());
    }

    /** The minimum-price point with {@code from <= timestamp <= to}, if any. */
    public synchronized Optional<PricePoint> lowestBetween(Instant from, Instant to) {
        PricePoint lowest = null;
        for (PricePoint point : points) {
            Instant ts = point.timestamp();
            if (ts.isBefore(from) || ts.isAfter(to)) {
                continue;
            }
            if (lowest == null || point.price().compareTo(lowest.price()) < 0) {
                lowest = point;
            }
        }
        return Optional.ofNullable(lowest);
    }

    public synchronized int size() {
        return points.size();
    }

    /**
     * Average dollar volume among points with {@code timestamp >= from}, excluding the
     * single most-recent point (identity-compared against the tail) - so a huge current
     * bar doesn't dilute its own baseline. Empty if fewer than 2 qualifying points exist.
     */
    public synchronized Optional<BigDecimal> averageDollarVolumeExcludingLatest(Instant from) {
        PricePoint last = points.peekLast();
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (PricePoint point : points) {
            if (point == last || point.timestamp().isBefore(from)) {
                continue;
            }
            sum = sum.add(point.dollarVolume());
            count++;
        }
        return count == 0 ? Optional.empty() : Optional.of(sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL64));
    }
}
