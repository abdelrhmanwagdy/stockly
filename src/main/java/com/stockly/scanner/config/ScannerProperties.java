package com.stockly.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spike}, {@code drop}, {@code reversal}, {@code volumeInflow}, and
 * {@code volumeOutflow} are configured independently so each can have its own
 * thresholds/lookback/cooldown - e.g. a more sensitive spike threshold than drop, or a
 * longer lookback for reversals. {@code pollIntervalSeconds} is shared: it's the
 * evaluation cadence for the whole loop, not a per-rule concept.
 */
@ConfigurationProperties(prefix = "stockly.scanner")
public record ScannerProperties(
        long pollIntervalSeconds,
        RuleProperties spike,
        RuleProperties drop,
        ReversalProperties reversal,
        VolumeRuleProperties volumeInflow,
        VolumeRuleProperties volumeOutflow,
        RvolProperties rvol,
        CompositeProperties composite) {

    public record RuleProperties(
            boolean enabled,
            double thresholdPercent,
            long lookbackMinutes,
            long cooldownMinutes) {
    }

    /** Two magnitudes (drop into the low, recovery off it) rather than one threshold. */
    public record ReversalProperties(
            boolean enabled,
            double dropPercent,
            double recoveryPercent,
            long lookbackMinutes,
            long cooldownMinutes) {
    }

    /**
     * {@code volumeMultiplierPercent} is a distinct name from {@code RuleProperties.thresholdPercent}
     * on purpose - it means "% of this symbol's own recent average dollar volume"
     * (e.g. 300 = 3x normal), not "% price move," even though the record shape matches.
     */
    public record VolumeRuleProperties(
            boolean enabled,
            double volumeMultiplierPercent,
            long lookbackMinutes,
            long cooldownMinutes) {
    }

    /**
     * Relative volume shown on every alert: current bar's dollar volume vs. this
     * symbol's own recent average over {@code lookbackMinutes}. Independent of any
     * rule's own lookback - it's context attached to whichever rule fired, not a
     * rule itself.
     */
    public record RvolProperties(
            boolean enabled,
            long lookbackMinutes) {
    }

    /** How far back to look when counting how many distinct rules fired on the same
     *  symbol for the composite "combo" score - e.g. a spike and a volume inflow
     *  firing within the same window is stronger evidence than either alone. */
    public record CompositeProperties(
            boolean enabled,
            long windowMinutes) {
    }
}
