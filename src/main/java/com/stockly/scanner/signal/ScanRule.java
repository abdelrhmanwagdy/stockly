package com.stockly.scanner.signal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Evaluates one symbol's rolling price history and reports whether the rule's
 * condition currently holds. Stateless: cooldown and re-arm bookkeeping live in
 * the caller (ScannerLoop), not here - but each rule carries its own lookback
 * and cooldown durations so different rules (e.g. a price spike vs. a price
 * drop) can be tuned independently rather than sharing one set of values.
 */
public interface ScanRule {

    Optional<ScanMatch> evaluate(String symbol, PriceHistory history, Instant now);

    /** How far back this rule looks for its reference price. */
    Duration lookback();

    /** Minimum time between this rule firing again on the same symbol. */
    Duration cooldown();
}
