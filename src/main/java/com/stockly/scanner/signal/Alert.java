package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @param rvolMultiple  current dollar volume as a multiple of this symbol's own recent
 *                      average (e.g. 2.3 = 2.3x normal), empty if RVOL is disabled or
 *                      there wasn't enough history to compute a baseline.
 * @param compositeScore how many distinct rules fired on this symbol within the combo
 *                       window (see {@code stockly.scanner.composite}), including this
 *                       alert itself - 1 means this alert fired alone.
 * @param comboLabels   the distinct rule labels behind {@code compositeScore}, in the
 *                      order they first fired within the window.
 */
public record Alert(
        String symbol,
        BigDecimal pctChange,
        BigDecimal fromPrice,
        BigDecimal toPrice,
        Instant firedAt,
        Duration lookback,
        String label,
        String detail,
        Optional<BigDecimal> rvolMultiple,
        int compositeScore,
        List<String> comboLabels) {

    public static Alert from(ScanMatch match, Optional<BigDecimal> rvolMultiple,
                              int compositeScore, List<String> comboLabels) {
        return new Alert(match.symbol(), match.pctChange(), match.fromPrice(), match.toPrice(),
                match.occurredAt(), match.lookback(), match.label(), match.detail(),
                rvolMultiple, compositeScore, comboLabels);
    }
}
