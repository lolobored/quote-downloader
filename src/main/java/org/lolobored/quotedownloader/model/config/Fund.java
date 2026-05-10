package org.lolobored.quotedownloader.model.config;

import lombok.Data;

@Data
public class Fund {
  /**
   * For providers that require navigating to a named portfolio/account first (e.g. OCBC "Wealth
   * Portfolio"). Leave null for providers where no intermediate navigation is needed.
   */
  private String portfolioName;

  /**
   * Identifier used on the provider's website to find this specific fund (ticker, fund code, name,
   * etc.)
   */
  private String fundId;

  /** Human-readable display name for the fund or stock. */
  private String fundName;

  private String currency = "SGD";
}
