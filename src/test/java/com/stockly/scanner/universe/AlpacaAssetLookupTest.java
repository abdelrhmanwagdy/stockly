package com.stockly.scanner.universe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlpacaAssetLookupTest {

    @Test
    void parsesSymbolsFromAValidAssetsPayload() {
        String body = """
                [
                  {"symbol":"AAPL","exchange":"NASDAQ","tradable":true},
                  {"symbol":"MSFT","exchange":"NASDAQ","tradable":true},
                  {"symbol":"NVDA","exchange":"NASDAQ","tradable":true}
                ]
                """;

        assertThat(AlpacaAssetLookup.parseSymbols(body))
                .containsExactlyInAnyOrder("AAPL", "MSFT", "NVDA");
    }

    @Test
    void skipsEntriesMissingTheSymbolField() {
        String body = """
                [
                  {"symbol":"AAPL","exchange":"NASDAQ"},
                  {"exchange":"NASDAQ"}
                ]
                """;

        assertThat(AlpacaAssetLookup.parseSymbols(body)).containsExactly("AAPL");
    }

    @Test
    void emptyArrayYieldsEmptySetWithoutThrowing() {
        assertThat(AlpacaAssetLookup.parseSymbols("[]")).isEmpty();
    }

    @Test
    void malformedJsonThrowsIllegalStateException() {
        assertThatThrownBy(() -> AlpacaAssetLookup.parseSymbols("not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
