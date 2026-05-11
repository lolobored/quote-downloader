package org.lolobored.quotedownloader.service.scrapers;

import java.util.List;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Provider;
import org.openqa.selenium.WebDriver;

public interface QuoteProviderService {

  List<Quote> fetchQuotes(WebDriver driver, Provider provider) throws Exception;

  default boolean requiresWebDriver() {
    return true;
  }
}
