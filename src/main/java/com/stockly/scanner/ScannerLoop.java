package com.stockly.scanner;

import com.stockly.scanner.alert.AlertSink;
import com.stockly.scanner.config.ScannerProperties;
import com.stockly.scanner.marketdata.MarketDataClient;
import com.stockly.scanner.signal.Alert;
import com.stockly.scanner.signal.PriceHistory;
import com.stockly.scanner.signal.PricePoint;
import com.stockly.scanner.signal.ScanMatch;
import com.stockly.scanner.signal.ScanRule;
import com.stockly.scanner.universe.UniverseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ties universe + market data + scan rules + alert sink together, per the design
 * doc's Section 3 pseudocode: a scheduled evaluation pass over per-symbol rolling
 * history, with cooldown / re-arm state kept here (not in any rule itself) so a
 * symbol only re-alerts after dropping back below threshold and crossing it again.
 *
 * <p>Cooldown/re-arm state is tracked per (symbol, rule) pair, not just per symbol -
 * with multiple rules (e.g. a price spike and a price drop), each fires and cools
 * down independently using its own lookback/cooldown (see {@link ScanRule}), so one
 * rule's cooldown never blocks another's alert, and each direction can be tuned
 * separately (e.g. a longer cooldown for drops than spikes).
 */
@Component
public class ScannerLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScannerLoop.class);

    /** Extra retention beyond the longest rule's lookback so its reference point at
     *  exactly "now - lookback" is never pruned away before it's read. */
    private static final Duration RETENTION_BUFFER = Duration.ofMinutes(2);

    private final UniverseProvider universeProvider;
    private final MarketDataClient marketDataClient;
    private final List<ScanRule> scanRules;
    private final AlertSink alertSink;
    private final ScannerProperties properties;
    private final Duration retainWindow;

    private final Map<String, PriceHistory> historyBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Boolean> currentlyMatching = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();

    /** Identity-based, not class-based: two rules of the same class (or even two mocks
     *  sharing a generated Mockito class) must still be distinguishable by instance. */
    private final Map<ScanRule, Integer> ruleIndex = new IdentityHashMap<>();

    /** Ingestion visibility for manual verification; reset every evaluation cycle. */
    private final AtomicLong ticksSinceLastCycle = new AtomicLong();
    private final Set<String> symbolsSeenSinceLastCycle = ConcurrentHashMap.newKeySet();

    /** Recent fired-alert labels per symbol, for the composite "combo" score - kept
     *  separately from cooldown state since it tracks across rules, not per-rule. */
    private final Map<String, Deque<FiredAlert>> recentAlertsBySymbol = new ConcurrentHashMap<>();

    private record FiredAlert(String label, Instant firedAt) {
    }

    private record ComboResult(int score, List<String> labels) {
    }

    public ScannerLoop(UniverseProvider universeProvider,
                        MarketDataClient marketDataClient,
                        List<ScanRule> scanRules,
                        AlertSink alertSink,
                        ScannerProperties properties) {
        this.universeProvider = universeProvider;
        this.marketDataClient = marketDataClient;
        this.scanRules = scanRules;
        this.alertSink = alertSink;
        this.properties = properties;
        for (int i = 0; i < scanRules.size(); i++) {
            ruleIndex.put(scanRules.get(i), i);
        }
        Duration maxLookback = scanRules.stream()
                .map(ScanRule::lookback)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        if (properties.rvol().enabled()) {
            Duration rvolLookback = Duration.ofMinutes(properties.rvol().lookbackMinutes());
            if (rvolLookback.compareTo(maxLookback) > 0) {
                maxLookback = rvolLookback;
            }
        }
        this.retainWindow = maxLookback.plus(RETENTION_BUFFER);
    }

    @Override
    public void run(ApplicationArguments args) {
        var universe = universeProvider.getUniverse();
        log.info("Universe loaded: {} symbols", universe.size());
        universe.forEach(symbol -> historyBySymbol.put(symbol, new PriceHistory()));
        marketDataClient.connect(universe, this::onBar);
    }

    private void onBar(String symbol, Instant timestamp, BigDecimal price, BigDecimal dollarVolume) {
        PriceHistory history = historyBySymbol.get(symbol);
        if (history != null) {
            history.append(timestamp, price, dollarVolume);
            ticksSinceLastCycle.incrementAndGet();
            symbolsSeenSinceLastCycle.add(symbol);
            log.debug("Tick: {} @ {} = {} (${} volume)", symbol, timestamp, price, dollarVolume);
        }
    }

    @Scheduled(fixedRateString = "#{${stockly.scanner.poll-interval-seconds} * 1000}")
    void evaluate() {
        logIngestionSummary();
        Instant now = Instant.now();

        historyBySymbol.forEach((symbol, history) -> {
            history.prune(now, retainWindow);

            for (ScanRule rule : scanRules) {
                String key = ruleKey(symbol, rule);
                Optional<ScanMatch> match = rule.evaluate(symbol, history, now);

                if (match.isPresent()) {
                    boolean matching = currentlyMatching.getOrDefault(key, false);
                    boolean onCooldown = isOnCooldown(key, now, rule.cooldown());
                    if (!matching && !onCooldown) {
                        ScanMatch scanMatch = match.get();
                        Optional<BigDecimal> rvol = computeRvol(history, now);
                        ComboResult combo = recordFiredAlertAndGetCombo(symbol, scanMatch.label(), now);
                        alertSink.fire(Alert.from(scanMatch, rvol, combo.score(), combo.labels()));
                        lastAlertTime.put(key, now);
                        currentlyMatching.put(key, true);
                    }
                } else {
                    currentlyMatching.put(key, false);
                }
            }
        });
    }

    private String ruleKey(String symbol, ScanRule rule) {
        return symbol + "::" + ruleIndex.get(rule);
    }

    /** Current bar's dollar volume as a multiple of this symbol's own recent average,
     *  independent of whichever rule fired - empty if RVOL is disabled or there isn't
     *  enough history yet to establish a baseline. */
    private Optional<BigDecimal> computeRvol(PriceHistory history, Instant now) {
        if (!properties.rvol().enabled()) {
            return Optional.empty();
        }
        Optional<PricePoint> latest = history.latest();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Instant from = now.minus(Duration.ofMinutes(properties.rvol().lookbackMinutes()));
        return history.averageDollarVolumeExcludingLatest(from)
                .filter(baseline -> baseline.signum() > 0)
                .map(baseline -> latest.get().dollarVolume().divide(baseline, MathContext.DECIMAL64));
    }

    /** Records this fired alert against the symbol's recent-alert window, pruning
     *  anything older than the window, and returns the resulting combo score/labels
     *  (distinct rule labels within the window, including this one). */
    private ComboResult recordFiredAlertAndGetCombo(String symbol, String label, Instant now) {
        if (!properties.composite().enabled()) {
            return new ComboResult(1, List.of(label));
        }
        Duration window = Duration.ofMinutes(properties.composite().windowMinutes());
        Deque<FiredAlert> deque = recentAlertsBySymbol.computeIfAbsent(symbol, s -> new ArrayDeque<>());
        synchronized (deque) {
            Instant cutoff = now.minus(window);
            while (!deque.isEmpty() && deque.peekFirst().firedAt().isBefore(cutoff)) {
                deque.removeFirst();
            }
            deque.addLast(new FiredAlert(label, now));
            List<String> labels = deque.stream().map(FiredAlert::label).distinct().toList();
            return new ComboResult(labels.size(), labels);
        }
    }

    private void logIngestionSummary() {
        long ticks = ticksSinceLastCycle.getAndSet(0);
        int distinctSymbols = symbolsSeenSinceLastCycle.size();
        symbolsSeenSinceLastCycle.clear();
        log.info("Ingestion: {} ticks across {}/{} symbols since last cycle",
                ticks, distinctSymbols, historyBySymbol.size());
    }

    private boolean isOnCooldown(String key, Instant now, Duration cooldown) {
        Instant last = lastAlertTime.get(key);
        return last != null && last.isAfter(now.minus(cooldown));
    }
}
