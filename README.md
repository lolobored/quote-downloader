# quote-downloader

A headless browser scraper that fetches daily investment fund prices from multiple financial portals and writes them to a single Excel workbook. Built for personal use with [Banktivity](https://www.banktivity.com/).

## Supported providers

| Provider | Auth | Notes |
|---|---|---|
| **Yahoo Finance** | None | Stock tickers (e.g. D05.SI, O39.SI) |
| **OCBC Invest** | Username + password + push notification | Wealth portfolio fund prices |
| **Great Eastern** | SSO username + password + SMS OTP | ILP policy fund prices |
| **Royal London** | Username + password + SMS OTP | Pension fund prices |

Credentials are fetched from [Bitwarden](https://bitwarden.com/) at runtime — nothing is stored in the config file.

## Prerequisites

- Java 25 (via [SDKMAN](https://sdkman.io/): `sdk install java 25-amzn`)
- [Bitwarden CLI](https://bitwarden.com/help/cli/) (`brew install bitwarden-cli`)
- [GitHub CLI](https://cli.github.com/) for auto-update (`brew install gh`)
- Google Chrome (for headless scraping)

## Configuration

Create a `quotes.json` file (not committed — keep it private). See `quotes.json.example` for the structure:

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
      { "portfolioName": "0123456789", "fundId": "GreatLink Multi-Sector Income Fund", "fundName": "GreatLink Multi-Sector Income Fund", "currency": "SGD" }
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

### From the terminal (interactive OTP)

Place the jar and `quotes.json` in `~/.perso/banktivity/` alongside the `download-quotes` script, then:

```bash
download-quotes
```

SMS OTP codes are prompted interactively on the terminal. The output Excel file is written to `~/Downloads/quotes.xlsx`.

### Options

```
--output=<dir>      Output directory (default: ~/Downloads)
--headed            Show browser windows (useful for debugging)
--browser=firefox   Use Firefox instead of Chrome
--otp-file          Read OTP codes from files instead of stdin:
                      Great Eastern → /tmp/ge-otp.txt
                      Royal London  → /tmp/rl-otp.txt
--sequential        Run providers one at a time instead of in parallel
```

## Excel output

Each run creates a new sheet named with today's date (`yyyy-MM-dd`) as the first tab:
- **Provider** column — merged across all rows for the same source
- **Price** — highlighted green if higher than the previous sheet, red if lower
- Up to 5 sheets are kept; older sheets are removed automatically

## Building from source

```bash
./gradlew bootJar
```

The jar is produced at `build/libs/quote-downloader-*.jar`.
