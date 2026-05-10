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
  // SMS MFA code field (optional — only shown when device not remembered)
  private static final By OTP_FIELD = By.name("callback_7");
  // Reused submit button id across credential, MFA, and remember-device pages
  private static final By SUBMIT_BUTTON = By.id("loginButton_0");

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
  private static final Duration OPTIONAL_WAIT = Duration.ofSeconds(8);

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

    // Optional SMS MFA step (skipped when device cookie is still valid)
    try {
      shortWait.until(ExpectedConditions.visibilityOfElementLocated(OTP_FIELD));
      String otpCode = readOtpFromFile();
      driver.findElement(OTP_FIELD).sendKeys(otpCode);
      jsClick(SUBMIT_BUTTON);
      logger.info("Royal London MFA code submitted");
    } catch (TimeoutException e) {
      logger.debug("No SMS MFA step, continuing");
    }

    // Optional "Remember my device" step (appears after MFA)
    try {
      shortWait.until(ExpectedConditions.presenceOfElementLocated(REMEMBER_DEVICE_HEADING));
      jsClick(SUBMIT_BUTTON);
      logger.info("Dismissed 'Remember my device' step");
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
