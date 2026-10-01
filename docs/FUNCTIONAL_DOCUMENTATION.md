# Stockly Scanner — Functional Documentation

## 1. Purpose

Stockly is a personal, long-running Java application that watches a broad universe
of stocks (initially the S&P 500) and prints a console alert whenever a stock's
price rises sharply within a short rolling window. It is a **monitoring/alerting
tool only** — it never places trades.

This document describes the application's functional behavior as built. For the
original signal rationale and open questions this implementation resolves, see
`Stock_Scanner_Signal_Design.docx`. For build/run instructions, see `README.md`.

## 2. Scope

### 2.1 In scope (v1)

- Two signals, both intraday and rolling-window based (Section 3): price
  **spikes** (`PriceSpikeRule`) and price **drops** (`PriceDropRule`) — the
  same rolling-percent-change mechanism, opposite directions, each with
  fully independent cooldown/re-arm state.
- Universe: all Nasdaq-listed stocks with enough trading volume to alert on
  reliably (~921 of ~5,600 active Nasdaq symbols, as of writing) — see
  Section 6.1a. No longer limited to the S&P 500.
- One alert destination: console + a durable local log file.
- Real-time (not delayed) market data from a free data source, as a proof of
  concept ahead of paid IBKR market data.

### 2.2 Explicitly out of scope (deferred)

- Alert delivery beyond console/log (Discord, email).
- Non-price signals (volume spike, breakout, gap, etc.) — price spike and
  price drop are the only two signals built.
- Coverage beyond Nasdaq-listed stocks (NYSE, other exchanges) — see the
  IBKR cost discussion in `docs/EXTERNAL_APIS.md` for why.
- Historical backtesting.
- Any brokerage/trading action.
- The production IBKR data connection (currently swapped out for a free
  alternative — see Section 6).

## 3. Functional behavior

### 3.1 Startup sequence

1. The app reads configuration (Section 5) and validates that required API
   credentials are present. If `ALPACA_API_KEY_ID` or `ALPACA_SECRET_KEY` is
   missing or blank, the app **fails immediately** with a message naming the
   missing variable and where to obtain it — it does not start up partially
   or retry.
2. It fetches the current S&P 500 constituent list (Section 6.1). If this call
   fails or returns an empty list, the app fails immediately — there is
   nothing to scan without a universe.
3. It opens a streaming market-data connection (Section 6.2) and subscribes to
   every symbol in the universe.
4. It begins the recurring evaluation cycle (Section 3.3).

### 3.2 Price ingestion (continuous)

As each symbol's price update arrives from the data source, it is appended to
that symbol's own rolling history buffer as a `(timestamp, price)` pair. This
happens continuously and independently of the evaluation cycle below — ingestion
is push-based (the data source sends updates as they occur), not pulled on a
timer.

### 3.3 Evaluation cycle (every `poll-interval-seconds`, default 60s)

For every symbol in the universe, against **each** configured rule
(`PriceSpikeRule` and `PriceDropRule`), in each cycle:

1. **Prune** history older than the lookback window plus a small buffer (2
   minutes), so memory doesn't grow unbounded over a long-running session.
   Pruning happens once per symbol, shared across both rules.
2. **Compute the reference price**: the most recent recorded price at or before
   "now − lookback-minutes" (default 15 minutes). This is a *rolling* reference
   that moves forward every cycle — not a fixed anchor such as the day's open.
   If no price that old exists yet (e.g. the app just started), the symbol is
   skipped this cycle for that rule.
3. **Compute the percent change**: `(current − reference) / reference × 100`.
4. **Compare to the rule's threshold** (default magnitude 3.0%, either
   direction). If the move doesn't cross this rule's threshold, this rule's
   "currently matching" flag for this symbol is cleared, re-arming it for a
   future alert from this rule.
5. **If the threshold is crossed**, an alert fires from this rule only if
   *both*:
   - this symbol+rule pair is not already flagged as currently matching (i.e.
     this is a new crossing, not a continuation of one already alerted on), and
   - this symbol+rule pair is not on cooldown (Section 3.4).

   If an alert fires, that flag is set and its cooldown timer starts. If the
   threshold is still crossed but already flagged as matching, or on
   cooldown, **no alert fires and no state changes** — this is what prevents
   re-alerting every cycle while a stock stays elevated (or depressed).

### 3.4 Cooldown and re-arm

Each **(symbol, rule)** pair has its own independent cooldown, starting the
moment it last fired an alert, lasting `cooldown-minutes` (default 15). A
stock spiking and a stock dropping are tracked completely separately — one
rule's cooldown on a symbol never blocks the other rule's alert on that same
symbol. While on cooldown, that rule cannot fire again on that symbol **even
if the threshold is still crossed**. The only way to alert again before
cooldown naturally expires is for the move to first fall back under
threshold (clearing the matching flag) — matching the design doc's
"re-alert only on transition back below threshold and then above it again,"
applied symmetrically to drops.

