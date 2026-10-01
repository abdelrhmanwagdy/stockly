

---
updatedAt: 2026-08-19T16:26:50.000Z
---

Fetch the complete documentation index at: https://docs.alpaca.markets/us/llms.txt. Use this file to discover all available pages before exploring further. Append .md to any documentation page URL to get its markdown version.

# Getting Started with Market Data API

This is a quick guide on how to start consuming market data via APIs. Starting from beginning to end, this section outlines how to install Alpaca’s software development kit (SDK), create a free alpaca account, locate your API keys, and how to request both historical and real-time data.

# Installing Alpaca’s Client SDK

In this guide, we’ll be making use of the SDKs provided by Alpaca. Alpaca maintains SDKs in four languages: Python, JavaScript, C#, and Go. Follow the steps in the installation guide below to install the SDK of your choice before proceeding to the next section.

```python
pip install alpaca-py
```
```go
go get -u github.com/alpacahq/alpaca-trade-api-go/v3/alpaca
```
```javascript
npm install --save @alpacahq/alpaca-trade-api
```
```csharp
dotnet add package Alpaca.Markets
```

# Generate API Keys

Go to the [Alpaca dashboard](https://app.alpaca.markets/brokerage/dashboard/overview) and find the **API Keys** section on the right sidebar. Click on Generate New Keys and save the generated API credentials. If you have previously generated keys there and you lost the secret, you can also regenerate them here.

# How to Request Market Data Through the SDK

With the SDK installed and our API keys ready, you can start requesting market data. Alpaca offers many options for both historical and real-time data, so to keep this guide succinct, these examples are on obtaining historical and real-time bar data. Information on what other data is available can be found in the Market Data API reference.

To start using the SDK for historical data, import the SDK and instantiate the crypto historical data client. Then define the parameters and send the request, such as querying for historical daily bar data of Bitcoin in the first week of September 2022.

```python
from alpaca.data.historical import CryptoHistoricalDataClient
from alpaca.data.requests import CryptoBarsRequest
from alpaca.data.timeframe import TimeFrame

# No keys required for crypto data
client = CryptoHistoricalDataClient()

# Creating request object
request_params = CryptoBarsRequest(
  symbol_or_symbols=["BTC/USD"],
  timeframe=TimeFrame.Day,
  start=datetime(2022, 9, 1),
  end=datetime(2022, 9, 7)
)

# Retrieve daily bars for Bitcoin in a DataFrame and printing it
btc_bars = client.get_crypto_bars(request_params)

# Convert to dataframe
btc_bars.df

"""
Result

symbol  timestamp                  open      high      low       close      volume       trade_count          vwap
BTC/USD 2022-09-01 05:00:00+00:00  20055.79  20292.00  19564.86  20156.76   7141.975485     110122.0  19934.167845
        2022-09-02 05:00:00+00:00  20156.76  20444.00  19757.72  19919.47   7165.911879      96231.0  20075.200868
        2022-09-03 05:00:00+00:00  19924.83  19968.20  19658.04  19806.11   2677.652012      51551.0  19800.185480
        2022-09-04 05:00:00+00:00  19805.39  20058.00  19587.86  19888.67   4325.678790      62082.0  19834.451414
        2022-09-05 05:00:00+00:00  19888.67  20180.50  19635.96  19760.56   6274.552824      84784.0  19812.095982
        2022-09-06 05:00:00+00:00  19761.39  20026.91  18534.06  18724.59  11217.789784     128106.0  19266.835520
"""
```
```go
package main

import "github.com/alpacahq/alpaca-trade-api-go/v3/marketdata"

func main() {
	// No keys required for crypto data
	client := marketdata.NewClient(marketdata.ClientOpts{})

	request := marketdata.GetCryptoBarsRequest{
		TimeFrame: marketdata.OneDay,
		Start:     time.Date(2022, 9, 1, 0, 0, 0, 0, time.UTC),
		End:       time.Date(2022, 9, 7, 0, 0, 0, 0, time.UTC),
	}

	bars, err := client.GetCryptoBars("BTC/USD", request)
	if err != nil {
		panic(err)
	}
	for _, bar := range bars {
		fmt.Printf("%+v\n", bar)
	}
}

/*
Result

{Timestamp:2022-09-01 05:00:00 +0000 UTC Open:20055.79 High:20292 Low:19564.86 Close:20156.76 Volume:7141.975485 TradeCount:110122 VWAP:19934.1678446199}
{Timestamp:2022-09-02 05:00:00 +0000 UTC Open:20156.76 High:20444 Low:19757.72 Close:19919.47 Volume:7165.911879 TradeCount:96231 VWAP:20075.2008677126}
{Timestamp:2022-09-03 05:00:00 +0000 UTC Open:19924.83 High:19968.2 Low:19658.04 Close:19806.11 Volume:2677.652012 TradeCount:51551 VWAP:19800.1854803241}
{Timestamp:2022-09-04 05:00:00 +0000 UTC Open:19805.39 High:20058 Low:19587.86 Close:19888.67 Volume:4325.67879 TradeCount:62082 VWAP:19834.4514137038}
{Timestamp:2022-09-05 05:00:00 +0000 UTC Open:19888.67 High:20180.5 Low:19635.96 Close:19760.56 Volume:6274.552824 TradeCount:84784 VWAP:19812.0959815687}
{Timestamp:2022-09-06 05:00:00 +0000 UTC Open:19761.39 High:20026.91 Low:18534.06 Close:18724.59 Volume:11217.789784 TradeCount:128106 VWAP:19266.8355201911}
*/
```
```javascript JavaScript
import Alpaca from "@alpacahq/alpaca-trade-api";

(async () => {
  // Alpaca() requires the API key and sectret to be set, even for crypto
  const alpaca = new Alpaca({
    keyId: "YOUR_API_KEY",
    secretKey: "YOUR_API_SECRET",
  });

  const options = {
    start: "2022-09-01",
    end: "2022-09-07",
    timeframe: alpaca.newTimeframe(1, alpaca.timeframeUnit.DAY),
  };

  const bars = await alpaca.getCryptoBars(["BTC/USD"], options);
  console.table(bars.get("BTC/USD"));
})();

/*
Result

┌─────────┬──────────┬──────────┬──────────┬────────────┬──────────┬────────────────────────┬──────────────┬──────────────────┐
│ (index) │ Close    │ High     │ Low      │ TradeCount │ Open     │ Timestamp              │ Volume       │ VWAP             │
├─────────┼──────────┼──────────┼──────────┼────────────┼──────────┼────────────────────────┼──────────────┼──────────────────┤
│ 0       │ 20156.76 │ 20292    │ 19564.86 │ 110122     │ 20055.79 │ '2022-09-01T05:00:00Z' │ 7141.975485  │ 19934.1678446199 │
│ 1       │ 19919.47 │ 20444    │ 19757.72 │ 96231      │ 20156.76 │ '2022-09-02T05:00:00Z' │ 7165.911879  │ 20075.2008677126 │
│ 2       │ 19806.11 │ 19968.2  │ 19658.04 │ 51551      │ 19924.83 │ '2022-09-03T05:00:00Z' │ 2677.652012  │ 19800.1854803241 │
│ 3       │ 19888.67 │ 20058    │ 19587.86 │ 62082      │ 19805.39 │ '2022-09-04T05:00:00Z' │ 4325.67879   │ 19834.4514137038 │
│ 4       │ 19760.56 │ 20180.5  │ 19635.96 │ 84784      │ 19888.67 │ '2022-09-05T05:00:00Z' │ 6274.552824  │ 19812.0959815687 │
│ 5       │ 18724.59 │ 20026.91 │ 18534.06 │ 128106     │ 19761.39 │ '2022-09-06T05:00:00Z' │ 11217.789784 │ 19266.8355201911 │
└─────────┴──────────┴──────────┴──────────┴────────────┴──────────┴────────────────────────┴──────────────┴──────────────────┘
*/
```
```csharp
using Alpaca.Markets;
using System;

var client = Environments.Paper.GetAlpacaCryptoDataClient(
    new SecretKey("YOUR_API_KEY", "YOUR_API_SECRET"));

var request = new HistoricalCryptoBarsRequest(
    "BTC/USD",
    new DateTime(2022, 9, 1, 0, 0, 0, DateTimeKind.Utc),
    new DateTime(2022, 9, 7, 0, 0, 0, DateTimeKind.Utc),
    BarTimeFrame.Day);

var bars = await client.ListHistoricalBarsAsync(request);

foreach (var bar in bars.Items)
{
    Console.WriteLine($"{bar.Symbol} {bar.TimeUtc:O} O:{bar.Open} H:{bar.High} L:{bar.Low} C:{bar.Close} V:{bar.Volume} Trades:{bar.TradeCount} VWAP:{bar.Vwap}");
}

/*
Result

BTC/USD 2022-09-01T00:00:00.0000000Z O:20051.81 H:20205.83 L:19564.86 C:20132.97 V:7529.674053 Trades:114052 VWAP:19934.7015559282
BTC/USD 2022-09-02T00:00:00.0000000Z O:20132.5 H:20444 L:19757.72 C:19954.16 V:7392.679014 Trades:98745 VWAP:20095.8994413784
BTC/USD 2022-09-03T00:00:00.0000000Z O:19950.63 H:20054.69 L:19658.04 C:19832.06 V:3077.135497 Trades:52729 VWAP:19839.4065626337
BTC/USD 2022-09-04T00:00:00.0000000Z O:19834.87 H:20030.89 L:19587.86 C:20002.38 V:3712.178165 Trades:60722 VWAP:19813.5375316233
BTC/USD 2022-09-05T00:00:00.0000000Z O:19998.77 H:20058 L:19635.96 C:19795.12 V:4817.489036 Trades:66396 VWAP:19801.5785916073
BTC/USD 2022-09-06T00:00:00.0000000Z O:19795.12 H:20180.5 L:18668.9 C:18790.39 V:11753.830278 Trades:139147 VWAP:19480.9863696975
BTC/USD 2022-09-07T00:00:00.0000000Z O:18789.4 H:19462.02 L:18534.06 C:19290.53 V:8092.183326 Trades:89704 VWAP:18952.4811318379
*/
```

# Request ID

All market data API endpoint provides a unique identifier of the API call in the response header with `X-Request-ID` key, the Request ID helps us to identify the call chain in our system.

Make sure you provide the Request ID in all support requests that you created, it could help us to solve the issue as soon as possible. Request ID can't be queried in other endpoints, that is why we suggest to persist the recent Request IDs.

```shell
$ curl -v https://data.alpaca.markets/v2/stocks/bars
...
> GET /v2/stocks/bars HTTP/1.1
> Host: data.alpaca.markets
> User-Agent: curl/7.88.1
> Accept: */*
>
< HTTP/1.1 403 Forbidden
< Date: Fri, 25 Aug 2023 09:37:03 GMT
< Content-Type: application/json
< Content-Length: 26
< Connection: keep-alive
< X-Request-ID: 0d29ba8d9a51ee0eb4e7bbaa9acff223
<
...
```