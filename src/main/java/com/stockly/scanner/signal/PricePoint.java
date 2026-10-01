package com.stockly.scanner.signal;

import java.math.BigDecimal;
import java.time.Instant;

public record PricePoint(Instant timestamp, BigDecimal price, BigDecimal dollarVolume) {
}
