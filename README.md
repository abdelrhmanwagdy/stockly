# Stockly — Price-Spike Scanner

Java/Spring Boot implementation of the signal in `Stock_Scanner_Signal_Design.docx`:
alerts when a stock rises **or falls** >= 3% (configurable) within a rolling
15-minute window, polled every minute, with a 15-minute per-symbol cooldown
tracked independently per direction. The design doc originally scoped this to
rises only; `PriceDropRule` (fallen-stock alerts) was added alongside the
original `PriceSpikeRule`.

v1 runs against **free real-time data** (not a paid IBKR market data
subscription) so the pipeline can be proven end-to-end without cost — see the
plan for why. Swapping in a real IBKR `MarketDataClient` later doesn't require
touching the scan logic.

## Setup

1. Java 21+ and Maven must be on your PATH.
2. Sign up for a free [Alpaca Markets](https://alpaca.markets/) account (paper
   trading) and generate API keys from the dashboard's "API Keys" section —
   used for the free Basic plan's real-time IEX-feed market data.
   (The S&P 500 constituent list itself comes from Wikipedia and needs no
   account — see "Universe source" below.)
3. Copy `.env.example` to `.env` and fill in your real keys:

   ```
   ALPACA_API_KEY_ID=...
   ALPACA_SECRET_KEY=...
   ```

   `.env` is gitignored and loaded automatically at startup (via
   `springboot4-dotenv`) — no need to export environment variables by hand.
   A real OS environment variable of the same name always overrides `.env`,
   so this stays safe to use in any future non-local deployment too.

## Run

```
mvn spring-boot:run
```

Console output shows the loaded universe size, then streams in per-minute bar
data. Fired alerts print to the console — green for spikes, red for drops —
and also append to `logs/alerts-spikes.log` / `logs/alerts-drops.log`
respectively (durable across restarts; rolling history state itself is
in-memory only). Everything also lands in `logs/app.log` uncolored.

## Test

```
mvn test
```

Core signal logic (`PriceHistory`, `PriceSpikeRule`) and orchestration
(`ScannerLoop`) are unit-tested without any live network dependency.

## Universe source

**Current scope (active): all liquid Nasdaq-listed stocks**, via
`LiquidityFilteredUniverseProvider` (`@Primary`):
1. `AllNasdaqUniverseProvider` asks Alpaca for every active, tradable
   Nasdaq-listed symbol it knows about (~5,600, as of writing — not limited
   to the S&P 500).
2. `LiquidityFilteredUniverseProvider` then excludes symbols with low trading
   volume (default threshold: 50,000 — this is in *IEX-observed* shares/day,
   not real market volume, since Alpaca's free-tier volume figures are
   themselves IEX-only; see `docs/EXTERNAL_APIS.md`). As of writing this
   narrows ~5,600 down to ~921 symbols worth alerting on reliably.

This exists because free-tier real-time data (IEX) doesn't represent thinly
traded stocks well — rather than pay for full consolidated data, this
excludes exactly the names where that would matter. Tune the threshold via
`stockly.universe.min-daily-volume` in `application.yml`.

**Two smaller/older scope providers still exist as ordinary (non-primary)
beans**, kept for reference and easy reversion:
- `NasdaqOnlyUniverseProvider` — Wikipedia's S&P 500 list ∩ Nasdaq-listed (159
  symbols). Originally used Financial Modeling Prep for the S&P 500 list, but
  that endpoint now requires a paid FMP plan even on its "stable" API (the
  free one was retired outright) — replaced with Wikipedia scraping.
- `WikipediaSp500UniverseProvider` — the full S&P 500 list, unfiltered.

`UniverseProvider` is an interface specifically so each of these layers is a
drop-in swap — change which one is `@Primary` to change the active scope
without touching `ScannerLoop` or anything else.

## Raw data logging (accuracy sanity-checking)

Set `stockly.diagnostics.log-raw-data: true` in `application.yml` (default
`false`) to log the raw data received from both external APIs, in
human-readable form, so you can eyeball whether it looks correct:

- The full parsed universe list from Wikipedia, logged once at startup.
- Every raw WebSocket message received from Alpaca (auth/subscribe
  confirmations and bar data), logged as it arrives.

This is off by default since the Alpaca side is verbose (one line per
incoming message across ~500 symbols); turn it on only when you actually want
to inspect the wire data, then turn it back off.
