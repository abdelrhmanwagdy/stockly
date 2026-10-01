package com.stockly.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stockly.marketdata.alpaca")
public record AlpacaProperties(String apiKeyId, String secretKey, String feed) {
}
