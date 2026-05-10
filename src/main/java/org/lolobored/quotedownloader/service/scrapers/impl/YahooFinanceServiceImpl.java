package org.lolobored.quotedownloader.service.scrapers.impl;

import java.util.ArrayList;
import java.util.List;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.scrapers.YahooFinanceService;
import org.lolobored.quotedownloader.service.scrapers.pages.yahoo.YahooQuotePage;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class YahooFinanceServiceImpl implements YahooFinanceService {

  private static final Logger logger = LoggerFactory.getLogger(YahooFinanceServiceImpl.class);

  @Override
  public List<Quote> fetchQuotes(WebDriver driver, Provider provider) throws Exception {
    List<Quote> quotes = new ArrayList<>();
    YahooQuotePage page = new YahooQuotePage(driver, provider.getWaitTime());

    for (Fund fund : provider.getFunds()) {
      logger.info(
          "Fetching Yahoo Finance quote for [{}] ({})", fund.getFundName(), fund.getFundId());
      Quote quote = page.getQuote(fund);
      logger.info(
          "  {} {} {}",
          quote.getTickerOrId(),
          quote.getPrice().toPlainString(),
          quote.getCurrency());
      quote.setProviderName(provider.getName());
      quotes.add(quote);
    }
    return quotes;
  }
}
