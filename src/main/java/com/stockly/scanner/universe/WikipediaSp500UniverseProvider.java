package com.stockly.scanner.universe;

import com.stockly.scanner.config.DiagnosticsProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the current S&P 500 constituent list by parsing Wikipedia's "List of
 * S&P 500 companies" page once per app startup.
 *
 * <p>The original v1 plan used Financial Modeling Prep's constituents endpoint,
 * but that turned out to require a paid plan even after moving to its
 * "stable" replacement (the legacy v3 endpoint was retired outright). This
 * free, no-API-key source replaces it — exactly the fallback anticipated in
 * docs/REMAINING_WORK.md. There's nothing to scan without a universe, so any
 * failure here is fatal (fail fast).
 */
@Component
public class WikipediaSp500UniverseProvider implements UniverseProvider {

    private static final Logger log = LoggerFactory.getLogger(WikipediaSp500UniverseProvider.class);
    private static final String URL = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies";
    private static final String CONSTITUENTS_TABLE_ID = "constituents";

    private final DiagnosticsProperties diagnostics;

    public WikipediaSp500UniverseProvider(DiagnosticsProperties diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public List<String> getUniverse() {
        Document doc;
        try {
            doc = Jsoup.connect(URL)
                    .userAgent("Mozilla/5.0 (compatible; StocklyScanner/1.0; personal use)")
                    .get();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reach Wikipedia for the S&P 500 constituent list", e);
        }

        List<String> symbols = parseSymbols(doc);

        if (symbols.isEmpty()) {
            throw new IllegalStateException(
                    "Parsed zero S&P 500 symbols from Wikipedia - the page structure may have changed");
        }

        if (diagnostics.logRawData()) {
            log.info("Raw universe data from Wikipedia ({} symbols): {}", symbols.size(), String.join(", ", symbols));
        }

        return symbols;
    }

    /** Pure parsing, split out from the HTTP call so it can be unit tested with a canned document. */
    static List<String> parseSymbols(Document doc) {
        Element table = doc.getElementById(CONSTITUENTS_TABLE_ID);
        if (table == null) {
            return List.of();
        }

        List<String> symbols = new ArrayList<>();
        for (Element row : table.select("tbody > tr")) {
            Element firstCell = row.selectFirst("td");
            if (firstCell != null) {
                String symbol = firstCell.text().trim();
                if (!symbol.isEmpty()) {
                    symbols.add(symbol);
                }
            }
        }
        return symbols;
    }
}
