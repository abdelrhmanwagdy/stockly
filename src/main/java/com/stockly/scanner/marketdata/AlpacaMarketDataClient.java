package com.stockly.scanner.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockly.scanner.config.AlpacaProperties;
import com.stockly.scanner.config.DiagnosticsProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams real-time (IEX feed) minute bars from Alpaca's free market data plan.
 * Free-plan trade/quote subscriptions cap out at 30 symbols, but minute-bar
 * subscriptions have no such cap, which is what lets this cover the full S&P
 * 500 universe for a proof of concept without a paid data plan. Each incoming
 * bar's close price stands in for "current price" at the 1-minute cadence the
 * signal is designed around.
 *
 * <p>Reconnects automatically with exponential backoff (capped at 30s) so a
 * transient network blip doesn't kill a long-running scan.
 */
@Component
public class AlpacaMarketDataClient implements MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaMarketDataClient.class);
    private static final String STREAM_URL_TEMPLATE = "wss://stream.data.alpaca.markets/v2/%s";
    private static final int SUBSCRIBE_BATCH_SIZE = 100;
    private static final long MAX_BACKOFF_SECONDS = 30;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AlpacaProperties properties;
    private final DiagnosticsProperties diagnostics;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile Collection<String> symbols = List.of();
    private volatile PriceListener listener;
    private volatile WebSocket webSocket;
    private volatile boolean closing = false;

    public AlpacaMarketDataClient(AlpacaProperties properties, DiagnosticsProperties diagnostics) {
        if (isBlank(properties.apiKeyId()) || isBlank(properties.secretKey())) {
            throw new IllegalStateException(
                    "ALPACA_API_KEY_ID / ALPACA_SECRET_KEY environment variables are not set. "
                            + "Sign up for a free paper trading account at alpaca.markets to get them.");
        }
        this.properties = properties;
        this.diagnostics = diagnostics;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void connect(Collection<String> symbols, PriceListener listener) {
        this.symbols = symbols;
        this.listener = listener;
        this.closing = false;
        openConnection();
    }

    @Override
    @PreDestroy
    public void close() {
        closing = true;
        WebSocket current = webSocket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        reconnectExecutor.shutdownNow();
    }

    private void openConnection() {
        String url = STREAM_URL_TEMPLATE.formatted(properties.feed());
        log.info("Connecting to Alpaca market data stream: {}", url);
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), new AlpacaListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        log.warn("Failed to open Alpaca stream connection", error);
                        scheduleReconnect();
                    }
                    // On success, `webSocket` is already set by AlpacaListener.onOpen - not here.
                    // The server's "connected" message can otherwise arrive and be processed
                    // before this callback runs, so handleSuccess() would see a null webSocket
                    // and silently skip sending auth, causing a 10s auth-timeout disconnect.
                });
    }

    private void scheduleReconnect() {
        if (closing) {
            return;
        }
        int attempt = reconnectAttempts.getAndIncrement();
        long delaySeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 5));
        log.warn("Reconnecting to Alpaca in {}s (attempt {})", delaySeconds, attempt + 1);
        reconnectExecutor.schedule(this::openConnection, delaySeconds, TimeUnit.SECONDS);
    }

    private void sendAuth(WebSocket ws) {
        send(ws, Map.of("action", "auth", "key", properties.apiKeyId(), "secret", properties.secretKey()));
    }

    private void sendSubscribe(WebSocket ws) {
        List<String> all = List.copyOf(symbols);
        for (int i = 0; i < all.size(); i += SUBSCRIBE_BATCH_SIZE) {
            List<String> batch = all.subList(i, Math.min(i + SUBSCRIBE_BATCH_SIZE, all.size()));
            send(ws, Map.of("action", "subscribe", "bars", batch));
        }
        log.info("Subscribed to minute bars for {} symbols", all.size());
    }

    private void send(WebSocket ws, Object payload) {
        try {
            ws.sendText(OBJECT_MAPPER.writeValueAsString(payload), true);
        } catch (Exception e) {
            log.warn("Failed to send message to Alpaca stream", e);
        }
    }

    private void handleMessage(String json) {
        if (diagnostics.logRawData()) {
            log.info("Raw Alpaca message: {}", json);
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("Could not parse Alpaca stream message: {}", json, e);
            return;
        }
        if (!root.isArray()) {
            return;
        }
        for (JsonNode node : root) {
            handleEvent(node);
        }
    }

    private void handleEvent(JsonNode node) {
        String type = node.path("T").asText("");
        switch (type) {
            case "success" -> handleSuccess(node.path("msg").asText(""));
            case "error" -> log.warn("Alpaca stream error: {}", node);
            case "b" -> handleBar(node);
            default -> { /* subscription acks etc. - nothing to do */ }
        }
    }

    private void handleSuccess(String msg) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        if ("connected".equals(msg)) {
            sendAuth(ws);
        } else if ("authenticated".equals(msg)) {
            reconnectAttempts.set(0);
            sendSubscribe(ws);
        }
    }

    private void handleBar(JsonNode bar) {
        PriceListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        Optional<ParsedBar> parsed = parseBar(bar);
        if (parsed.isEmpty()) {
            log.warn("Skipping unparseable bar event: {}", bar);
            return;
        }
        ParsedBar tick = parsed.get();
        currentListener.onBar(tick.symbol(), tick.timestamp(), tick.price(), tick.dollarVolume());
    }

    /**
     * Pure parsing, split out from dispatch so it's unit-testable with canned JSON and so a
     * single malformed bar can never throw out of the WebSocket callback (which would skip the
     * webSocket.request(1) call in AlpacaListener.onText and silently stall the stream).
     *
     * <p>Dollar volume uses {@code vw} (that minute's volume-weighted average price) times
     * {@code v} (share volume) when available - more accurate than {@code close * v} - falling
     * back to {@code close * v} if {@code vw} is missing.
     */
    static Optional<ParsedBar> parseBar(JsonNode bar) {
        String symbol = bar.path("S").asText(null);
        JsonNode closeNode = bar.get("c");
        String timestamp = bar.path("t").asText(null);
        JsonNode volumeNode = bar.get("v");
        if (symbol == null || closeNode == null || timestamp == null || volumeNode == null) {
            return Optional.empty();
        }
        try {
            BigDecimal close = new BigDecimal(closeNode.asText());
            BigDecimal volume = new BigDecimal(volumeNode.asText());
            JsonNode vwapNode = bar.get("vw");
            BigDecimal vwap = vwapNode != null ? new BigDecimal(vwapNode.asText()) : close;
            BigDecimal dollarVolume = vwap.multiply(volume);
            return Optional.of(new ParsedBar(symbol, Instant.parse(timestamp), close, dollarVolume));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    record ParsedBar(String symbol, Instant timestamp, BigDecimal price, BigDecimal dollarVolume) {
    }

    private class AlpacaListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            // Set synchronously here (not in buildAsync().whenComplete()) - onOpen receives the
            // same WebSocket instance with no race against the server's incoming messages.
            AlpacaMarketDataClient.this.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Alpaca stream closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Alpaca stream error", error);
            scheduleReconnect();
        }
    }
}
