# Stockly Scanner — External APIs

Stockly talks to two outside services. This document explains what each one
is, why it was chosen, exactly what data it hands over, and — since this came
up in conversation — clears up how often the app actually talks to them
(it's less often than you'd think).

## Quick reference

| Service | Used for | How | API key? | Rate limit that matters here |
|---|---|---|---|---|
| **Alpaca Markets (Market Data API)** | Live stock prices | One persistent WebSocket connection | Yes (free) | 1 concurrent connection; no cap on minute-bar subscriptions |
| **Alpaca Markets (Trading API — assets)** | Getting all Nasdaq-listed symbols | One REST call, once at startup | Yes (same keys) | Negligible — a single occasional call |
| **Alpaca Markets (Trading API — snapshots)** | Liquidity-filtering the universe | ~56 batched REST calls, once at startup | Yes (same keys) | Negligible — one-time, not per-minute |
| **Wikipedia** | S&P 500 ticker list (smaller, older scope, kept as a reference option) | One plain HTTP page fetch, once at startup | No | None documented for a single occasional fetch |

Financial Modeling Prep (FMP) was the *original* plan for the ticker list but
turned out not to work on a free plan — see Section 4 for what happened and
why it's no longer used.

**Current active universe (as of 2026-09-15): all liquid Nasdaq stocks**,
not the S&P 500 — see Section 3. The Wikipedia/S&P-500 path (Section 2) is
kept as a smaller, non-`@Primary` alternative.

## 1. Alpaca Markets — Market Data API

### What it is

Alpaca is a US brokerage that also sells market data access. Stockly mainly
uses its **Market Data API** (the real-time streaming side of it) for
prices, plus one small read-only piece of its **Trading API** (asset
reference data, Section 3) to look up which exchange each symbol is listed
on — never anything that places, modifies, or reads orders/positions, since
Stockly never trades.

### What it provides

Real-time (not delayed) price data for US stocks, sourced from the **IEX
exchange feed**, delivered over a WebSocket connection that pushes updates as
they happen. On the free "Basic" plan this is genuinely free forever, no
credit card required — you just need a (free) Alpaca account, which
automatically comes with a paper-trading account and its own API keys.

Specifically, Stockly subscribes to **1-minute bars** per symbol: for each
symbol, once a minute, Alpaca sends a message with that minute's open, high,
low, close, and volume. Stockly uses the **close** price as that symbol's
"current price" for the signal calculation.

### Why we use it

The design doc's actual end goal is Interactive Brokers (IBKR) — a full
brokerage API with deep market access. The problem: real-time IBKR market
data requires a **paid** per-exchange subscription, which would block
building and proving out the scanner before spending any money.

Alpaca was chosen as a free stand-in specifically because:
- Its free plan gives genuinely **real-time** data (not 15-20 minute delayed
  data, which would defeat the point of a price-spike scanner).
