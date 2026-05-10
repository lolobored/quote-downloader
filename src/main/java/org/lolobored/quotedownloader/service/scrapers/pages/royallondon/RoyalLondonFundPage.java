package org.lolobored.quotedownloader.service.scrapers.pages.royallondon;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoyalLondonFundPage {

  private static final Logger logger = LoggerFactory.getLogger(RoyalLondonFundPage.class);

  // Stable ID on the Investments tab <td> — inner text is "Investments" but CSS renders uppercase
  private static final By INVESTMENTS_TAB = By.id("MemDetsTabHeader5");

  private static final BigDecimal PENCE_TO_GBP = BigDecimal.valueOf(100);

  // Extract fund rows from the Investments table.
  // Row layout verified against the live portal (May 2026):
  //   [0] Fund manager | [1] Fund name | [2] info-icon | [3] Units | [4] Price(p) | [5] Value(£) |
  // [6] Protected rights(£)
  // Also extracts the "Value as at DD/MM/YYYY" date cell to derive the price date.
  private static final String EXTRACT_INVESTMENTS_JS =
      "var result = { funds: [], currency: 'GBP' };"
          + "var rows = document.querySelectorAll('table tr');"
          + "for (var i = 0; i < rows.length; i++) {"
          + "  var cells = rows[i].querySelectorAll('td');"
          + "  if (cells.length >= 6) {"
          + "    var name = cells[1].textContent.trim();"
          + "    var price = cells[4].textContent.trim().replace(/,/g, '');"
          + "    if (name && /^[\\d.]+$/.test(price)) {"
          + "      result.funds.push({ name: name, price: price });"
          + "    }"
          + "  }"
          + "}"
          + "return result;";

  private final WebDriver driver;
  private final WebDriverWait wait;

  public RoyalLondonFundPage(WebDriver driver, int waitSeconds) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
  }

  public List<Quote> getQuotesForPolicy(String policyNumber, List<Fund> funds)
      throws InterruptedException {
    navigateToInvestmentsTab(policyNumber);
    return extractFundQuotes(funds);
  }

  private void navigateToInvestmentsTab(String policyNumber) throws InterruptedException {
    logger.info("Navigating to Investments tab for policy [{}]...", policyNumber);

    driver.switchTo().defaultContent();
    driver.switchTo().frame("frameBody");

    By policyLink = By.xpath("//a[normalize-space(text())='" + policyNumber + "']");
    wait.until(ExpectedConditions.elementToBeClickable(policyLink)).click();

    // Re-acquire frame reference after frameBody navigates to meminf.asp
    driver.switchTo().defaultContent();
    driver.switchTo().frame("frameBody");

    WebElement investmentsTab =
        wait.until(ExpectedConditions.presenceOfElementLocated(INVESTMENTS_TAB));
    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", investmentsTab);
    logger.info("Investments tab clicked, polling for fund data...");

    // Poll directly for the fund rows via the same JS that will extract them — most reliable
    // since the "Please wait" cell persists in the DOM even after data loads.
    wait.until(
        d -> {
          @SuppressWarnings("unchecked")
          Map<String, Object> result =
              (Map<String, Object>) ((JavascriptExecutor) d).executeScript(EXTRACT_INVESTMENTS_JS);
          if (result == null) return false;
          List<?> funds = (List<?>) result.get("funds");
          return funds != null && !funds.isEmpty();
        });
    logger.info("Investments data ready");
  }

  @SuppressWarnings("unchecked")
  private List<Quote> extractFundQuotes(List<Fund> funds) {
    Map<String, Fund> remaining = new LinkedHashMap<>();
    for (Fund f : funds) remaining.put(f.getFundId(), f);
    List<Quote> results = new ArrayList<>();

    Map<String, Object> extracted =
        (Map<String, Object>) ((JavascriptExecutor) driver).executeScript(EXTRACT_INVESTMENTS_JS);

    driver.switchTo().defaultContent();

    if (extracted == null) {
      throw new RuntimeException(
          "No data extracted from Investments tab — page structure may have changed.");
    }

    List<Map<String, Object>> rows = (List<Map<String, Object>>) extracted.get("funds");
    if (rows == null || rows.isEmpty()) {
      throw new RuntimeException("No fund rows found on Investments tab.");
    }

    logger.info("Found {} fund rows on Investments tab", rows.size());

    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String priceStr = (String) row.get("price");

      for (String fundId : new ArrayList<>(remaining.keySet())) {
        if (name != null && name.contains(fundId)) {
          Fund fund = remaining.get(fundId);
          // Prices on this portal are in pence — convert to GBP
          BigDecimal priceInPence = new BigDecimal(priceStr);
          BigDecimal priceGbp = priceInPence.divide(PENCE_TO_GBP, 6, RoundingMode.HALF_UP);
          String currency = "GBP";
          logger.info("  {} £{} {}", fundId, priceGbp.toPlainString(), currency);
          results.add(new Quote(fund.getFundName(), fundId, priceGbp, currency, LocalDate.now()));
          remaining.remove(fundId);
          break;
        }
      }
    }

    if (!remaining.isEmpty()) {
      throw new RuntimeException(
          "Could not find funds on Investments tab: "
              + remaining.keySet()
              + ". Verify fundId is a substring of the fund name shown on the Royal London portal.");
    }
    return results;
  }
}
