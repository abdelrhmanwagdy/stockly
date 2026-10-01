# Stockly Scanner — Remaining Work

Status as of 2026-09-15: Milestones 1-6 are done, plus two originally-deferred
gaps (tick visibility, parsing unit tests) have since been closed. Live
verification against real Alpaca credentials additionally surfaced and fixed
two real bugs — see Section 1. This document covers what's still open: the
last bit of live verification, one still-open risk, and the features
explicitly deferred out of v1.

## 1. Milestone 7 — End-to-end live verification (mostly done)

Verified live on 2026-09-15 with real credentials:
- Universe fetch: **works** — 503 symbols parsed from Wikipedia, no API key
  needed (see Section 1.1 on why this changed from the original FMP plan).
- Alpaca connection: **works** — connects, authenticates, and subscribes to
  minute bars for all 503 symbols, after fixing a race condition (Section 1.2).
- Reconnect-with-backoff: incidentally exercised for real while diagnosing
  the bug below (1s → 2s → 4s escalation observed) and confirmed working.

Universe scope changed twice since, both deliberate decisions, not bug
fixes:
1. `NasdaqOnlyUniverseProvider` narrowed the 503-symbol Wikipedia list to
   Nasdaq-listed symbols only (159).
2. Then superseded by `AllNasdaqUniverseProvider` + `LiquidityFilteredUniverseProvider`
   (now `@Primary`): all active Nasdaq-listed symbols (~5,600, not limited
   to the S&P 500), filtered down to ones with enough trading volume to
   alert on reliably (~921). See `docs/EXTERNAL_APIS.md` Section 3 for the
   full reasoning and the empirically-confirmed IEX-volume caveat. Both
   older providers (`NasdaqOnlyUniverseProvider`, `WikipediaSp500UniverseProvider`)
   remain as non-`@Primary` beans.

Verified live: both steps work correctly, and Alpaca subscribes
successfully to the full filtered set (921 symbols).

Still open (blocked on market hours, not on anything left to build):

1. Run `mvn spring-boot:run` during US market hours (9:30am-4pm ET, Mon-Fri —
   the free IEX feed is quiet outside those hours; live testing so far
   happened outside those hours, so 0 ticks were seen, as expected).
2. Confirm the `Ingestion: N ticks across M/921 symbols` log line shows a
   healthy, non-zero, growing count once the market is open.
3. Let it run through at least one full 15-minute window; watch for any fired
   alert and cross-check it against a real chart (does the logged % move
   match reality?).

### 1.1 Universe source changed: FMP → Wikipedia

