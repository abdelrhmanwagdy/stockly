package com.stockly.scanner;

import com.stockly.scanner.alert.AlertSink;
import com.stockly.scanner.config.ScannerProperties;
import com.stockly.scanner.marketdata.MarketDataClient;
import com.stockly.scanner.marketdata.PriceListener;
import com.stockly.scanner.signal.Alert;
import com.stockly.scanner.signal.PriceHistory;
import com.stockly.scanner.signal.ScanMatch;
import com.stockly.scanner.signal.ScanRule;
import com.stockly.scanner.universe.UniverseProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the cooldown / re-arm orchestration in isolation from any real market
 * data or HTTP calls, using a fake UniverseProvider/MarketDataClient/ScanRule per
 * the doc's own open question about testing the data-fetch layer.
 */
class ScannerLoopTest {

    private UniverseProvider universeProvider;
    private MarketDataClient marketDataClient;
    private ScanRule scanRule;
    private AlertSink alertSink;

    @BeforeEach
    void setUp() {
        universeProvider = mock(UniverseProvider.class);
        when(universeProvider.getUniverse()).thenReturn(List.of("AAPL"));
        marketDataClient = mock(MarketDataClient.class);
        scanRule = mock(ScanRule.class);
        alertSink = mock(AlertSink.class);
    }

    /** Stubs lookback/cooldown on each rule (per-rule now, not shared config) and wires up the loop.
     *  RVOL/composite are off here so these cooldown-focused tests aren't affected by them. */
    private ScannerLoop newLoop(long cooldownMinutes, ScanRule... rules) {
        for (ScanRule rule : rules) {
            when(rule.lookback()).thenReturn(Duration.ofMinutes(15));
            when(rule.cooldown()).thenReturn(Duration.ofMinutes(cooldownMinutes));
        }
        ScannerLoop loop = new ScannerLoop(universeProvider, marketDataClient, List.of(rules), alertSink,
                testProperties(false, 20, false, 10));
        loop.run(null);
        return loop;
    }

    private ScannerLoop newLoop(long cooldownMinutes) {
        return newLoop(cooldownMinutes, scanRule);
    }

    private static ScannerProperties testProperties(boolean rvolEnabled, long rvolLookbackMinutes,
                                                      boolean compositeEnabled, long compositeWindowMinutes) {
        var dummyRule = new ScannerProperties.RuleProperties(true, 0, 0, 0);
        var dummyReversal = new ScannerProperties.ReversalProperties(true, 0, 0, 0, 0);
        var dummyVolume = new ScannerProperties.VolumeRuleProperties(true, 0, 0, 0);
        return new ScannerProperties(60, dummyRule, dummyRule, dummyReversal, dummyVolume, dummyVolume,
                new ScannerProperties.RvolProperties(rvolEnabled, rvolLookbackMinutes),
                new ScannerProperties.CompositeProperties(compositeEnabled, compositeWindowMinutes));
    }

    private ScanMatch aMatch() {
        return new ScanMatch("AAPL", BigDecimal.valueOf(3.5), BigDecimal.valueOf(100), BigDecimal.valueOf(103.5),
                Instant.now(), Duration.ofMinutes(15), "SPIKE", "");
    }

