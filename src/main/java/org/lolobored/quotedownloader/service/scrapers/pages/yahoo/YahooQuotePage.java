package org.lolobored.quotedownloader.service.scrapers.pages.yahoo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class YahooQuotePage {

  private static final String BASE_URL = "https://finance.yahoo.com/quote/";

  // Yahoo Finance has changed this selector several times; listed in priority order
  private static final By[] PRICE_SELECTORS = {
    By.cssSelector("fin-streamer[data-field='regularMarketPrice'][data-symbol]"),
    By.cssSelector("[data-testid='qsp-price']"),
    By.cssSelector("fin-streamer[data-field='regularMarketPrice']"),
  };

  private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);

  private final WebDriver driver;
  private final WebDriverWait wait;

  public YahooQuotePage(WebDriver driver, int waitSeconds) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
  }

  public Quote getQuote(Fund fund) {
    driver.get(BASE_URL + fund.getFundId());

    WebElement priceElement = null;
    for (By selector : PRICE_SELECTORS) {
      try {
        priceElement =
            new WebDriverWait(driver, PAGE_LOAD_TIMEOUT)
                .until(ExpectedConditions.visibilityOfElementLocated(selector));
        break;
      } catch (Exception ignored) {
      }
    }

    if (priceElement == null) {
      throw new RuntimeException(
          "Could not find price element for ticker ["
              + fund.getFundId()
              + "] on Yahoo Finance. The page structure may have changed.");
    }

    String rawPrice = priceElement.getText().trim().replaceAll(",", "");
    BigDecimal price = new BigDecimal(rawPrice);

    return new Quote(
        fund.getFundName(), fund.getFundId(), price, fund.getCurrency(), LocalDate.now());
  }
}
