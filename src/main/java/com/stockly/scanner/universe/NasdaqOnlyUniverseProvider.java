package com.stockly.scanner.universe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Narrows the full S&P 500 universe (from {@link WikipediaSp500UniverseProvider}) down
 * to Nasdaq-listed symbols only. Superseded as the active universe by
 * {@link LiquidityFilteredUniverseProvider} (which covers ALL Nasdaq-listed symbols,
 * not just the S&P 500 subset) - kept here as a smaller, S&P-500-scoped alternative,
 * not wired in as {@code @Primary} anymore.
 */
@Component
public class NasdaqOnlyUniverseProvider implements UniverseProvider {

    private static final Logger log = LoggerFactory.getLogger(NasdaqOnlyUniverseProvider.class);

    private final WikipediaSp500UniverseProvider delegate;
    private final AlpacaAssetLookup assetLookup;

    public NasdaqOnlyUniverseProvider(WikipediaSp500UniverseProvider delegate, AlpacaAssetLookup assetLookup) {
        this.delegate = delegate;
        this.assetLookup = assetLookup;
    }

    @Override
    public List<String> getUniverse() {
        List<String> fullUniverse = delegate.getUniverse();
        Set<String> nasdaqSymbols = assetLookup.fetchNasdaqListedSymbols();

        List<String> filtered = fullUniverse.stream()
                .filter(nasdaqSymbols::contains)
                .toList();

        if (filtered.isEmpty()) {
            throw new IllegalStateException("Filtering to Nasdaq-listed symbols left an empty universe");
        }

        log.info("Narrowed universe to Nasdaq-listed symbols: {}/{} from the full S&P 500 list",
                filtered.size(), fullUniverse.size());
        return filtered;
    }
}