- It requires no payment info to sign up.
- Its **minute-bar** subscriptions have no per-symbol cap on the free plan
  (trade/quote subscriptions do — capped at 30 symbols — but minute bars
  don't), which happens to line up exactly with the scanner's 1-minute
  cadence and lets it cover the full ~500-symbol S&P 500 universe for free.

`MarketDataClient` is written as an interface specifically so a real
`IbkrMarketDataClient` can be added later as a straight swap, once IBKR
market data is worth paying for — no changes to the scanning logic itself.

### How Stockly uses it — connection model

This is the part that caused the "how many requests" confusion, so it's
worth being explicit: **Stockly does not poll Alpaca.** It does not make a
fresh HTTP request per symbol per minute. Instead:

1. Once at startup, `AlpacaMarketDataClient` opens **one** WebSocket
   connection to `wss://stream.data.alpaca.markets/v2/iex`.
2. It authenticates once (sends its API key/secret once over that same
   connection).
3. It sends **one** subscribe message listing all ~500 symbols (split into a
   couple of batches of 100, still just a handful of messages, not 500).
4. From then on, Alpaca **pushes** a bar message down that same open
   connection every minute, for every symbol, without Stockly asking again.

So the connection is opened once and stays open indefinitely — there is no
per-minute or per-symbol "request" being made at all, and nothing about
`poll-interval-seconds` (which only controls how often Stockly re-evaluates
data it already has in memory) causes any network call.

If the connection drops for any reason, `AlpacaMarketDataClient` reconnects
automatically with increasing backoff (1s, 2s, 4s, 8s, 16s, capped at 30s)
and re-subscribes — this is the only scenario that generates more than a
handful of connection attempts, and it's inherently rate-limited by the
backoff itself.

### Free tier limits, and how Stockly stays within them

| Limit | Value | How Stockly stays within it |
|---|---|---|
| Concurrent WebSocket connections | 1 | Only ever opens one at a time |
| Trade/quote channel subscriptions | 30 symbols | Not used — Stockly subscribes to minute bars instead |
| Minute-bar channel subscriptions | No documented cap | This is what Stockly uses for all ~500 symbols |
| Data feed | IEX only (single exchange) | Accepted tradeoff for v1 — see the functional doc's note on IEX being a slice of total volume |

There is no "requests per day" style quota for this streaming use case at
all — that model applies to Alpaca's REST endpoints. Stockly does make one
such REST call, but only once per startup (the exchange lookup in Section 3),
nowhere near any meaningful rate limit.

### Authentication

Two credentials from your Alpaca account dashboard ("API Keys" section):
`ALPACA_API_KEY_ID` and `ALPACA_SECRET_KEY`, stored in `.env` (gitignored) and
sent once over the WebSocket connection during the auth handshake — never
sent with every message.

## 2. Wikipedia — "List of S&P 500 companies" (smaller, older scope)

> Superseded as the active (`@Primary`) universe by Section 3's all-Nasdaq,
> liquidity-filtered approach — kept as a reference/fallback option via
> `NasdaqOnlyUniverseProvider` and `WikipediaSp500UniverseProvider`, both
> still ordinary beans.

### What it is

The public Wikipedia article at
`https://en.wikipedia.org/wiki/List_of_S%26P_500_companies`, which has
historically been kept up to date by editors whenever S&P actually changes
the index's constituents.

### What it provides

An HTML table (with the id `constituents`) listing every current S&P 500
member, one row per company, with the ticker symbol in the first column.
Stockly parses just that one column.

### Why we use it

This replaced the original plan of using Financial Modeling Prep's API for
this list — see Section 4 for exactly what went wrong there. Wikipedia was
chosen as the replacement because:
- It's free, with no signup and no API key at all.
- It's a single, well-known, stable page structure (the `id="constituents"`
  table is a long-standing convention many tools already rely on for this
  exact purpose).
- The scanner only needs this list **once per startup** — it doesn't need to
  be a paid, SLA-backed data feed for that usage pattern.

`UniverseProvider` is an interface specifically so this was a drop-in swap:
only `WikipediaSp500UniverseProvider` needed writing, nothing else in the
app changed.

### How Stockly uses it

Exactly one plain HTTP GET to that page, made **once, at application
startup**. The HTML is parsed locally (via the `jsoup` library) to pull out
the ticker symbols; the page itself is discarded afterward. No further
requests happen until the app is restarted.

### Risks and limits

- No documented rate limit for a single occasional page fetch like this —
  but this isn't a formal API, so there's no published SLA either. If
  Wikipedia's page structure changes (the table's id, or which column holds
  the symbol), the parser could start returning zero symbols — Stockly fails
  fast in that case (see `docs/FUNCTIONAL_DOCUMENTATION.md` Section 6.1)
  rather than silently scanning an empty or wrong universe.
- A handful of tickers with punctuated share-class suffixes (e.g. `BRK.B`)
  might not exactly match Alpaca's own symbol spelling — noted as an open
  risk in `docs/REMAINING_WORK.md`.

## 3. Universe — all liquid Nasdaq stocks (Alpaca Trading API)

Two Alpaca Trading API endpoints (a different part of Alpaca than the
streaming Market Data API in Section 1), chained together, form the current
active universe.

### 3.1 Getting every Nasdaq-listed symbol — `GET /v2/assets`

**What it provides:** a reference-data endpoint listing every tradable
asset along with metadata including which exchange it's primarily listed
on. Stockly calls it with `exchange=NASDAQ&status=active&asset_class=us_equity`
to get back every symbol Alpaca considers active and Nasdaq-listed — as of
writing, ~5,600 symbols, not limited to the S&P 500.

**Why we use it:** we already have Alpaca credentials, and this is the
natural place to ask "which stocks are Nasdaq-listed" rather than adding a
new external dependency. `AllNasdaqUniverseProvider` (via the shared
`AlpacaAssetLookup` helper, also reused by the smaller `NasdaqOnlyUniverseProvider`
in Section 2) makes **one** REST call to this endpoint, once at startup.

