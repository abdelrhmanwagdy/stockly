package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * A rule firing against a symbol's current history, before cooldown/re-arm logic is applied.
 *
 * @param label  which rule produced this match (e.g. "SPIKE", "DROP", "REVERSAL",
 *               "INFLOW", "OUTFLOW") - set by the rule itself rather than inferred from
 *               {@code pctChange}'s sign, since e.g. a reversal's recovery percentage is
 *               always positive just like a spike's.
 * @param detail optional extra context beyond the price move, e.g. a volume multiple for
 *               the inflow/outflow rules. Empty string for rules where the price move
 *               alone is the whole story.
 */
public record ScanMatch(
        String symbol,
        BigDecimal pctChange,
        BigDecimal fromPrice,
        BigDecimal toPrice,
        Instant occurredAt,
        Duration lookback,
        String label,
        String detail) {
}
