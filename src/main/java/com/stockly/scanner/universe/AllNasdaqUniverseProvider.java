package com.stockly.scanner.universe;

import com.stockly.scanner.config.DiagnosticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Every active, tradable Nasdaq-listed symbol Alpaca knows about - not limited to the
 * S&P 500 (see {@link NasdaqOnlyUniverseProvider} for that smaller, older scope). On its
 * own this is too broad to scan reliably (thousands of symbols, many too thinly traded
 * for the free IEX feed to represent well) - {@link LiquidityFilteredUniverseProvider}
 * wraps this and narrows it down.
 */
@Component
public class AllNasdaqUniverseProvider implements UniverseProvider {

    private static final Logger log = LoggerFactory.getLogger(AllNasdaqUniverseProvider.class);

    private final AlpacaAssetLookup assetLookup;
    private final DiagnosticsProperties diagnostics;

    public AllNasdaqUniverseProvider(AlpacaAssetLookup assetLookup, DiagnosticsProperties diagnostics) {
        this.assetLookup = assetLookup;
        this.diagnostics = diagnostics;
    }

    @Override
    public List<String> getUniverse() {
        Set<String> symbols = assetLookup.fetchNasdaqListedSymbols();

        if (symbols.isEmpty()) {
            throw new IllegalStateException("Alpaca returned zero Nasdaq-listed symbols");
        }

        if (diagnostics.logRawData()) {
            log.info("Raw universe data from Alpaca assets ({} Nasdaq symbols): {}",
                    symbols.size(), String.join(", ", symbols));
        }

        return List.copyOf(symbols);
    }
}
