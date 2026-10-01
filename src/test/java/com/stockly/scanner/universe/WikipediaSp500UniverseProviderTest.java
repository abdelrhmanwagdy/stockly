package com.stockly.scanner.universe;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WikipediaSp500UniverseProviderTest {

    private Document parse(String html) {
        return Jsoup.parse(html);
    }

    @Test
    void parsesSymbolsFromTheConstituentsTableInOrder() {
        String html = """
                <html><body>
                <table id="constituents" class="wikitable">
                  <thead><tr><th>Symbol</th><th>Security</th></tr></thead>
                  <tbody>
                    <tr><td><a href="/wiki/MMM">MMM</a></td><td>3M</td></tr>
                    <tr><td><a href="/wiki/AAPL">AAPL</a></td><td>Apple Inc.</td></tr>
                    <tr><td><a href="/wiki/BRK.B">BRK.B</a></td><td>Berkshire Hathaway</td></tr>
                  </tbody>
                </table>
                </body></html>
                """;

        assertThat(WikipediaSp500UniverseProvider.parseSymbols(parse(html)))
                .containsExactly("MMM", "AAPL", "BRK.B");
    }

    @Test
    void returnsEmptyListWhenConstituentsTableIsMissing() {
        String html = "<html><body><table id=\"somethingElse\"><tbody><tr><td>X</td></tr></tbody></table></body></html>";

        assertThat(WikipediaSp500UniverseProvider.parseSymbols(parse(html))).isEmpty();
    }

    @Test
    void skipsRowsWithNoCells() {
        String html = """
                <html><body>
                <table id="constituents">
                  <tbody>
                    <tr><td>MMM</td></tr>
                    <tr></tr>
                    <tr><td>AAPL</td></tr>
                  </tbody>
                </table>
                </body></html>
                """;

        assertThat(WikipediaSp500UniverseProvider.parseSymbols(parse(html)))
                .containsExactly("MMM", "AAPL");
    }
}
