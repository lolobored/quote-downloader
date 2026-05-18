package org.lolobored.quotedownloader.service.scrapers.pages.royallondon;

import java.nio.file.Path;
import java.time.Duration;
import org.lolobored.quotedownloader.service.otp.OtpGate;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoyalLondonLoginPage {

  private static final Logger logger = LoggerFactory.getLogger(RoyalLondonLoginPage.class);

  // OpenAM callback fields — stable naming on this portal
  private static final By USERNAME_FIELD = By.name("callback_3");
  private static final By PASSWORD_FIELD = By.name("callback_4");
  // SMS MFA code field — try the legacy OpenAM callback name first, fall back to placeholder
  private static final By OTP_FIELD_CALLBACK = By.name("callback_7");
  private static final By OTP_FIELD_PLACEHOLDER = By.cssSelector("input[placeholder*='digit']");
  // Reused submit button id across credential, MFA, and remember-device pages
  private static final By SUBMIT_BUTTON = By.id("loginButton_0");
  // "Next" button used on the OTP page when the portal has been redesigned
  private static final By NEXT_BUTTON = By.xpath("//button[normalize-space(text())='Next']");

  // "Remember my device" intermediate page (appears after MFA, before login-choice)
  private static final By REMEMBER_DEVICE_HEADING =
      By.xpath("//h1[contains(text(), 'Remember my device')]");

  // "Continue to online services" button on the login-choice page
  private static final By CONTINUE_BUTTON =
      By.xpath("//button[normalize-space(text())='Continue']");

  // Final portal — both the frameset and sub-pages live under this host
  private static final String ESERVICE_URL_FRAGMENT = "royallondon.com/EService";

  // Cookie preferences banner — appears over the login form on fresh browser profiles
  private static final By COOKIE_ACCEPT_BUTTON =
      By.xpath("//button[normalize-space(text())='Accept all']");

  // Short timeout for optional steps — long enough for page transitions, short enough not to block
  private static final Duration OPTIONAL_WAIT = Duration.ofSeconds(15);

  private final WebDriver driver;
  private final WebDriverWait wait;
  private final OtpGate otpGate;

  public RoyalLondonLoginPage(WebDriver driver, int waitSeconds, OtpGate otpGate) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    this.otpGate = otpGate;
  }

  public void login(String url, String username, String password) throws InterruptedException {
    driver.get(url);

    wait.until(ExpectedConditions.visibilityOfElementLocated(USERNAME_FIELD)).sendKeys(username);
    wait.until(ExpectedConditions.visibilityOfElementLocated(PASSWORD_FIELD)).sendKeys(password);
    dismissCookieConsentIfPresent();
    jsClick(SUBMIT_BUTTON);

    WebDriverWait shortWait = new WebDriverWait(driver, OPTIONAL_WAIT);
    // Optional SMS MFA step (skipped when device is remembered)
    logger.info("Checking for SMS MFA step (current URL: {})", driver.getCurrentUrl());
    WebElement otpField = findOtpField();
    if (otpField != null) {
      String otpCode = readOtpFromFile();
      otpField.sendKeys(otpCode);
      submitOtpPage();
      logger.info("Royal London MFA code submitted");
    }

    // Optional "Remember my device" step — accept so the server marks this device as trusted
    try {
      shortWait.until(ExpectedConditions.presenceOfElementLocated(REMEMBER_DEVICE_HEADING));
      jsClick(SUBMIT_BUTTON);
      logger.info("Accepted 'Remember my device' — device will be trusted on next login");
    } catch (TimeoutException e) {
      logger.debug("No 'Remember my device' step, continuing");
    }

    // Optional login-choice page ("Continue to online services")
    try {
      shortWait.until(ExpectedConditions.urlContains("login-choice"));
      wait.until(ExpectedConditions.elementToBeClickable(CONTINUE_BUTTON)).click();
      logger.info("Clicked through login-choice page");
    } catch (TimeoutException e) {
      logger.debug("No login-choice step, continuing");
    }

    wait.until(ExpectedConditions.urlContains(ESERVICE_URL_FRAGMENT));
    logger.info("Royal London login successful");
  }

  private WebElement findOtpField() {
    // Wait for the page to finish transitioning: either an input re-appears (OTP form) or
    // the URL moves forward (device remembered, no OTP needed).
    try {
      new WebDriverWait(driver, OPTIONAL_WAIT)
          .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("input")));
    } catch (TimeoutException e) {
      logger.info("No inputs appeared after credential submit — no MFA step");
      return null;
    }

    // Log all inputs now visible so we can diagnose selector mismatches in the future
    try {
      Object inputs =
          ((JavascriptExecutor) driver)
              .executeScript(
                  "return Array.from(document.querySelectorAll('input')).map(i => "
                      + "({name: i.name, id: i.id, type: i.type, placeholder: i.placeholder}));");
      logger.info("Inputs on page after page transition: {}", inputs);
    } catch (Exception e) {
      logger.debug("Could not enumerate inputs: {}", e.getMessage());
    }

    // Try selectors: callback_6 (current), legacy callback_7/callback_0, any visible text input
    for (By selector :
        java.util.List.of(
            By.name("callback_6"),
            By.name("callback_0"),
            OTP_FIELD_CALLBACK,
            By.cssSelector("input[type='text']"))) {
      try {
        WebElement field =
            new WebDriverWait(driver, Duration.ofSeconds(3))
                .until(ExpectedConditions.presenceOfElementLocated(selector));
        logger.info("Royal London OTP field found via: {}", selector);
        return field;
      } catch (TimeoutException ignored) {
        // try next
      }
    }
    logger.info("No SMS MFA step detected, continuing");
    return null;
  }

  private void submitOtpPage() {
    try {
      jsClick(SUBMIT_BUTTON);
    } catch (TimeoutException e) {
      logger.debug("loginButton_0 not found, trying Next button");
      jsClick(NEXT_BUTTON);
    }
  }

  private String readOtpFromFile() throws InterruptedException {
    return otpGate.requestOtp("Royal London", Path.of("/tmp/rl-otp.txt"));
  }

  private void dismissCookieConsentIfPresent() throws InterruptedException {
    try {
      WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
      shortWait.until(ExpectedConditions.elementToBeClickable(COOKIE_ACCEPT_BUTTON)).click();
      logger.info("Dismissed cookie consent popup");
      Thread.sleep(2000);
    } catch (TimeoutException e) {
      logger.debug("No cookie consent popup, continuing");
    }
  }

  private void jsClick(By locator) {
    WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
  }
}
