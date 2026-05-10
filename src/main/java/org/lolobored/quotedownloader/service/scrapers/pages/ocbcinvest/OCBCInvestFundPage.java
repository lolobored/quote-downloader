package org.lolobored.quotedownloader.service.scrapers.pages.ocbcinvest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OCBCInvestFundPage {

  private static final Logger logger = LoggerFactory.getLogger(OCBCInvestFundPage.class);

  // MUI paper block that wraps all investment portfolio rows
  private static final By PORTFOLIO_BLOCK = By.className("mfe-cfo-portfolio--MuiPaper-root");
  // Each hoverable row on the dashboard has class "cursor"
  private static final By PORTFOLIO_ROW = By.className("cursor");
  // Action links that appear after hovering over a row (Details / Transactions, etc.)
  private static final By PORTFOLIO_ACTION_LINKS = By.className("first");
  // Sentinel that the portfolio holdings page has finished loading
  private static final By FUND_PAGE_ANCHOR = By.xpath("//p[contains(text(), 'Last price')]");

  private static final String DETAILS_TRANSACTIONS_LABEL = "Details / Transactions";

  // Mirroring the bank-statements project timings that are known to work on this portal
  private static final Duration HOVER_PAUSE = Duration.ofMillis(2000);
  private static final Duration NAV_PAUSE = Duration.ofMillis(1500);
  private static final Duration ELEMENT_WAIT = Duration.ofSeconds(30);
  private static final Duration RETRY_CHECK_WAIT = Duration.ofSeconds(5);
  private static final int MAX_HOVER_ATTEMPTS = 3;

  // Extracts fund name / last price / currency from every fund card on the holdings page.
  // DOM path verified against the live OCBC portal (May 2026):
  //   <p> "Last price (SGD)"  ← label
  //     parent div            ← price cell
  //       h3                  ← price value
  //   price-cell.parent       ← detail row
  //   detail-row.parent       ← fund card
  //     first h3              ← fund name
  private static final String EXTRACT_FUNDS_JS =
      "var r = [];"
          + "var ps = document.querySelectorAll('p');"
          + "for (var i = 0; i < ps.length; i++) {"
          + "  var p = ps[i];"
          + "  if (p.textContent.indexOf('Last price') < 0) continue;"
          + "  var pp = p.parentElement;"
          + "  var priceH = pp ? pp.querySelector('h3') : null;"
          + "  var row = pp ? pp.parentElement : null;"
          + "  var card = row ? row.parentElement : null;"
          + "  var nameH = card ? card.querySelector('h3') : null;"
          + "  var cm = p.textContent.match(/\\(([A-Z]+)\\)/);"
          + "  if (nameH && priceH && cm)"
          + "    r.push({ n: nameH.textContent.trim(), p: priceH.textContent.trim(), c: cm[1] });"
          + "}"
          + "return r;";

  private final WebDriver driver;
  private final WebDriverWait wait;

  public OCBCInvestFundPage(WebDriver driver, int waitSeconds) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
  }

  public List<Quote> getQuotesForPortfolio(String portfolioName, List<Fund> funds)
      throws InterruptedException {
    navigateToPortfolioDetails(portfolioName);
    return extractAllFundQuotes(funds);
  }

  private void navigateToPortfolioDetails(String portfolioName) throws InterruptedException {
    logger.info("Looking for portfolio [{}] on dashboard...", portfolioName);

    WebDriverWait shortWait = new WebDriverWait(driver, ELEMENT_WAIT);

    // Wait for the portfolio block to load (MFE renders asynchronously)
    shortWait.until(ExpectedConditions.visibilityOfElementLocated(PORTFOLIO_BLOCK));

    // Find the cursor row whose text matches the portfolio name (normalize whitespace)
    String normalizedTarget = portfolioName.trim().replaceAll("\\s+", " ");
    List<WebElement> rows = driver.findElements(PORTFOLIO_ROW);
    WebElement portfolioRow = null;
    for (WebElement row : rows) {
      if (normalizedTarget.equals(row.getText().trim().replaceAll("\\s+", " "))) {
        portfolioRow = row;
        break;
      }
    }
    if (portfolioRow == null) {
      throw new RuntimeException(
          "Could not find portfolio ["
              + portfolioName
              + "] on dashboard. Verify portfolioName matches exactly what the OCBC portal shows.");
    }

    ((JavascriptExecutor) driver)
        .executeScript("arguments[0].scrollIntoView({block:'center'});", portfolioRow);
    Thread.sleep(500);

    // Hover to reveal the "Details / Transactions" tooltip — retry up to 3 times (same logic as
    // bank-statements)
    WebDriverWait retryWait = new WebDriverWait(driver, RETRY_CHECK_WAIT);
    for (int attempt = 0; attempt < MAX_HOVER_ATTEMPTS; attempt++) {
      new Actions(driver).moveToElement(portfolioRow).pause(HOVER_PAUSE).build().perform();
      try {
        retryWait.until(ExpectedConditions.visibilityOfElementLocated(PORTFOLIO_ACTION_LINKS));
        List<WebElement> links = driver.findElements(PORTFOLIO_ACTION_LINKS);
        for (WebElement link : links) {
          if (DETAILS_TRANSACTIONS_LABEL.equals(link.getText())) {
            link.click();
            shortWait.until(ExpectedConditions.presenceOfElementLocated(FUND_PAGE_ANCHOR));
            Thread.sleep(NAV_PAUSE.toMillis());
            logger.info("Portfolio holdings page loaded");
            return;
          }
        }
      } catch (TimeoutException ignored) {
        logger.debug("Action links not visible after hover (attempt {}), retrying", attempt + 1);
      }
    }
    throw new RuntimeException(
        "Could not open '"
            + DETAILS_TRANSACTIONS_LABEL
            + "' for portfolio ["
            + portfolioName
            + "] after "
            + MAX_HOVER_ATTEMPTS
            + " hover attempts.");
  }

  @SuppressWarnings("unchecked")
  private List<Quote> extractAllFundQuotes(List<Fund> funds) {
    Map<String, Fund> remaining = new LinkedHashMap<>();
    for (Fund f : funds) remaining.put(f.getFundId(), f);
    List<Quote> results = new ArrayList<>();

    List<Map<String, Object>> allFunds =
        (List<Map<String, Object>>) ((JavascriptExecutor) driver).executeScript(EXTRACT_FUNDS_JS);

    if (allFunds == null || allFunds.isEmpty()) {
      throw new RuntimeException(
          "No fund data extracted from portfolio holdings page — page structure may have changed.");
    }

    logger.info("Found {} funds on portfolio page", allFunds.size());

    for (Map<String, Object> entry : allFunds) {
      String fundName = (String) entry.get("n");
      String priceStr = (String) entry.get("p");
      String currency = (String) entry.get("c");

      for (String fundId : new ArrayList<>(remaining.keySet())) {
        if (fundName != null && fundName.contains(fundId)) {
          Fund fund = remaining.get(fundId);
          BigDecimal price = new BigDecimal(priceStr.replaceAll(",", ""));
          String resolvedCurrency = fund.getCurrency() != null ? fund.getCurrency() : currency;
          logger.info("  {} {} {}", fundId, price.toPlainString(), resolvedCurrency);
          results.add(
              new Quote(fund.getFundName(), fundId, price, resolvedCurrency, LocalDate.now()));
          remaining.remove(fundId);
          break;
        }
      }
    }

    if (!remaining.isEmpty()) {
      throw new RuntimeException(
          "Could not find funds on portfolio holdings page: "
              + remaining.keySet()
              + ". Verify fundId is a substring of the fund name shown on the OCBC page.");
    }
    return results;
  }
}
