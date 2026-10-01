package com.stockly.scanner.alert;

import com.stockly.scanner.signal.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * v1 alert destination: logs to one of five dedicated loggers depending on
 * {@code alert.label()} - "ALERTS.SPIKE", "ALERTS.DROP", "ALERTS.REVERSAL",
 * "ALERTS.INFLOW", or "ALERTS.OUTFLOW". logback-spring.xml gives each its own color on
 * the console and its own durable file (logs/alerts-*.log), so alert history survives a
 * restart without a database, and each kind can be told apart at a glance or counted
 * separately later. Future sinks (Discord, email) implement AlertSink the same way,
 * without touching scan logic.
 */
@Component
public class LoggingAlertSink implements AlertSink {

    private static final Logger spikeLog = LoggerFactory.getLogger("ALERTS.SPIKE");
    private static final Logger dropLog = LoggerFactory.getLogger("ALERTS.DROP");
    private static final Logger reversalLog = LoggerFactory.getLogger("ALERTS.REVERSAL");
    private static final Logger inflowLog = LoggerFactory.getLogger("ALERTS.INFLOW");
    private static final Logger outflowLog = LoggerFactory.getLogger("ALERTS.OUTFLOW");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public void fire(Alert alert) {
        Logger log = switch (alert.label()) {
            case "DROP" -> dropLog;
            case "REVERSAL" -> reversalLog;
            case "INFLOW" -> inflowLog;
            case "OUTFLOW" -> outflowLog;
            default -> spikeLog;
        };
        String sign = alert.pctChange().signum() < 0 ? "" : "+";
        String detailSuffix = alert.detail().isBlank() ? "" : " - " + alert.detail();
        // Skip RVOL when detail is already non-blank (inflow/outflow's own volume
        // multiple already covers "how unusual is this volume" - showing both would
        // just be two different numbers answering the same question.
        String rvolSuffix = alert.detail().isBlank() && alert.rvolMultiple().isPresent()
                ? " [RVOL " + alert.rvolMultiple().get().setScale(1, java.math.RoundingMode.HALF_UP) + "x]"
                : "";
        String comboSuffix = alert.compositeScore() > 1
                ? " [combo x" + alert.compositeScore() + ": " + String.join("+", alert.comboLabels()) + "]"
                : "";

        log.info("[{}] {} {} {}{}% over {}min ({} -> {}){}{}{}",
                TIME_FORMAT.format(alert.firedAt()),
                alert.label(),
                alert.symbol(),
                sign,
                alert.pctChange().setScale(1, java.math.RoundingMode.HALF_UP),
                alert.lookback().toMinutes(),
                alert.fromPrice(),
                alert.toPrice(),
                detailSuffix,
                rvolSuffix,
                comboSuffix);
    }
}
