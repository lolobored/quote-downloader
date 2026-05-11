package org.lolobored.quotedownloader.service.scrapers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.scrapers.YahooFinanceService;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class YahooFinanceServiceImpl implements YahooFinanceService {

  private static final Logger logger = LoggerFactory.getLogger(YahooFinanceServiceImpl.class);

  private static final String CHART_URL =
      "https://query2.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1d";

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public boolean requiresWebDriver() {
    return false;
  }

  @Override
  public List<Quote> fetchQuotes(WebDriver driver, Provider provider) throws Exception {
    List<Quote> quotes = new ArrayList<>();
    for (Fund fund : provider.getFunds()) {
      logger.info(
          "Fetching Yahoo Finance quote for [{}] ({})", fund.getFundName(), fund.getFundId());
      Quote quote = fetchQuote(fund);
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

  private Quote fetchQuote(Fund fund) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(String.format(CHART_URL, fund.getFundId())))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .GET()
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Yahoo Finance API returned HTTP "
              + response.statusCode()
              + " for ticker ["
              + fund.getFundId()
              + "]");
    }

    JsonNode meta =
        objectMapper.readTree(response.body()).path("chart").path("result").path(0).path("meta");

    if (meta.isMissingNode()) {
      throw new RuntimeException(
          "No price data returned from Yahoo Finance for ticker [" + fund.getFundId() + "]");
    }

    BigDecimal price = new BigDecimal(meta.path("regularMarketPrice").asText());
    String currency =
        fund.getCurrency() != null ? fund.getCurrency() : meta.path("currency").asText("USD");

    return new Quote(fund.getFundName(), fund.getFundId(), price, currency, LocalDate.now());
  }
}