### 3.5 Direction

Both directions are detected: `PriceSpikeRule` fires on a rise of at least
`threshold-percent`, `PriceDropRule` fires on a fall of at least the same
magnitude. Both share `threshold-percent`, `lookback-minutes`, and
`cooldown-minutes` from `stockly.scanner.*` — there's no separate config for
drop sensitivity as of writing.

## 4. Outputs

### 4.1 Console

Every fired alert prints one line to standard output, labeled `SPIKE` or
`DROP` depending on direction, colored **green** for spikes and **red** for
drops (via Logback's `%green`/`%red` pattern converters + the `jansi`
library for Windows console support — `withJansi="true"` on each colored
appender), e.g.:

```
14:32:07.912 INFO  ALERTS.SPIKE - [14:32:07] SPIKE AAPL +3.4% over 15min (172.10 -> 177.95)   (green)
14:41:52.310 INFO  ALERTS.DROP  - [14:41:52] DROP XYZ -3.2% over 15min (50.00 -> 48.40)        (red)
```

Color only applies to a real interactive terminal — if you redirect/pipe the
app's output (e.g. into another program, or capture it in a script), color
detection correctly disables itself and you get plain text instead. That's
expected, not a bug.

All other application logging (startup, universe size, connection/reconnect
events, parse warnings) prints to the console uncolored at INFO level, and
also to `logs/app.log` (Section 4.2b).

### 4.1a Relative volume (RVOL) and composite "combo" score

Every alert (regardless of which rule fired it) can carry two extra pieces of
context, appended to the end of the line:

- **RVOL** — current bar's dollar volume as a multiple of this symbol's own
  recent average (e.g. `[RVOL 2.3x]` = trading at 2.3x its normal volume
  right now), computed over `stockly.scanner.rvol.lookback-minutes`
  independently of whichever rule's own lookback fired the alert. Skipped
  for `INFLOW`/`OUTFLOW` alerts, since their `detail` already states a
  volume multiple (from their own rule-specific baseline window) and
  showing two different multiples side by side would just be confusing, not
  additive.
- **Combo score** — if more than one distinct rule fired on the same symbol
  within `stockly.scanner.composite.window-minutes`, the alert shows
  `[combo x2: SPIKE+INFLOW]` (or `x3`, etc.) naming every distinct rule
  label that fired in that window, current alert included. A lone alert
  with nothing else corroborating it in the window shows no combo suffix at
  all — this is meant to make "a spike that's also seeing volume inflow" or
  "a reversal confirmed by the drop rule easing off" visually stand out from
  a single, unconfirmed signal, without changing which alerts actually fire
  or their cooldown behavior.

```
[14:32:07] SPIKE AAPL +3.4% over 15min (172.10 -> 177.95) [RVOL 2.3x] [combo x2: SPIKE+INFLOW]
```

Both are pure presentation/context — they never gate whether an alert fires,
never affect cooldown, and can each be turned off independently via their
`enabled` flag (Section 5) if they're not useful.

### 4.2 Durable alert logs (split by direction)

Fired alerts append to one of two separate files depending on direction —
`logs/alerts-spikes.log` for spikes, `logs/alerts-drops.log` for drops (both
rotated daily, 30 days retained) — so you can review or count each direction
independently without filtering a combined file. Rolling price history and
cooldown/matching flags are in-memory only and reset on restart; these files
are the durable record of what fired.

### 4.2a Why split instead of one combined file

Originally a single `logs/alerts.log`. Splitting by direction was a
deliberate choice once both `PriceSpikeRule` and `PriceDropRule` existed:
counting/analyzing "how many drops today" no longer needs a regex over a
mixed file. Both still also propagate into `logs/app.log` (Section 4.2b),
so a single chronological view combining both directions with everything
else still exists if needed.

### 4.2b General application log

`logs/app.log` (rotated daily, 30 days retained) mirrors everything the
console shows, uncolored — startup, universe fetch, raw-data diagnostics
(Section 6.3), ingestion summaries, connection/reconnect events, and both
alert directions. Added after this was initially console-only and raw-data
logs (asked for specifically to sanity-check external API accuracy) had
nowhere to persist once the terminal closed.

## 5. Configuration reference

