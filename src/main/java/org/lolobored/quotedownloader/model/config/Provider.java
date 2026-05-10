package org.lolobored.quotedownloader.model.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class Provider {
  private String name;
  private String username;
  private String password;
  private String connectionUrl;
  /** Timeout in seconds for WebDriver waits. Higher default to allow time for MFA flows. */
  private int waitTime = 120;

  private List<Fund> funds = new ArrayList<>();
  /** If set, credentials are fetched from Bitwarden instead of the fields above. */
  private String bitwardenItemName;

  private boolean enabled = true;
}
