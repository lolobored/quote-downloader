package org.lolobored.quotedownloader.service.scrapers.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.otp.OtpGate;
import org.lolobored.quotedownloader.service.scrapers.RoyalLondonService;
import org.lolobored.quotedownloader.service.scrapers.pages.royallondon.RoyalLondonFundPage;
import org.lolobored.quotedownloader.service.scrapers.pages.royallondon.RoyalLondonLoginPage;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoyalLondonServiceImpl implements RoyalLondonService {

  private static final Logger logger = LoggerFactory.getLogger(RoyalLondonServiceImpl.class);

  @Autowired private OtpGate otpGate;

  @Override
  public List<Quote> fetchQuotes(WebDriver driver, Provider provider) throws Exception {
    logger.info("Logging in to Royal London...");
    RoyalLondonLoginPage loginPage =
        new RoyalLondonLoginPage(driver, provider.getWaitTime(), otpGate);
    loginPage.login(provider.getConnectionUrl(), provider.getUsername(), provider.getPassword());

    RoyalLondonFundPage fundPage = new RoyalLondonFundPage(driver, provider.getWaitTime());
    List<Quote> quotes = new ArrayList<>();

    // Funds are grouped by policy number, stored in the portfolioName field
    Map<String, List<Fund>> byPolicy =
        provider.getFunds().stream()
            .collect(
                Collectors.groupingBy(
                    f -> f.getPortfolioName() != null ? f.getPortfolioName() : "",
                    LinkedHashMap::new,
                    Collectors.toList()));

    for (Map.Entry<String, List<Fund>> entry : byPolicy.entrySet()) {
      String policyNumber = entry.getKey();
      List<Fund> funds = entry.getValue();
      logger.info("Fetching {} funds from Royal London policy [{}]", funds.size(), policyNumber);
      List<Quote> policyQuotes = fundPage.getQuotesForPolicy(policyNumber, funds);
      policyQuotes.forEach(q -> q.setProviderName(provider.getName()));
      quotes.addAll(policyQuotes);
    }
    return quotes;
  }
}
