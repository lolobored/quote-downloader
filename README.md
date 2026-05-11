# quote-downloader

A headless browser scraper that fetches daily investment fund prices from multiple financial portals and writes them to a single Excel workbook. Built for personal use with [Banktivity](https://www.banktivity.com/).

## Supported providers

| Provider | Auth | Method |
|---|---|---|
| **Yahoo Finance** | None | Headless Chrome — price element detected before full page load (~2s per ticker) |
| **OCBC Invest** | Username + password + push notification | Headless Chrome |
| **Great Eastern** | SSO username + password + SMS OTP | Headless Chrome |
| **Royal London** | Username + password + SMS OTP | Headless Chrome |

Credentials are fetched from [Bitwarden](https://bitwarden.com/) at runtime — nothing is stored in the config file.

## Prerequisites

- Java 17+ (via [SDKMAN](https://sdkman.io/): `sdk install java 17.0.15-amzn`)
- [Bitwarden CLI](https://bitwarden.com/help/cli/) (`brew install bitwarden-cli`)
- [GitHub CLI](https://cli.github.com/) for auto-update (`brew install gh`)
- Google Chrome (for headless scraping of browser-based providers)

## Configuration

Create a `quotes.json` file (not committed — keep it private). See `quotes.json.sample` for the full structure:

```json
[
  {
    "name": "yahoo",
    "enabled": true,
    "funds": [
      { "fundId": "D05.SI", "fundName": "DBS Group Holdings", "currency": "SGD" }
    ]
  },
  {
    "name": "great eastern",
    "connectionUrl": "https://uip.greateasternlife.com/econnect-new/#/login",
    "bitwardenItemName": "sso-sgmob.greateasternlife.com",
    "waitTime": 120,
    "enabled": true,
    "funds": [
      {
        "portfolioName": "0123456789",
        "fundId": "GreatLink Multi-Sector Income Fund",
        "fundName": "GreatLink Multi-Sector Income Fund",
        "currency": "SGD"
      }
    ]
  }
]
```

Fields:
- `name` — one of `yahoo`, `ocbc`, `great eastern`, `royal london`
- `bitwardenItemName` — Bitwarden item whose username/password will be used
- `waitTime` — Selenium wait timeout in seconds (default 120; increase for slow portals)
- `portfolioName` — policy or portfolio identifier used to navigate to the right page
- `fundId` — substring of the fund name as shown on the portal (used to match rows)

## Running

Place the jar and `quotes.json` in `~/.perso/banktivity/` alongside the `download-quotes` script, then:

```bash
download-quotes
```

Providers run sequentially. SMS OTP codes are prompted interactively on the terminal when needed. The output Excel file is written to `~/Downloads/quotes.xlsx`.

### Options

```
--output=<dir>      Output directory (default: ~/Downloads)
--headed            Show browser windows (useful for debugging)
--browser=firefox   Use Firefox instead of Chrome
--otp-file          Read OTP codes from files instead of stdin:
                      Great Eastern → /tmp/ge-otp.txt
                      Royal London  → /tmp/rl-otp.txt
```

## Excel output

`quotes.xlsx` contains one sheet per calendar month (`yyyy-MM`), never pruned:

| Date | Provider | Fund Name | Ticker | Price | Currency |
|---|---|---|---|---|---|
| 2026-05-01 | yahoo | DBS Group Holdings | D05.SI | 38.50 | SGD |
| 2026-05-01 | great eastern | GreatLink Multi-Sector Income | GEV001 | 0.8901 | SGD |
| 2026-05-11 | yahoo | DBS Group Holdings | D05.SI | 39.10 | SGD |

- All columns have an **autofilter** so you can slice by provider, fund, or date range directly in Excel
- Running multiple times on the same day **updates** the existing row rather than adding duplicates
- Funds added or removed from the config simply appear or stop appearing in future rows
- The long format is directly usable in pivot tables, charting tools, and data analysis libraries (pandas, R, etc.)

## Building from source

```bash
./gradlew bootJar
```

The jar is produced at `build/libs/quote-downloader-*.jar`.