The original plan (`FmpSp500UniverseProvider`, using Financial Modeling
Prep's `sp500_constituent` endpoint) failed on first live run: the legacy
`v3` endpoint is retired outright ("Legacy Endpoint... no longer supported"),
and its documented "stable" replacement (`stable/sp500-constituent`) returned
"Restricted Endpoint: not available under your current subscription" even
with a real free-tier key. Confirmed by direct testing, not just docs.

Fixed by replacing it with `WikipediaSp500UniverseProvider`, which parses the
`id="constituents"` table on Wikipedia's "List of S&P 500 companies" page
(via `jsoup`) — free, no API key, no rate limit. `FmpSp500UniverseProvider`
and `FmpProperties` were deleted; `FMP_API_KEY` is no longer used anywhere.
This was exactly the fallback anticipated when FMP was first chosen.

### 1.2 Alpaca WebSocket race condition (fixed)

Live testing showed every connection attempt closing with code 1006 almost
exactly 10 seconds after connecting — matching Alpaca's documented 10-second
auth timeout exactly. Root cause: `AlpacaMarketDataClient.webSocket` was only
assigned inside `buildAsync().whenComplete(...)`, an async callback that can
run *after* the server's `"connected"` message has already arrived and been
processed by `onText()`. When that happened, `handleSuccess()` read a still-
null `webSocket` field and silently skipped sending the auth message,
guaranteeing the connection would be killed 10 seconds later for never
authenticating.

Fixed by assigning `webSocket` synchronously inside `AlpacaListener.onOpen()`,
which receives the same `WebSocket` instance directly with no race. Verified
live afterward: connects, authenticates, and subscribes successfully every
time.

## 2. Non-issue on the active path: symbol format mismatch risk (Wikipedia vs Alpaca)

A handful of S&P 500 tickers use punctuated share-class suffixes (e.g.
`BRK.B`, `BF.B`) that different sources format inconsistently — dot, dash, or
no separator. This was a real risk when `NasdaqOnlyUniverseProvider`
intersected Wikipedia's list with Alpaca's — a mismatched spelling would
silently exclude that symbol.

The current active path (`AllNasdaqUniverseProvider`) doesn't touch
Wikipedia at all — it takes Alpaca's Nasdaq asset list as-is, so there's no
second source to disagree with it. This risk is now scoped entirely to the
non-`@Primary` `NasdaqOnlyUniverseProvider` fallback path, not the active one.

## 3. Deferred features (explicitly out of scope for v1)

These were intentionally not built now — the design doc scopes them out, and
the interfaces (`MarketDataClient`, `AlertSink`, `ScanRule`) exist specifically
so each can be added later without touching what's already working.

### 3.1 IBKR TWS integration (the actual end goal)

Build `IbkrMarketDataClient implements MarketDataClient` using the TWS Java
API against a running IB Gateway/TWS process, requiring:
- A real-time market data subscription per exchange you want coverage on.
- Handling IBKR's own connection lifecycle (`EClientSocket`/`EWrapper`) and
  pacing/rate limits across ~500 symbols.
- A config switch (e.g. a Spring profile or property) to choose Alpaca vs
  IBKR without code changes elsewhere.
- Re-validating the whole pipeline against real IBKR data once wired in —
  the free-tier caveats noted in the functional doc (IEX-only coverage) go
  away once this lands.

### 3.2 Additional alert destinations

Implement `AlertSink` for:
- **Discord** — webhook POST per alert.
- **Email** — SMTP send per alert, or batched digest.

Both are additive: `ScannerLoop` already calls every configured `AlertSink`
uniformly, so this is new classes plus config, not a change to scan logic.

### 3.3 Additional signals

`PriceDropRule` (fallen-stock alerts, done 2026-09-15) was the first
additional signal — `ScannerLoop` now takes `List<ScanRule>` (was a single
`ScanRule` bean) with per-(symbol, rule) cooldown/re-arm state, exactly the
small change anticipated here. `PriceSpikeRule` and `PriceDropRule` share
their percent-change math via a new package-private `PriceChangeCalculator`,
differing only in threshold direction.

Still unbuilt, per the design doc's Section 6 list:
- Volume spike.
- Breakout (price crossing a recent high/low).
- Gap up/down (comparing to prior session close, not intraday).
- Combined price+volume confirmation.

Adding any of these now is purely additive: implement `ScanRule`, add a
`@Bean` in `ScannerApplication`, and it's automatically included in the
`List<ScanRule>` `ScannerLoop` already iterates — no other code changes.

### 3.4 Universe beyond Nasdaq

A broader/filtered universe was already built (Section 1: all liquid Nasdaq
stocks, not just the S&P 500). Still unbuilt: coverage of other exchanges
(NYSE, etc. — blocked on the IBKR cost/cap discussion), sector filters, or a
custom watchlist (see below). `UniverseProvider` remains the seam for all of
these — a new implementation, no changes elsewhere.

**Specific planned feature (noted 2026-09-15, not yet designed in detail):**
a file-based watchlist — the user enters a list of stock symbols in a file,
and those get added as watchers, constrained to Nasdaq-listed symbols only.
Likely shape: `FileWatchlistProvider implements UniverseProvider` reading a
plain text/CSV file, validating each entered symbol against Alpaca's
`/v2/assets?exchange=NASDAQ` (via the shared `AlpacaAssetLookup` helper) so a
typo or non-Nasdaq symbol fails clearly rather than silently going unwatched.

Open question, not yet decided: should this **replace** the current
all-Nasdaq/liquidity-filtered scan (~921 symbols), or run **alongside/on top
of** it (combined symbol count)? This matters more now than when first
noted, since the active universe is already ~921 symbols, not 159 — a
combined universe would be even closer to (or further past) IBKR's ~100
concurrent market-data-line cap (see the IBKR discussion elsewhere in this
doc) once that integration exists, whereas Alpaca's minute-bar subscriptions
have no such cap today. Needs a decision before building.

### 3.5 Historical backtesting

Running the signal against historical data to validate threshold/lookback
choices before they fire live. Not started; would likely be a separate
offline tool reusing `PriceSpikeRule` and `PriceHistory` directly rather than
part of the running scanner.

## 4. Operational odds and ends (not committed to, just noted)

- **Long-running packaging** — currently run via `mvn spring-boot:run` in a
  terminal. If this is meant to run unattended for hours/days, consider a
  packaged jar (`mvn package` → `java -jar target/scanner-0.1.0-SNAPSHOT.jar`)
  kept alive some way (a dedicated terminal, a scheduled task, or a Windows
  service wrapper) rather than a Maven process.
- **Version control** — `git init` was explicitly skipped at your request;
  still pending whenever you want it.
- **CI** — not needed for a personal project at this stage; noted only in
  case that changes.
