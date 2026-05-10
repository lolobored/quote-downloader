package org.lolobored.quotedownloader.service.scrapers.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.scrapers.OCBCInvestService;
import org.lolobored.quotedownloader.service.scrapers.pages.ocbcinvest.OCBCInvestFundPage;
import org.lolobored.quotedownloader.service.scrapers.pages.ocbcinvest.OCBCInvestLoginPage;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OCBCInvestServiceImpl implements OCBCInvestService {

  private static final Logger logger = LoggerFactory.getLogger(OCBCInvestServiceImpl.class);

  @Override
  public List<Quote> fetchQuotes(WebDriver driver, Provider provider) throws Exception {
    logger.info("Logging in to OCBC Investment...");
    OCBCInvestLoginPage loginPage = new OCBCInvestLoginPage(driver, provider.getWaitTime());
    loginPage.login(provider.getConnectionUrl(), provider.getUsername(), provider.getPassword());
    logger.info("OCBC Investment login successful");

    OCBCInvestFundPage fundPage = new OCBCInvestFundPage(driver, provider.getWaitTime());
    List<Quote> quotes = new ArrayList<>();

    Map<String, List<Fund>> byPortfolio =
        provider.getFunds().stream()
            .collect(
                Collectors.groupingBy(
                    f -> f.getPortfolioName() != null ? f.getPortfolioName() : "",
                    LinkedHashMap::new,
                    Collectors.toList()));

    for (Map.Entry<String, List<Fund>> entry : byPortfolio.entrySet()) {
      String portfolioName = entry.getKey();
      List<Fund> funds = entry.getValue();
      logger.info("Fetching {} funds from portfolio [{}]", funds.size(), portfolioName);
      List<Quote> portfolioQuotes = fundPage.getQuotesForPortfolio(portfolioName, funds);
      portfolioQuotes.forEach(q -> q.setProviderName(provider.getName()));
      quotes.addAll(portfolioQuotes);
    }
    return quotes;
  }
}
