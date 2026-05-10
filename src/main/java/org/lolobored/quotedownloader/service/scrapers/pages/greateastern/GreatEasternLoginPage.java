package org.lolobored.quotedownloader.service.scrapers.pages.greateastern;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.lolobored.quotedownloader.service.otp.OtpGate;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GreatEasternLoginPage {

  private static final Logger logger = LoggerFactory.getLogger(GreatEasternLoginPage.class);

  // eConnect landing page — Login div (text in child <span>, use CSS class for stability)
  private static final By ECONNECT_LOGIN_BUTTON = By.cssSelector("div.signin-greatId");

  // Cookie/terms consent popup on the SSO page (first-time or periodic)
  private static final By CONSENT_OK_BUTTON =
      By.xpath("//button[@type='submit' and normalize-space(text())='Ok']");

  // Great ID SSO credentials
  private static final By USERNAME_FIELD = By.id("username");
  private static final By PASSWORD_FIELD = By.id("password");
  private static final By CONTINUE_BUTTON = By.id("btnSubmit");

  // 6-digit OTP inputs (separate boxes, no id/name)
  private static final By OTP_INPUTS = By.cssSelector("input.leo-input-otp__input");

  // Primary leo button — used on both OTP and T&C pages
  private static final By LEO_PRIMARY_BUTTON = By.cssSelector("button.leo-button--primary");

  private static final String DASHBOARD_URL_FRAGMENT = "dashboard";
  private static final Duration OPTIONAL_WAIT = Duration.ofSeconds(3);

  private final WebDriver driver;
  private final WebDriverWait wait;
  private final OtpGate otpGate;

  public GreatEasternLoginPage(WebDriver driver, int waitSeconds, OtpGate otpGate) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
    this.otpGate = otpGate;
  }

  public void login(String url, String username, String password) throws InterruptedException {
    driver.get(url);
    dismissConsentIfPresent();

    wait.until(ExpectedConditions.elementToBeClickable(ECONNECT_LOGIN_BUTTON)).click();
    logger.info("GE login button clicked, waiting for SSO sign-in form...");

    // Username field is present but not visible due to Chrome FedCM overlay — use presence only
    wait.until(ExpectedConditions.presenceOfElementLocated(USERNAME_FIELD));
    logger.info("GE SSO page reached: {}", driver.getCurrentUrl());
    dismissConsentIfPresent();

    JavascriptExecutor js = (JavascriptExecutor) driver;
    jsSetInputValue(js, driver.findElement(USERNAME_FIELD), username);
    jsSetInputValue(js, driver.findElement(PASSWORD_FIELD), password);
    js.executeScript("arguments[0].click();", driver.findElement(CONTINUE_BUTTON));
    logger.info("GE credentials submitted");

    wait.until(ExpectedConditions.urlContains("otp"));
    logger.info("GE OTP page detected");
    handleOtp();

    // Portal may show a T&C acceptance page before landing on dashboard
    acceptTermsIfPresent();

    wait.until(ExpectedConditions.urlContains(DASHBOARD_URL_FRAGMENT));
    logger.info("Great Eastern login successful");
  }

  private void handleOtp() throws InterruptedException {
    String otpCode = readOtpFromFile();
    JavascriptExecutor js = (JavascriptExecutor) driver;

    // OTP inputs have same visibility issue — use presence then keyboard-event simulation
    WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
    shortWait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(OTP_INPUTS));
    List<WebElement> inputs = driver.findElements(OTP_INPUTS);
    logger.info("GE OTP: found {} input boxes", inputs.size());

    // Type each digit directly into its own box via full JS keyboard event simulation.
    // This bypasses auto-advance reliance and ensures Angular change detection fires per box.
    for (int i = 0; i < Math.min(otpCode.length(), inputs.size()); i++) {
      jsTypeOtpDigit(js, inputs.get(i), String.valueOf(otpCode.charAt(i)));
      Thread.sleep(200);
    }

    // Brief pause before submitting so the portal registers all digit inputs
    Thread.sleep(2000);

    // Submit by pressing Enter on the last OTP input — generates a trusted keyboard event.
    // The submit button has no Selenium-visible size (FedCM overlay) so click approaches fail.
    WebDriverWait urlWait = new WebDriverWait(driver, Duration.ofSeconds(10));
    WebElement lastInput = inputs.get(inputs.size() - 1);
    for (int attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) {
        Thread.sleep(1000);
        logger.warn("OTP submit attempt {}, retrying...", attempt + 1);
      }
      try {
        js.executeScript("arguments[0].focus();", lastInput);
        new Actions(driver).sendKeys(Keys.RETURN).perform();
        urlWait.until(ExpectedConditions.not(ExpectedConditions.urlContains("otp")));
        return;
      } catch (TimeoutException e) {
        logger.debug("Still on OTP page after attempt {}", attempt + 1);
      }
    }
    throw new RuntimeException("Failed to pass Great Eastern OTP after 3 submit attempts.");
  }

  private void acceptTermsIfPresent() {
    try {
      WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
      shortWait.until(ExpectedConditions.urlContains("terms"));
      // T&C page auto-redirects to dashboard within a few seconds — no button click needed.
      logger.info("GE T&C page detected, waiting for auto-redirect...");
    } catch (TimeoutException e) {
      logger.debug("No T&C page, continuing to dashboard");
    }
  }

  private String readOtpFromFile() throws InterruptedException {
    return otpGate.requestOtp("Great Eastern", Path.of("/tmp/ge-otp.txt"));
  }

  private void dismissConsentIfPresent() {
    try {
      WebDriverWait shortWait = new WebDriverWait(driver, OPTIONAL_WAIT);
      shortWait.until(ExpectedConditions.elementToBeClickable(CONSENT_OK_BUTTON)).click();
      logger.info("Dismissed consent popup");
    } catch (TimeoutException e) {
      logger.debug("No consent popup, continuing");
    }
  }

  // Uses the native HTMLInputElement value setter so Angular/React change detection fires.
  private void jsSetInputValue(JavascriptExecutor js, WebElement element, String value) {
    js.executeScript(
        "var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
            + "nativeSetter.call(arguments[0], arguments[1]);"
            + "arguments[0].dispatchEvent(new Event('input', {bubbles: true}));"
            + "arguments[0].dispatchEvent(new Event('change', {bubbles: true}));",
        element,
        value);
  }

  // Simulates real keystrokes for OTP digit boxes (keydown/keypress/value/input/keyup).
  private void jsTypeOtpDigit(JavascriptExecutor js, WebElement element, String digit) {
    js.executeScript(
        "var el = arguments[0]; var d = arguments[1];"
            + "var kc = d.charCodeAt(0);"
            + "el.focus();"
            + "el.dispatchEvent(new KeyboardEvent('keydown', {key: d, code: 'Digit'+d, keyCode: kc, which: kc, bubbles: true, cancelable: true}));"
            + "el.dispatchEvent(new KeyboardEvent('keypress', {key: d, code: 'Digit'+d, keyCode: kc, which: kc, bubbles: true, cancelable: true}));"
            + "Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, d);"
            + "el.dispatchEvent(new Event('input', {bubbles: true}));"
            + "el.dispatchEvent(new KeyboardEvent('keyup', {key: d, code: 'Digit'+d, keyCode: kc, which: kc, bubbles: true}));",
        element,
        digit);
  }
}
