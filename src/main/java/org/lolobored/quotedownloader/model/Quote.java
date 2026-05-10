package org.lolobored.quotedownloader.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Data
public class Quote {
  private String providerName;
  private String name;
  private String tickerOrId;
  private BigDecimal price;
  private String currency;
  private LocalDate date;

  public Quote(String name, String tickerOrId, BigDecimal price, String currency, LocalDate date) {
    this.name = name;
    this.tickerOrId = tickerOrId;
    this.price = price;
    this.currency = currency;
    this.date = date;
  }
}
