package com.stockly.scanner;

import com.stockly.scanner.config.AlpacaProperties;
import com.stockly.scanner.config.DiagnosticsProperties;
import com.stockly.scanner.config.ScannerProperties;
import com.stockly.scanner.config.UniverseProperties;
import com.stockly.scanner.signal.PriceDropRule;
import com.stockly.scanner.signal.PriceReversalRule;
import com.stockly.scanner.signal.PriceSpikeRule;
import com.stockly.scanner.signal.ScanRule;
import com.stockly.scanner.signal.VolumeInflowRule;
import com.stockly.scanner.signal.VolumeOutflowRule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ScannerProperties.class, AlpacaProperties.class, DiagnosticsProperties.class,
        UniverseProperties.class})
public class ScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScannerApplication.class, args);
    }

    /**
     * Each rule's bean is registered only if its own {@code enabled} flag is true, so a
     * disabled rule never exists in the {@code List<ScanRule>} ScannerLoop autowires -
     * no runtime on/off check needed anywhere else.
     */
    @Bean
    @ConditionalOnProperty(prefix = "stockly.scanner.spike", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScanRule priceSpikeRule(ScannerProperties properties) {
        var spike = properties.spike();
        return new PriceSpikeRule(spike.thresholdPercent(),
                Duration.ofMinutes(spike.lookbackMinutes()),
                Duration.ofMinutes(spike.cooldownMinutes()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "stockly.scanner.drop", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScanRule priceDropRule(ScannerProperties properties) {
        var drop = properties.drop();
        return new PriceDropRule(drop.thresholdPercent(),
                Duration.ofMinutes(drop.lookbackMinutes()),
                Duration.ofMinutes(drop.cooldownMinutes()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "stockly.scanner.reversal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScanRule priceReversalRule(ScannerProperties properties) {
        var reversal = properties.reversal();
        return new PriceReversalRule(reversal.dropPercent(), reversal.recoveryPercent(),
                Duration.ofMinutes(reversal.lookbackMinutes()),
                Duration.ofMinutes(reversal.cooldownMinutes()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "stockly.scanner.volume-inflow", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScanRule volumeInflowRule(ScannerProperties properties) {
        var inflow = properties.volumeInflow();
        return new VolumeInflowRule(inflow.volumeMultiplierPercent(),
                Duration.ofMinutes(inflow.lookbackMinutes()),
                Duration.ofMinutes(inflow.cooldownMinutes()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "stockly.scanner.volume-outflow", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScanRule volumeOutflowRule(ScannerProperties properties) {
        var outflow = properties.volumeOutflow();
        return new VolumeOutflowRule(outflow.volumeMultiplierPercent(),
                Duration.ofMinutes(outflow.lookbackMinutes()),
                Duration.ofMinutes(outflow.cooldownMinutes()));
    }
}
