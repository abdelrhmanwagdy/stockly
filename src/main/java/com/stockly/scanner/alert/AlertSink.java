package com.stockly.scanner.alert;

import com.stockly.scanner.signal.Alert;

public interface AlertSink {

    void fire(Alert alert);
}