    @Test
    void firesOnceThenSuppressesRepeatAlertsWhileStillAboveThreshold() {
        ScannerLoop loop = newLoop(15);
        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));

        loop.evaluate();
        loop.evaluate();
        loop.evaluate();

        verify(alertSink, times(1)).fire(any());
    }

    @Test
    void doesNotRefireAfterReArmIfStillOnCooldown() {
        ScannerLoop loop = newLoop(15);
        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate(); // fires

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.empty());
        loop.evaluate(); // drops back below threshold -> re-arms

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate(); // rises again, but cooldown (15min) hasn't elapsed

        verify(alertSink, times(1)).fire(any());
    }

    @Test
    void refiresAfterReArmOnceCooldownHasExpired() {
        ScannerLoop loop = newLoop(0); // cooldown effectively always elapsed
        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate(); // fires

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.empty());
        loop.evaluate(); // drops back below threshold -> re-arms

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate(); // rises again -> should re-fire

        verify(alertSink, times(2)).fire(any());
    }

    @Test
    void incomingPriceTicksAreAppendedToTheSymbolsHistory() {
        ScannerLoop loop = newLoop(15);

        ArgumentCaptor<PriceListener> listenerCaptor = ArgumentCaptor.forClass(PriceListener.class);
        verify(marketDataClient).connect(eq(List.of("AAPL")), listenerCaptor.capture());

        listenerCaptor.getValue().onBar("AAPL", Instant.now(), BigDecimal.valueOf(150), BigDecimal.ZERO);

        when(scanRule.evaluate(eq("AAPL"), any(PriceHistory.class), any())).thenAnswer(invocation -> {
            PriceHistory history = invocation.getArgument(1);
            return history.latest().isPresent() ? Optional.of(aMatch()) : Optional.empty();
        });

        loop.evaluate();

        verify(alertSink, times(1)).fire(any());
    }

    @Test
    void twoRulesOnTheSameSymbolCooldownIndependently() {
        ScanRule spikeRule = mock(ScanRule.class, "spikeRule");
        ScanRule dropRule = mock(ScanRule.class, "dropRule");
        ScannerLoop loop = newLoop(15, spikeRule, dropRule);

        // Spike rule matches and fires; drop rule never matches.
        when(spikeRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        when(dropRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.empty());
        loop.evaluate();

        // Drop rule now matches too - should fire independently, unaffected by the
        // spike rule's active cooldown on the very same symbol.
        when(dropRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate();

        verify(alertSink, times(2)).fire(any());
    }

    @Test
    void computesRvolFromRecentDollarVolumeHistoryExcludingTheLatestBar() {
        when(scanRule.lookback()).thenReturn(Duration.ofMinutes(15));
        when(scanRule.cooldown()).thenReturn(Duration.ofMinutes(15));
        ScannerLoop loop = new ScannerLoop(universeProvider, marketDataClient, List.of(scanRule), alertSink,
                testProperties(true, 20, false, 10));
        loop.run(null);

        ArgumentCaptor<PriceListener> listenerCaptor = ArgumentCaptor.forClass(PriceListener.class);
        verify(marketDataClient).connect(eq(List.of("AAPL")), listenerCaptor.capture());
        PriceListener listener = listenerCaptor.getValue();

        Instant base = Instant.now();
        listener.onBar("AAPL", base.minusSeconds(15 * 60), BigDecimal.valueOf(100), BigDecimal.valueOf(1000));
        listener.onBar("AAPL", base.minusSeconds(10 * 60), BigDecimal.valueOf(101), BigDecimal.valueOf(1000));
        listener.onBar("AAPL", base, BigDecimal.valueOf(103), BigDecimal.valueOf(5000)); // 5x the 1000 baseline

        when(scanRule.evaluate(eq("AAPL"), any(PriceHistory.class), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate();

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertSink).fire(alertCaptor.capture());
        assertThat(alertCaptor.getValue().rvolMultiple()).isPresent();
        assertThat(alertCaptor.getValue().rvolMultiple().get()).isEqualByComparingTo("5");
    }

    @Test
    void rvolIsAbsentWhenDisabled() {
        when(scanRule.lookback()).thenReturn(Duration.ofMinutes(15));
        when(scanRule.cooldown()).thenReturn(Duration.ofMinutes(15));
        ScannerLoop loop = new ScannerLoop(universeProvider, marketDataClient, List.of(scanRule), alertSink,
                testProperties(false, 20, false, 10));
        loop.run(null);

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate();

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertSink).fire(alertCaptor.capture());
        assertThat(alertCaptor.getValue().rvolMultiple()).isEmpty();
    }

    @Test
    void compositeScoreCountsDistinctRuleLabelsFiredWithinTheWindow() {
        ScanRule spikeRule = mock(ScanRule.class, "spikeRule");
        ScanRule inflowRule = mock(ScanRule.class, "inflowRule");
        when(spikeRule.lookback()).thenReturn(Duration.ofMinutes(15));
        when(spikeRule.cooldown()).thenReturn(Duration.ofMinutes(15));
        when(inflowRule.lookback()).thenReturn(Duration.ofMinutes(15));
        when(inflowRule.cooldown()).thenReturn(Duration.ofMinutes(15));

        ScannerLoop loop = new ScannerLoop(universeProvider, marketDataClient, List.of(spikeRule, inflowRule),
                alertSink, testProperties(false, 20, true, 10));
        loop.run(null);

        when(spikeRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        when(inflowRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.empty());
        loop.evaluate(); // spike fires alone

        ScanMatch inflowMatch = new ScanMatch("AAPL", BigDecimal.valueOf(2), BigDecimal.valueOf(100),
                BigDecimal.valueOf(102), Instant.now(), Duration.ofMinutes(15), "INFLOW", "volume 4.0x normal");
        when(inflowRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(inflowMatch));
        loop.evaluate(); // inflow fires too, within the composite window

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertSink, times(2)).fire(alertCaptor.capture());
        List<Alert> fired = alertCaptor.getAllValues();
        assertThat(fired.get(0).compositeScore()).isEqualTo(1);
        assertThat(fired.get(1).compositeScore()).isEqualTo(2);
        assertThat(fired.get(1).comboLabels()).containsExactly("SPIKE", "INFLOW");
    }

    @Test
    void compositeScoreStaysOneWhenDisabled() {
        when(scanRule.lookback()).thenReturn(Duration.ofMinutes(15));
        when(scanRule.cooldown()).thenReturn(Duration.ofMinutes(15));
        ScannerLoop loop = new ScannerLoop(universeProvider, marketDataClient, List.of(scanRule), alertSink,
                testProperties(false, 20, false, 10));
        loop.run(null);

        when(scanRule.evaluate(eq("AAPL"), any(), any())).thenReturn(Optional.of(aMatch()));
        loop.evaluate();

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertSink).fire(alertCaptor.capture());
        assertThat(alertCaptor.getValue().compositeScore()).isEqualTo(1);
        assertThat(alertCaptor.getValue().comboLabels()).containsExactly("SPIKE");
    }
}
