package org.lolobored.quotedownloader.service.scrapers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.scrapers.impl.YahooFinanceServiceImpl;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

class YahooFinanceServiceImplTest {

  private WebDriver mockDriver;
  private YahooFinanceServiceImpl service;

  @BeforeEach
  void setUp() {
    mockDriver = mock(WebDriver.class);
    service =
        new YahooFinanceServiceImpl() {
          @Override
          protected WebDriver createDriver() {
            return mockDriver;
          }
        };
  }

  @Test
  void requiresWebDriver_returnsFalse() {
    assertThat(service.requiresWebDriver()).isFalse();
  }

  @Test
  void fetchQuotes_returnsOneQuotePerFund() throws Exception {
    Provider provider = providerWith(fund("D05.SI", "DBS Group Holdings", "SGD"));
    stubPrice("58.77");

    List<Quote> quotes = service.fetchQuotes(null, provider);

    assertThat(quotes).hasSize(1);
    assertThat(quotes.get(0).getTickerOrId()).isEqualTo("D05.SI");
    assertThat(quotes.get(0).getName()).isEqualTo("DBS Group Holdings");
    assertThat(quotes.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("58.77"));
    assertThat(quotes.get(0).getCurrency()).isEqualTo("SGD");
    assertThat(quotes.get(0).getProviderName()).isEqualTo("yahoo");
  }

  @Test
  void fetchQuotes_multipleFunds_allReturned() throws Exception {
    Provider provider =
        providerWith(
            fund("D05.SI", "DBS", "SGD"),
            fund("O39.SI", "OCBC", "SGD"),
            fund("U11.SI", "UOB", "SGD"));
    stubPrice("10.00");

    List<Quote> quotes = service.fetchQuotes(null, provider);

    assertThat(quotes).hasSize(3);
    assertThat(quotes)
        .extracting(Quote::getTickerOrId)
        .containsExactly("D05.SI", "O39.SI", "U11.SI");
  }

  @Test
  void fetchQuotes_usesCurrencyFromConfig() throws Exception {
    Provider provider = providerWith(fund("D05.SI", "DBS", "SGD"));
    stubPrice("58.77");

    List<Quote> quotes = service.fetchQuotes(null, provider);

    assertThat(quotes.get(0).getCurrency()).isEqualTo("SGD");
  }

  @Test
  void fetchQuotes_defaultsCurrencyToSGD_whenNotExplicitlySet() throws Exception {
    Fund f = new Fund();
    f.setFundId("AAPL");
    f.setFundName("Apple");
    // currency not set — Fund defaults to "SGD"
    Provider provider = providerWith(f);
    stubPrice("200.00");

    List<Quote> quotes = service.fetchQuotes(null, provider);

    assertThat(quotes.get(0).getCurrency()).isEqualTo("SGD");
  }

  @Test
  void fetchQuotes_navigatesToCorrectUrl() throws Exception {
    Provider provider = providerWith(fund("D05.SI", "DBS", "SGD"));
    stubPrice("58.77");

    service.fetchQuotes(null, provider);

    verify(mockDriver).get("https://finance.yahoo.com/quote/D05.SI/");
  }

  @Test
  void fetchQuotes_closesDriverWhenDone() throws Exception {
    Provider provider = providerWith(fund("D05.SI", "DBS", "SGD"));
    stubPrice("58.77");

    service.fetchQuotes(null, provider);

    verify(mockDriver).quit();
  }

  @Test
  void fetchQuotes_closesDriverOnError() {
    Provider provider = providerWith(fund("BAD.SI", "Bad", "SGD"));
    when(mockDriver.findElement(any(By.class))).thenThrow(new NoSuchElementException("not found"));

    assertThatThrownBy(() -> service.fetchQuotes(null, provider));

    verify(mockDriver).quit();
  }

  @Test
  void fetchQuotes_stripsCommasFromPrice() throws Exception {
    Provider provider = providerWith(fund("BRK-A", "Berkshire", "USD"));
    stubPrice("650,000.00");

    List<Quote> quotes = service.fetchQuotes(null, provider);

    assertThat(quotes.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("650000.00"));
  }

  // --- helpers ---

  private void stubPrice(String text) {
    WebElement el = mock(WebElement.class);
    when(el.getText()).thenReturn(text);
    when(mockDriver.findElement(any(By.class))).thenReturn(el);
  }

  private static Fund fund(String id, String name, String currency) {
    Fund f = new Fund();
    f.setFundId(id);
    f.setFundName(name);
    f.setCurrency(currency);
    return f;
  }

  private static Provider providerWith(Fund... funds) {
    Provider p = new Provider();
    p.setName("yahoo");
    p.setFunds(List.of(funds));
    return p;
  }
}
