package com.stockly.scanner.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PriceHistoryTest {

    @Test
    void latestReturnsMostRecentAppendedPoint() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(101), BigDecimal.ZERO);

        assertThat(history.latest()).isPresent();
        assertThat(history.latest().get().price()).isEqualByComparingTo("101");
    }

    @Test
    void priceAtOrBeforeReturnsClosestPointNotAfterTarget() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(101), BigDecimal.ZERO);
        history.append(t0.plusSeconds(120), BigDecimal.valueOf(102), BigDecimal.ZERO);

        var reference = history.priceAtOrBefore(t0.plusSeconds(90));

        assertThat(reference).isPresent();
        assertThat(reference.get().price()).isEqualByComparingTo("101");
    }

    @Test
    void priceAtOrBeforeIsEmptyWhenNoPointOldEnough() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);

        assertThat(history.priceAtOrBefore(t0.minusSeconds(1))).isEmpty();
    }

    @Test
    void lowestBetweenFindsTheMinimumPriceInRange() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(90), BigDecimal.ZERO);
        history.append(t0.plusSeconds(120), BigDecimal.valueOf(95), BigDecimal.ZERO);
        history.append(t0.plusSeconds(180), BigDecimal.valueOf(105), BigDecimal.ZERO);

        var lowest = history.lowestBetween(t0, t0.plusSeconds(180));

        assertThat(lowest).isPresent();
        assertThat(lowest.get().price()).isEqualByComparingTo("90");
    }

    @Test
    void lowestBetweenRespectsFromAndToBounds() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(50), BigDecimal.ZERO); // outside range, should be excluded
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(90), BigDecimal.ZERO);
        history.append(t0.plusSeconds(120), BigDecimal.valueOf(95), BigDecimal.ZERO);

        var lowest = history.lowestBetween(t0.plusSeconds(60), t0.plusSeconds(120));

        assertThat(lowest).isPresent();
        assertThat(lowest.get().price()).isEqualByComparingTo("90");
    }

    @Test
    void lowestBetweenIsEmptyWhenNoPointsInRange() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);

        assertThat(history.lowestBetween(t0.plusSeconds(60), t0.plusSeconds(120))).isEmpty();
    }

    @Test
    void averageDollarVolumeExcludingLatestExcludesTheTailPoint() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(1000)); // baseline
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(100), BigDecimal.valueOf(3000)); // baseline
        history.append(t0.plusSeconds(120), BigDecimal.valueOf(100), BigDecimal.valueOf(1_000_000)); // latest, excluded

        var average = history.averageDollarVolumeExcludingLatest(t0);

        assertThat(average).isPresent();
        assertThat(average.get()).isEqualByComparingTo("2000"); // avg(1000, 3000), not diluted by the 1,000,000 tail
    }

    @Test
    void averageDollarVolumeExcludingLatestRespectsFromBound() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(9999)); // before "from", excluded
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(100), BigDecimal.valueOf(2000)); // baseline
        history.append(t0.plusSeconds(120), BigDecimal.valueOf(100), BigDecimal.valueOf(500)); // latest, excluded

        var average = history.averageDollarVolumeExcludingLatest(t0.plusSeconds(60));

        assertThat(average).isPresent();
        assertThat(average.get()).isEqualByComparingTo("2000");
    }

    @Test
    void averageDollarVolumeExcludingLatestIsEmptyWithFewerThanTwoPoints() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(5000)); // only point - it's the "latest," excluded

        assertThat(history.averageDollarVolumeExcludingLatest(t0)).isEmpty();
    }

    @Test
    void pruneDropsPointsOlderThanMaxAge() {
        PriceHistory history = new PriceHistory();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        history.append(t0, BigDecimal.valueOf(100), BigDecimal.ZERO);
        history.append(t0.plusSeconds(60), BigDecimal.valueOf(101), BigDecimal.ZERO);

        history.prune(t0.plusSeconds(60), Duration.ofSeconds(30));

        assertThat(history.size()).isEqualTo(1);
        assertThat(history.latest().get().price()).isEqualByComparingTo("101");
    }
}
