package com.stockly.scanner.universe;

import java.util.List;

public interface UniverseProvider {

    /** The list of ticker symbols to scan. */
    List<String> getUniverse();
}