All settings live in `src/main/resources/application.yml`. Values marked
"env" are read from environment variables and must be set before running —
the app will not start without them.

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `stockly.scanner.threshold-percent` | — | `3.0` | Minimum % rise to qualify as a spike |
| `stockly.scanner.lookback-minutes` | — | `15` | Rolling comparison window |
| `stockly.scanner.poll-interval-seconds` | — | `60` | How often the evaluation cycle runs |
| `stockly.scanner.cooldown-minutes` | — | `15` | Minimum time between alerts for the same symbol |
| `stockly.scanner.spike.enabled` | — | `true` | Turn the price-spike rule on/off |
| `stockly.scanner.drop.enabled` | — | `true` | Turn the price-drop rule on/off |
| `stockly.scanner.reversal.enabled` | — | `true` | Turn the reversal rule on/off |
| `stockly.scanner.volume-inflow.enabled` | — | `true` | Turn the volume-inflow rule on/off |
| `stockly.scanner.volume-outflow.enabled` | — | `true` | Turn the volume-outflow rule on/off |
| `stockly.scanner.rvol.enabled` | — | `true` | Show relative volume on alerts (Section 4.1a) |
| `stockly.scanner.rvol.lookback-minutes` | — | `30` | Baseline window for "normal" dollar volume |
| `stockly.scanner.composite.enabled` | — | `true` | Show the combo score on alerts (Section 4.1a) |
| `stockly.scanner.composite.window-minutes` | — | `10` | How far back to count other rules firing on the same symbol |
| `stockly.marketdata.alpaca.api-key-id` | `ALPACA_API_KEY_ID` | *(required)* | Alpaca API key ID |
| `stockly.marketdata.alpaca.secret-key` | `ALPACA_SECRET_KEY` | *(required)* | Alpaca API secret |
| `stockly.marketdata.alpaca.feed` | — | `iex` | Alpaca data feed to subscribe to |
| `stockly.universe.min-daily-volume` | — | `50000` | Liquidity threshold in IEX-observed shares/day (not real volume — see Section 6.1) |
| `stockly.diagnostics.log-raw-data` | — | `false` | Log raw external API data (see Section 6.3) |

Setting a rule's `enabled` to `false` and restarting means that rule's Spring
bean is never created at all (`@ConditionalOnProperty`), not just skipped at
evaluation time — it never appears in the scanner's rule list, tracks no
cooldown state, and doesn't contribute to how far back price history is
retained.

## 6. Data sources and their functional role

### 6.1 Universe — all liquid Nasdaq stocks (current, `@Primary`)

Two steps, both run once at startup:

1. **`AllNasdaqUniverseProvider`** calls Alpaca's Trading API asset reference
   endpoint (`GET /v2/assets?exchange=NASDAQ&status=active`, via the shared
   `AlpacaAssetLookup` helper — a different Alpaca API surface from the
   streaming market data connection, same credentials) to get every active,
   tradable Nasdaq-listed symbol. As of writing: ~5,600 symbols. Not limited
   to the S&P 500.
2. **`LiquidityFilteredUniverseProvider`** then calls Alpaca's snapshots
   endpoint (`GET /v2/stocks/snapshots?symbols=...`, batched 100 symbols per
   call) to get each symbol's daily trading volume, and excludes anything
   below `stockly.universe.min-daily-volume` (default `50000`). As of
   writing this narrows ~5,600 down to ~921.

**Why this exists:** free-tier real-time data (Section 6.2) doesn't
represent thinly-traded stocks well. Rather than pay for full consolidated
data to fix that for every symbol, this excludes exactly the symbols where
it would matter, for free.

**Important caveat on the threshold's units:** Alpaca's free-plan volume
figures are themselves IEX-only — confirmed live (e.g. AAPL's reported daily
volume was ~1.3M vs. a real total of tens of millions, roughly the same
~1-5% IEX represents of total market volume). So `min-daily-volume` is a
threshold in *IEX-observed shares*, not real share counts — it still works
as a relative liquidity ranking (thin stocks show thin IEX volume too), but
don't interpret the configured number as a literal market volume figure.

### 6.1a Smaller/older universe providers (kept, not active)

Two earlier scope decisions remain as ordinary (non-`@Primary`) beans, for
reference and easy reversion — change which provider is `@Primary` to switch
the active scope, no other code changes needed:

- **`NasdaqOnlyUniverseProvider`** — Wikipedia's S&P 500 list (503 symbols)
  narrowed to Nasdaq-listed names only (159), using the same
  `AlpacaAssetLookup` helper. This was the scope right before the
  all-Nasdaq/liquidity-filtered approach replaced it.
