package com.stockly.scanner.universe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockly.scanner.config.AlpacaProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared Alpaca Trading API asset-reference lookup, used by both {@link NasdaqOnlyUniverseProvider}
 * (filters a smaller list down to Nasdaq names) and {@link AllNasdaqUniverseProvider} (returns every
 * Nasdaq-listed symbol Alpaca knows about). Kept separate from the market-data WebSocket connection -
 * this is a plain REST call to a different Alpaca API surface, using the same credentials.
 */
@Component
class AlpacaAssetLookup {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ASSETS_ENDPOINT =
            "https://paper-api.alpaca.markets/v2/assets?status=active&asset_class=us_equity&exchange=NASDAQ";

    private final AlpacaProperties alpacaProperties;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    AlpacaAssetLookup(AlpacaProperties alpacaProperties) {
        this.alpacaProperties = alpacaProperties;
    }

    Set<String> fetchNasdaqListedSymbols() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ASSETS_ENDPOINT))
                .header("APCA-API-KEY-ID", alpacaProperties.apiKeyId())
                .header("APCA-API-SECRET-KEY", alpacaProperties.secretKey())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to reach Alpaca for Nasdaq-listed asset reference data", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Alpaca assets lookup returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseSymbols(response.body());
    }

    /** Pure parsing, split out from the HTTP call so it can be unit tested with a canned payload. */
    static Set<String> parseSymbols(String responseBody) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse Alpaca assets response", e);
        }

        Set<String> symbols = new HashSet<>();
        for (JsonNode node : root) {
            JsonNode symbol = node.get("symbol");
            if (symbol != null) {
                symbols.add(symbol.asText());
            }
        }
        return symbols;
    }
}
