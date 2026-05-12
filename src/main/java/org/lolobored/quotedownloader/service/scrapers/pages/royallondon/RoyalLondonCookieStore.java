package org.lolobored.quotedownloader.service.scrapers.pages.royallondon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.openqa.selenium.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RoyalLondonCookieStore {

  private static final Logger logger = LoggerFactory.getLogger(RoyalLondonCookieStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final File STORE_FILE =
      new File(System.getProperty("user.home"), ".quote-downloader/royallondon-cookies.json");

  void save(Set<Cookie> cookies) {
    try {
      STORE_FILE.getParentFile().mkdirs();
      List<StoredCookie> stored =
          cookies.stream().map(StoredCookie::from).collect(Collectors.toList());
      MAPPER.writeValue(STORE_FILE, stored);
      logger.info("Saved {} Royal London cookies to {}", stored.size(), STORE_FILE);
    } catch (IOException e) {
      logger.warn("Could not save Royal London cookies: {}", e.getMessage());
    }
  }

  List<Cookie> load() {
    if (!STORE_FILE.exists()) return List.of();
    try {
      List<StoredCookie> stored =
          MAPPER.readValue(STORE_FILE, new TypeReference<List<StoredCookie>>() {});
      return stored.stream().map(StoredCookie::toCookie).collect(Collectors.toList());
    } catch (IOException e) {
      logger.warn("Could not load Royal London cookies: {}", e.getMessage());
      return List.of();
    }
  }

  static class StoredCookie {
    public String name, value, domain, path;
    public Long expiryMs;
    public boolean secure, httpOnly;

    public static StoredCookie from(Cookie c) {
      StoredCookie s = new StoredCookie();
      s.name = c.getName();
      s.value = c.getValue();
      s.domain = c.getDomain();
      s.path = c.getPath();
      s.expiryMs = c.getExpiry() != null ? c.getExpiry().getTime() : null;
      s.secure = c.isSecure();
      s.httpOnly = c.isHttpOnly();
      return s;
    }

    public Cookie toCookie() {
      return new Cookie.Builder(name, value)
          .domain(domain)
          .path(path)
          .expiresOn(expiryMs != null ? new Date(expiryMs) : null)
          .isSecure(secure)
          .isHttpOnly(httpOnly)
          .build();
    }
  }
}