### 3.2 Filtering by liquidity — `GET /v2/stocks/snapshots`

**What it provides:** latest trade, latest quote, latest minute bar, latest
daily bar, and previous daily bar for a batch of symbols in one call
(comma-separated, up to 100 per request in Stockly's implementation).
`LiquidityFilteredUniverseProvider` reads each symbol's `dailyBar.v` (volume),
falling back to `prevDailyBar.v` if the daily bar isn't populated yet (e.g.
before the market opens), and excludes anything below
`stockly.universe.min-daily-volume` (default `50000`).

**Why we use it:** ~5,600 Nasdaq symbols is too broad to alert on reliably —
most of that list is thinly-traded stocks where the free IEX feed (Section 1)
doesn't represent the real price well. Rather than pay for full consolidated
data to fix accuracy for every symbol, this excludes exactly the symbols
where it would matter, for free.

**Critical caveat, confirmed empirically (2026-09-15):** Alpaca's free-plan
volume figures are themselves IEX-only, not real total market volume. A
direct check against known mega-caps confirmed this:

| Symbol | Alpaca's `dailyBar.v` (IEX-only) | Real-world total daily volume (typical) | Ratio |
|---|---|---|---|
| AAPL | 1,335,636 | ~40-100M | ~1-3% |
| MSFT | 830,370 | ~15-25M | ~3-5% |
| NVDA | 2,427,233 | ~150-300M+ | ~1-2% |
| INTC | 2,467,012 | ~30-60M | ~4-8% |

The ratios line up with IEX's known ~2.5% market share. So
`min-daily-volume` is a threshold in **IEX-observed shares**, not real share
counts — changing it based on a real-world "average daily volume" figure you
looked up elsewhere would be miscalibrated by roughly 20-50x. It still works
as a *relative* liquidity ranking (thin stocks show thin IEX volume too,
proportionally), just don't treat the configured number as a literal market
volume figure.

**Result as of writing:** narrows ~5,600 Nasdaq symbols down to ~921.

### 3.3 Why this replaced the smaller Nasdaq-S&P-500 scope

This is a **scope expansion**, decided during conversation, not a technical
requirement: watching "all Nasdaq stocks reliably" was explicitly the goal,
and excluding illiquid names turned out to be the free way to make that
reliable, instead of paying for full consolidated data or limiting to a
smaller index like the S&P 500. Both are implemented as ordinary Spring
beans, and only `LiquidityFilteredUniverseProvider` carries `@Primary` — so
switching back to the smaller S&P-500/Nasdaq scope (Section 2), or to the
full unfiltered S&P 500, is a matter of moving the `@Primary` annotation,
not a redesign.

## 4. Rejected: Financial Modeling Prep (FMP)

Kept here for the record, since it was the original plan.

FMP is a financial data provider with a documented free tier and a specific
`sp500_constituent` endpoint for exactly this use case. It was the original
choice for the universe list. On live testing (2026-09-15), it turned out:

- The legacy `v3/sp500_constituent` endpoint is retired outright: "Legacy
  Endpoint... no longer supported."
- Its documented "stable" replacement, `stable/sp500-constituent`, returned
  "Restricted Endpoint: not available under your current subscription" even
  with a real, freshly-generated free-tier API key.

In other words, this specific data point is no longer available on FMP's
free plan at all, regardless of the general 250-requests/day quota (which
was never actually the blocker — the endpoint itself was). `FmpSp500UniverseProvider`
and its config were deleted rather than kept around unused; `FMP_API_KEY` is
not referenced anywhere in the app anymore.

## 5. Summary: what actually generates a network request, and when

| Event | Which service | How often |
|---|---|---|
| App startup | Alpaca Trading API (all-Nasdaq assets lookup) | Once |
| App startup | Alpaca Trading API (liquidity snapshots) | ~56 batched calls, once |
| App startup | Alpaca Market Data API (connect + auth + subscribe) | Once |
| Every `poll-interval-seconds` (default 60s) | *Neither* — purely in-memory | N/A |
| Alpaca pushing a new minute bar | Alpaca | Continuously, but *pushed*, not requested |
| Connection drop | Alpaca (reconnect) | Only on failure, backed off up to 30s between attempts |

Net effect: the app can be left running for as long as you like — hours,
days, continuously — without approaching any rate limit, because nothing
about its normal operation scales with time the way a polling design would.
