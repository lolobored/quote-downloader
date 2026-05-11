package org.lolobored.quotedownloader.service.scrapers.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.scrapers.YahooFinanceService;
import org.openqa.selenium.By;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class YahooFinanceServiceImpl implements YahooFinanceService {

  private static final Logger logger = LoggerFactory.getLogger(YahooFinanceServiceImpl.class);

  private static final String PRICE_SELECTOR = "[data-testid='qsp-price']";
  private static final Duration PRICE_TIMEOUT = Duration.ofSeconds(30);

  @Override
  public boolean requiresWebDriver() {
    return false;
  }

  protected WebDriver createDriver() {
    ChromeOptions opts = new ChromeOptions();
    opts.addArguments(
        "--headless=new",
        "--window-size=1280,900",
        "--no-first-run",
        "--disable-popup-blocking",
        "--disable-features=FedCm,FedCmWithoutWellKnownEnforcement",
        "--incognito");
    opts.setPageLoadStrategy(PageLoadStrategy.NONE);
    return new ChromeDriver(opts);
  }

  @Override
  public List<Quote> fetchQuotes(WebDriver ignored, Provider provider) throws Exception {
    WebDriver driver = createDriver();
    try {
      List<Quote> quotes = new ArrayList<>();
      for (Fund fund : provider.getFunds()) {
        String symbol = fund.getFundId();
        logger.info("Fetching Yahoo Finance quote for [{}] ({})", fund.getFundName(), symbol);

        driver.get("https://finance.yahoo.com/quote/" + symbol + "/");

        WebElement priceEl =
            new WebDriverWait(driver, PRICE_TIMEOUT)
                .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(PRICE_SELECTOR)));

        BigDecimal price = new BigDecimal(priceEl.getText().replace(",", ""));
        String currency = fund.getCurrency() != null ? fund.getCurrency() : "USD";

        logger.info("  {} {} {}", symbol, price.toPlainString(), currency);
        Quote quote = new Quote(fund.getFundName(), symbol, price, currency, LocalDate.now());
        quote.setProviderName(provider.getName());
        quotes.add(quote);
      }
      return quotes;
    } finally {
      driver.quit();
    }
  }
}