- **`WikipediaSp500UniverseProvider`** — parses the "Symbol" column of the
  `id="constituents"` table on Wikipedia's "List of S&P 500 companies" page.
  Originally replaced an FMP-based plan: FMP's constituents endpoint
  requires a paid plan on live testing (the free `v3/sp500_constituent`
  endpoint was retired outright, and its "stable" replacement returns
  "Restricted Endpoint" on a free-tier key). Depends on Wikipedia's page
  structure remaining stable; fails fast ("Parsed zero S&P 500 symbols from
  Wikipedia") if that structure changes, rather than silently scanning an
  empty or wrong universe.

### 6.2 Market data — Alpaca (free tier, proof of concept)

The application streams **real-time** (not delayed) per-minute price bars over
a WebSocket connection, one subscription per symbol, using Alpaca's free IEX
feed. Each bar's closing price is treated as that symbol's "current price" for
that minute — this lines up with the 1-minute evaluation cadence.

**Functional limitation to be aware of:** IEX is a single exchange
representing a small slice of total US equity trading volume. Thinly-traded
symbols may show sparser or slightly different price action than the full
consolidated market tape would show. This is acceptable for proving the
pipeline works end-to-end; it is a known gap versus using the full tape (e.g.
a future IBKR connection).

**Connection resilience:** if the stream disconnects for any reason, the app
automatically reconnects, waiting 1s, 2s, 4s, 8s, 16s, then capping at 30s
between attempts, and re-subscribes to the full universe on reconnect. The
process does not exit on a transient network or feed interruption.

### 6.3 Raw data logging (accuracy sanity-checking)

Controlled by `stockly.diagnostics.log-raw-data` (default `false`). When
enabled:

- `WikipediaSp500UniverseProvider` logs the complete parsed symbol list once
  at startup, e.g. `Raw universe data from Wikipedia (503 symbols): MMM, AAPL, ...`
  (note: this only fires when `NasdaqOnlyUniverseProvider`/`WikipediaSp500UniverseProvider`
  are the active path — the current `@Primary` all-Nasdaq/liquidity-filtered
  path doesn't have raw-data logging wired in yet).
- `AlpacaMarketDataClient` logs every raw WebSocket message exactly as
  received (auth/subscribe confirmations and bar data alike) before any
  parsing happens, e.g. `Raw Alpaca message: [{"T":"b","S":"AAPL","c":170.25,...}]`

Both log at INFO level under each class's own logger, so they print to the
console like any other log line — no separate file, no log-level fiddling
needed, just the one config flag. Off by default because the Alpaca side is
one line per incoming message across the whole universe.

## 7. Error handling summary

| Condition | Behavior |
|---|---|
| Required API key/secret env var missing | App fails to start, with a message naming the variable and where to get it |
| Universe fetch fails or returns empty | App fails to start |
| Market data stream disconnects | Automatic reconnect with backoff (Section 6.2); app keeps running |
| A single price message fails to parse | That message is logged and skipped; scanning continues for all other symbols |

## 8. Test coverage

`mvn test` runs 37 unit tests with no live network dependency:

- **Signal logic** — rolling reference lookup, threshold boundary (inclusive),
  behavior with insufficient history, the rolling-vs-fixed-anchor distinction
  from the design doc, and — for both `PriceSpikeRule` and `PriceDropRule` —
  that each only fires in its own direction, never the other's.
- **History buffer** — append/prune/lookup-by-age semantics.
- **Orchestration** — alert suppression while continuously above threshold,
  no re-fire while on cooldown even after re-arming, re-fire once cooldown has
  elapsed, correct ingestion of incoming price ticks into per-symbol history,
  and that two different rules on the same symbol cool down independently
  (one rule's active cooldown never blocks the other's alert).
- **Universe parsing** — Wikipedia constituents table parsing (valid table,
  missing table, rows with no cells).
- **Universe narrowing** — Alpaca assets-response parsing (`AlpacaAssetLookup`,
  used by both `NasdaqOnlyUniverseProvider` and `AllNasdaqUniverseProvider`)
  and Alpaca snapshots-response volume parsing (`LiquidityFilteredUniverseProvider`) -
  valid payloads, missing fields, malformed JSON, `dailyBar`/`prevDailyBar` fallback.
- **Market data parsing** — Alpaca bar-event parsing (valid bar, each missing
  field, malformed timestamp/price handled without throwing).

Verified live (not just unit-tested) as of 2026-09-15: universe fetch finds
~5,600 active Nasdaq symbols via Alpaca, the liquidity filter narrows that to
~921, and the Alpaca WebSocket connects, authenticates, and subscribes to
that filtered set successfully. See `docs/REMAINING_WORK.md` for the bug
found and fixed during that verification.

## 9. Planned extensions (not built in v1)

These were deliberately deferred, and the architecture (interfaces for
`MarketDataClient`, `ScanRule`, `AlertSink`) is shaped so each can be added
without modifying existing scan logic:

- `IbkrMarketDataClient` — swap-in real IBKR TWS API connection once real-time
  market data subscriptions are set up.
- Additional `AlertSink`s — Discord webhook, email.
- Additional `ScanRule`s — volume spike, breakout, gap up/down, combined
  price+volume signals.
