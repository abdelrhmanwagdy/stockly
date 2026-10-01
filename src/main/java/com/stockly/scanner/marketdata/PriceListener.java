package com.stockly.scanner.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

@FunctionalInterface
public interface PriceListener {

    void onBar(String symbol, Instant timestamp, BigDecimal price, BigDecimal dollarVolume);
}
