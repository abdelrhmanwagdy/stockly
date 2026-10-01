package com.stockly.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in verbose logging of raw external API data, for sanity-checking accuracy. */
@ConfigurationProperties(prefix = "stockly.diagnostics")
public record DiagnosticsProperties(boolean logRawData) {
}
