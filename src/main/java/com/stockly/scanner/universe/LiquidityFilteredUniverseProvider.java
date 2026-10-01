package com.stockly.scanner.universe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockly.scanner.config.AlpacaProperties;
import com.stockly.scanner.config.DiagnosticsProperties;
import com.stockly.scanner.config.UniverseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrows {@link AllNasdaqUniverseProvider}'s full Nasdaq universe (thousands of symbols)
 * down to ones with enough trading volume to be worth alerting on. This is the answer to
 * "can we watch all Nasdaq stocks reliably" without paying for full consolidated (SIP)
 * data: exclude the thin/illiquid names where the free IEX feed can't represent the real
 * price well, instead of trying to fix the data source itself.
 *
 * <p><b>Important:</b> Alpaca's free-plan volume figures are themselves IEX-only (confirmed
 * live: e.g. AAPL's reported daily volume was ~1.3M vs. a real total of tens of millions -
 * roughly the same ~1-5% ratio IEX represents of total market volume). So
 * {@code min-daily-volume} is a threshold in "IEX-observed shares," not real share counts.
 * It still works as a *relative* liquidity ranking - thin stocks show thin IEX volume too -
 * just don't interpret the configured number as a literal market volume figure.
 *
 * <p>Uses the previous session's <em>complete</em> volume (see {@link #parseVolumes}), not
 * today's still-accumulating one, so the same threshold passes a consistent set of symbols
 * no matter what time of day the app happens to start.
 *
 * <p>{@code @Primary}: this - not {@link NasdaqOnlyUniverseProvider} - is what
 * {@code ScannerLoop} gets injected with now. Both remain ordinary beans underneath.
 */
@Component
@Primary
public class LiquidityFilteredUniverseProvider implements UniverseProvider {

    private static final Logger log = LoggerFactory.getLogger(LiquidityFilteredUniverseProvider.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SNAPSHOTS_ENDPOINT = "https://data.alpaca.markets/v2/stocks/snapshots?symbols=";
    private static final int SNAPSHOT_BATCH_SIZE = 100;

    private final AllNasdaqUniverseProvider delegate;
    private final AlpacaProperties alpacaProperties;
    private final UniverseProperties universeProperties;
    private final DiagnosticsProperties diagnostics;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public LiquidityFilteredUniverseProvider(AllNasdaqUniverseProvider delegate,
                                              AlpacaProperties alpacaProperties,
                                              UniverseProperties universeProperties,
                                              DiagnosticsProperties diagnostics) {
        this.delegate = delegate;
        this.alpacaProperties = alpacaProperties;
        this.universeProperties = universeProperties;
        this.diagnostics = diagnostics;
    }

    @Override
    public List<String> getUniverse() {
        List<String> allNasdaq = delegate.getUniverse();
        Map<String, Long> volumes = fetchDailyVolumes(allNasdaq);

        if (diagnostics.logRawData()) {
            log.info("Raw daily volume data from Alpaca snapshots (IEX-observed shares/day): {}", volumes);
        }

        long threshold = universeProperties.minDailyVolume();
        List<String> filtered = allNasdaq.stream()
                .filter(symbol -> volumes.getOrDefault(symbol, 0L) >= threshold)
                .toList();

        if (filtered.isEmpty()) {
            throw new IllegalStateException(
                    "Liquidity filter (min-daily-volume=" + threshold + ") left an empty universe");
        }

        log.info("Liquidity-filtered universe: {}/{} Nasdaq symbols meet the {} (IEX-observed) daily volume threshold",
                filtered.size(), allNasdaq.size(), threshold);
        return filtered;
    }

    private Map<String, Long> fetchDailyVolumes(List<String> symbols) {
        Map<String, Long> volumes = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += SNAPSHOT_BATCH_SIZE) {
            List<String> batch = symbols.subList(i, Math.min(i + SNAPSHOT_BATCH_SIZE, symbols.size()));
            volumes.putAll(fetchSnapshotBatch(batch));
        }
        return volumes;
    }

    private Map<String, Long> fetchSnapshotBatch(List<String> batch) {
        String url = SNAPSHOTS_ENDPOINT + String.join(",", batch);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("APCA-API-KEY-ID", alpacaProperties.apiKeyId())
                .header("APCA-API-SECRET-KEY", alpacaProperties.secretKey())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to reach Alpaca for daily volume snapshots", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Alpaca snapshots lookup returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseVolumes(response.body());
    }

    /**
     * Pure parsing, split out from the HTTP call so it can be unit tested with a canned
     * payload. Response is a JSON object keyed by symbol (not an array).
     *
     * <p>Prefers {@code prevDailyBar} (yesterday's complete volume) over {@code dailyBar}
     * (today's still-accumulating volume) - using today's partial figure would make the
     * same threshold pass a shrinking number of symbols as the trading day goes on, since
     * a stock's volume-so-far right after the open is naturally much smaller than its
     * volume-so-far right before the close. {@code dailyBar} is only used as a fallback for
     * symbols with no previous session yet (e.g. newly listed).
     */
    static Map<String, Long> parseVolumes(String responseBody) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse Alpaca snapshots response", e);
        }

        Map<String, Long> volumes = new HashMap<>();
        root.properties().forEach(entry -> {
            String symbol = entry.getKey();
            JsonNode snapshot = entry.getValue();
            long volume = snapshot.path("prevDailyBar").path("v").asLong(
                    snapshot.path("dailyBar").path("v").asLong(0L));
            volumes.put(symbol, volume);
        });
        return volumes;
    }
}
