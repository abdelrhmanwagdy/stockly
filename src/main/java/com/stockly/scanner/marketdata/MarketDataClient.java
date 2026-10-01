package com.stockly.scanner.marketdata;

import java.util.Collection;

/**
 * Abstracts the price-data source. v1 implementation (AlpacaMarketDataClient)
 * streams free real-time IEX data; a future IbkrMarketDataClient can implement
 * this same interface against the TWS API without ScannerLoop or ScanRule
 * changing at all.
 */
public interface MarketDataClient {

    /** Starts streaming price updates for {@code symbols}, pushing each tick to {@code listener}. */
    void connect(Collection<String> symbols, PriceListener listener);

    /** Stops streaming and releases any connection resources. */
    void close();
}
