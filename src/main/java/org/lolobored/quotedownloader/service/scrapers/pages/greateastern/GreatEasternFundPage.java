package org.lolobored.quotedownloader.service.scrapers.pages.greateastern;

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
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GreatEasternFundPage {

  private static final Logger logger = LoggerFactory.getLogger(GreatEasternFundPage.class);

  // Finds the smallest DOM element whose textContent contains BOTH the policy number AND
  // "View Policy Details" (i.e. the card container), then clicks the cv-footer--view-all
  // span inside it. Class-agnostic — doesn't assume a specific container class name.
  // AngularJS 1.x ng-click handlers accept untrusted JS clicks (confirmed May 2026).
  private static final String CLICK_VIEW_DETAILS_JS =
      "var policyNumber = arguments[0];"
          + "var all = document.querySelectorAll('*');"
          + "var minLen = Infinity; var cardEl = null;"
          + "for (var i = 0; i < all.length; i++) {"
          + "  var t = all[i].textContent;"
          + "  if (t.indexOf(policyNumber) >= 0 && t.indexOf('View Policy Details') >= 0 && t.length < minLen) {"
          + "    minLen = t.length; cardEl = all[i];"
          + "  }"
          + "}"
          + "if (!cardEl) return 'not-found';"
          + "var span = cardEl.querySelector('span.cv-footer--view-all');"
          + "if (span) { span.click(); return 'clicked'; }"
          + "return 'not-found';";

  // Extracts fund name, unit price, and currency from the Fund Details table.
  // Table layout verified against the live GE portal (May 2026):
  //   cells[0] = fund name
  //   cells[1] = asset class  (rows whose cells[1] contains "Total" are skipped)
  //   cells[2] = "DD MMM YYYYprice CURR" — date and price concatenated without separator
  //   cells[3] = value + units
  //   cells[4] = apportionment rate
  // Regex anchors on the date prefix to avoid capturing the year digits as part of the price.
  private static final String EXTRACT_FUNDS_JS =
      "var result = [];"
          + "var rows = document.querySelectorAll('table tr');"
          + "for (var i = 0; i < rows.length; i++) {"
          + "  var cells = rows[i].querySelectorAll('td');"
          + "  if (cells.length < 3) continue;"
          + "  var name = cells[0].textContent.trim();"
          + "  var assetClass = cells[1].textContent.trim();"
          + "  if (!name || assetClass.indexOf('Total') >= 0) continue;"
          + "  var priceCellText = cells[2].textContent.trim();"
          + "  var m = priceCellText.match(/\\d{1,2}\\s+\\w+\\s+\\d{4}([\\d,.]+)\\s+([A-Z]{3})/);"
          + "  if (m) {"
          + "    result.push({ name: name, price: m[1].replace(/,/g, ''), currency: m[2] });"
          + "  }"
          + "}"
          + "return result;";

  private final WebDriver driver;
  private final WebDriverWait wait;
  private final String dashboardUrl;

  public GreatEasternFundPage(WebDriver driver, int waitSeconds, String dashboardUrl) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    this.dashboardUrl = dashboardUrl;
  }

  public List<Quote> getQuotesForPolicy(String policyNumber, List<Fund> funds) {
    navigateToPolicyDetails(policyNumber);
    return extractFundQuotes(policyNumber, funds);
  }

  private void navigateToPolicyDetails(String policyNumber) {
    if (!driver.getCurrentUrl().contains("dashboard")) {
      logger.info("Returning to dashboard...");
      driver.navigate().to(dashboardUrl);
    }

    JavascriptExecutor js = (JavascriptExecutor) driver;
    // Wait for the policy number to appear, then wait for the View Policy Details spans —
    // the card header renders before the card body, so we need both in DOM before clicking.
    By policyText = By.xpath("//*[contains(normalize-space(.), '" + policyNumber + "')]");
    wait.until(ExpectedConditions.presenceOfElementLocated(policyText));
    wait.until(
        ExpectedConditions.presenceOfElementLocated(By.cssSelector("span.cv-footer--view-all")));

    logger.info("Clicking 'View Policy Details' for policy [{}]...", policyNumber);
    String result = (String) js.executeScript(CLICK_VIEW_DETAILS_JS, policyNumber);
    if ("not-found".equals(result)) {
      throw new RuntimeException(
          "Could not find 'View Policy Details' for policy ["
              + policyNumber
              + "]. Verify the policy number matches what is shown on the Great Eastern dashboard.");
    }

    wait.until(ExpectedConditions.urlContains("policy-details-in"));
    logger.info("Policy details page loaded for policy [{}]", policyNumber);
  }

  @SuppressWarnings("unchecked")
  private List<Quote> extractFundQuotes(String policyNumber, List<Fund> funds) {
    Map<String, Fund> remaining = new LinkedHashMap<>();
    for (Fund f : funds) remaining.put(f.getFundId(), f);
    List<Quote> results = new ArrayList<>();

    // Fund data is loaded from API after Angular routing — wait for a table cell containing
    // an asset class value before extracting (page shows spinner + "No records found" until ready)
    wait.until(
        ExpectedConditions.presenceOfElementLocated(
            By.xpath(
                "//td[contains(normalize-space(.),'BONDS') or contains(normalize-space(.),'EQUITIES')"
                    + " or contains(normalize-space(.),'MIXED ASSETS') or contains(normalize-space(.),'MONEY MARKET')]")));

    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) ((JavascriptExecutor) driver).executeScript(EXTRACT_FUNDS_JS);

    if (rows == null || rows.isEmpty()) {
      throw new RuntimeException(
          "No fund rows extracted from policy ["
              + policyNumber
              + "] — page structure may have changed.");
    }

    logger.info("Found {} fund rows on policy [{}] details page", rows.size(), policyNumber);

    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String priceStr = (String) row.get("price");
      String currency = (String) row.get("currency");

      for (String fundId : new ArrayList<>(remaining.keySet())) {
        if (name != null && name.contains(fundId)) {
          Fund fund = remaining.get(fundId);
          BigDecimal price = new BigDecimal(priceStr);
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
          "Could not find funds on policy ["
              + policyNumber
              + "]: "
              + remaining.keySet()
              + ". Verify fundId is a substring of the fund name shown on the Great Eastern portal.");
    }
    return results;
  }
}
