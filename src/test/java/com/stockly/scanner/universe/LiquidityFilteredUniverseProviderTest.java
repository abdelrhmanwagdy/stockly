package com.stockly.scanner.universe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiquidityFilteredUniverseProviderTest {

    @Test
    void prefersPrevDailyBarOverDailyBar() {
        String body = """
                {
                  "AAPL": {"dailyBar": {"v": 1335636}, "prevDailyBar": {"v": 1563982}},
                  "MSFT": {"dailyBar": {"v": 830370}, "prevDailyBar": {"v": 368151}}
                }
                """;

        var volumes = LiquidityFilteredUniverseProvider.parseVolumes(body);

        assertThat(volumes).containsEntry("AAPL", 1563982L);
        assertThat(volumes).containsEntry("MSFT", 368151L);
    }

    @Test
    void fallsBackToDailyBarWhenPrevDailyBarIsMissing() {
        String body = """
                {
                  "NEWLYLISTED": {"dailyBar": {"v": 2341626}}
                }
                """;

        assertThat(LiquidityFilteredUniverseProvider.parseVolumes(body))
                .containsEntry("NEWLYLISTED", 2341626L);
    }

    @Test
    void yieldsZeroWhenNeitherBarIsPresent() {
        String body = """
                {
                  "NEWLYLISTED": {}
                }
                """;

        assertThat(LiquidityFilteredUniverseProvider.parseVolumes(body))
                .containsEntry("NEWLYLISTED", 0L);
    }

    @Test
    void emptyObjectYieldsEmptyMap() {
        assertThat(LiquidityFilteredUniverseProvider.parseVolumes("{}")).isEmpty();
    }

    @Test
    void malformedJsonThrowsIllegalStateException() {
        assertThatThrownBy(() -> LiquidityFilteredUniverseProvider.parseVolumes("not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
