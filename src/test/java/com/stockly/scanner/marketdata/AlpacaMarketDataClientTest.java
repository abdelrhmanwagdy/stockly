package com.stockly.scanner.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlpacaMarketDataClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode barJson(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void parsesAValidBarEvent() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","o":170.1,"h":170.5,"l":169.9,"c":170.25,"v":12345,"vw":170.0,"t":"2026-01-01T14:31:00Z"}
                """);

        var parsed = AlpacaMarketDataClient.parseBar(bar);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().symbol()).isEqualTo("AAPL");
        assertThat(parsed.get().timestamp()).isEqualTo(Instant.parse("2026-01-01T14:31:00Z"));
        assertThat(parsed.get().price()).isEqualByComparingTo(new BigDecimal("170.25"));
    }

    @Test
    void dollarVolumeUsesVwapTimesVolumeWhenVwapIsPresent() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"v":1000,"vw":170.0,"t":"2026-01-01T14:31:00Z"}
                """);

        var parsed = AlpacaMarketDataClient.parseBar(bar);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().dollarVolume()).isEqualByComparingTo("170000.0"); // 170.0 * 1000, not close * v
    }

    @Test
    void dollarVolumeFallsBackToCloseTimesVolumeWhenVwapIsMissing() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"v":1000,"t":"2026-01-01T14:31:00Z"}
                """);

        var parsed = AlpacaMarketDataClient.parseBar(bar);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().dollarVolume()).isEqualByComparingTo("170250.00"); // 170.25 * 1000
    }

    @Test
    void missingSymbolYieldsEmpty() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","c":170.25,"v":1000,"t":"2026-01-01T14:31:00Z"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void missingCloseYieldsEmpty() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","v":1000,"t":"2026-01-01T14:31:00Z"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void missingVolumeYieldsEmpty() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"t":"2026-01-01T14:31:00Z"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void missingTimestampYieldsEmpty() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"v":1000}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void malformedTimestampYieldsEmptyRatherThanThrowing() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"v":1000,"t":"not-a-timestamp"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void nonNumericCloseYieldsEmptyRatherThanThrowing() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":"not-a-number","v":1000,"t":"2026-01-01T14:31:00Z"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }

    @Test
    void nonNumericVolumeYieldsEmptyRatherThanThrowing() throws Exception {
        JsonNode bar = barJson("""
                {"T":"b","S":"AAPL","c":170.25,"v":"not-a-number","t":"2026-01-01T14:31:00Z"}
                """);

        assertThat(AlpacaMarketDataClient.parseBar(bar)).isEmpty();
    }
}
