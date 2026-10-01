package com.stockly.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param minDailyVolume Minimum daily share volume (as observed on Alpaca's free IEX feed -
 *                        roughly 1-5% of real total market volume, not a real share count;
 *                        see docs/EXTERNAL_APIS.md) for a symbol to be included when using
 *                        {@link com.stockly.scanner.universe.LiquidityFilteredUniverseProvider}.
 */
@ConfigurationProperties(prefix = "stockly.universe")
public record UniverseProperties(long minDailyVolume) {
}
